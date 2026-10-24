package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-slot foreground video pool for pager-style feeds. A prewarm slot decodes
 * silently into a retained, off-screen TextureView. Promotion reveals that same
 * surface, so no decoder or SurfaceTexture is replaced at the page boundary.
 */
final class PodVideoSessionServices implements AutoCloseable {
    interface Callback { void complete(JSONObject value); void fail(String code, String message); }

    static final int MAX_SESSIONS = 2;
    static final int TARGET_BUFFER_BYTES = 6 * 1024 * 1024;
    static final long PREWARM_TIMEOUT_MS = 2_500L;
    private static final float PREWARM_ALPHA = 0.01f;
    private static final String CAPABILITY = "media.video.sessions";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File mediaRoot;
    private final PodMediaPrefixCache mediaPrefixCache;
    private final PodVideoDecoderBudget decoderBudget;
    private final PodVideoDecoderCapabilities decoderCapabilities;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private ViewGroup parent;
    private boolean closed;
    private boolean lifecyclePaused;
    private float density;
    private PodVideoDecoderCapabilities.Profile lastProfile;
    private PodVideoDecoderCapabilities.Result lastCapability;

    PodVideoSessionServices(Context context, PodVideoDecoderBudget decoderBudget) {
        this(context,decoderBudget,null);
    }
    PodVideoSessionServices(Context context, PodVideoDecoderBudget decoderBudget, PodMediaPrefixCache cache) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        this.decoderBudget = decoderBudget;
        this.mediaPrefixCache=cache;
        decoderCapabilities = new PodVideoDecoderCapabilities(this.context);
        try { mediaRoot = PodTakeover.filesRoot(this.context).getCanonicalFile(); }
        catch (Exception error) { throw new IllegalStateException("service_root_unavailable", error); }
        density = this.context.getResources().getDisplayMetrics().density;
    }

    void attachToParent(ViewGroup host) {
        main.post(() -> {
            if (closed || host == null) return;
            parent = host;
            density = context.getResources().getDisplayMetrics().density;
            for (Session session : sessions.values()) session.attachViews(host);
        });
    }

    void lifecycle(boolean resumed) {
        main.post(() -> {
            lifecyclePaused = !resumed;
            if (!resumed) {
                for (Session session : sessions.values()) {
                    if (session.player != null) session.player.pause();
                    if (session.player != null && !session.prewarming) session.state = "paused";
                }
            }
        });
    }

    void dispatch(String method, JSONObject args, Callback callback) {
        main.post(() -> {
            if (closed) { callback.fail("service_closed", "Video session service is closed"); return; }
            try {
                switch (method) {
                    case "video.session.captureFrame": captureFrame(args,callback); break;
                    case "video.session.capabilities": callback.complete(capabilities(args)); break;
                    case "video.session.prepare": prepare(args, callback); break;
                    case "video.session.play": play(args, callback); break;
                    case "video.session.pause": pause(args, callback); break;
                    case "video.session.seek": seek(args, callback); break;
                    case "video.session.stop": stop(args, callback); break;
                    case "video.session.release": release(args, callback); break;
                    case "video.session.setBounds": setBounds(args, callback); break;
                    case "video.session.setTransform": setTransform(args, callback); break;
                    case "video.session.setLoop": setLoop(args, callback); break;
                    case "video.session.setVolume": setVolume(args, callback); break;
                    case "video.session.state": callback.complete(require(args).snapshot()); break;
                    case "video.session.promote": promote(args, callback); break;
                    case "video.session.transition": transition(args, callback); break;
                    default: callback.fail("unsupported_method", "Unsupported video session method: " + method);
                }
            } catch (Exception error) {
                callback.fail(errorCode(error), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    void cancel(String sessionId) {
        if (sessionId == null) return;
        main.post(() -> {
            Session session = sessions.get(sessionId);
            if (session == null || session.prepareCallback == null) return;
            Callback pending = session.takePrepareCallback();
            session.resetPlayer();
            session.state = "stopped";
            session.hideOffscreen();
            pending.fail("cancelled", "Video session preparation cancelled");
        });
    }

    private JSONObject capabilities(JSONObject args) throws Exception {
        PodVideoDecoderCapabilities.Profile requested = profileFromArguments(args, false);
        PodVideoDecoderCapabilities.Result capability = requested == null ? lastCapability : decoderCapabilities.inspect(requested);
        int limit = capability == null ? decoderBudget.limit() : Math.min(decoderBudget.limit(), capability.limit);
        JSONObject value = new JSONObject()
                .put("capability", CAPABILITY)
                .put("maxSessions", MAX_SESSIONS)
                .put("decoderLimit", limit)
                .put("decoderInUse", decoderBudget.inUse())
                .put("targetBufferBytesPerSession", TARGET_BUFFER_BYTES)
                .put("targetBufferBytesTotal", (long) TARGET_BUFFER_BYTES * limit)
                .put("prewarmTimeoutMs", PREWARM_TIMEOUT_MS)
                .put("prewarmMode", "hidden_first_frame")
                .put("surfacePromotion", limit >= 2)
                .put("verticalTransition", limit >= 2)
                .put("surfaceComposition", "android_texture_view")
                .put("backgroundPlayback", false);
        if (capability != null) {
            JSONObject details = capability.json();
            java.util.Iterator<String> keys = details.keys();
            while (keys.hasNext()) { String key = keys.next(); value.put(key, details.get(key)); }
        }
        return value;
    }

    private void prepare(JSONObject args, Callback callback) throws Exception {
        if (parent == null) throw new IllegalStateException("video_surface_unavailable");
        if (args.optBoolean("backgroundPlayback", false)) throw new IllegalArgumentException("background_playback_unsupported");
        String id = sessionId(args, "sessionId");
        Bounds bounds = Bounds.read(args.optJSONObject("bounds"), density, true);
        boolean prewarming = args.optBoolean("prewarm", false);
        PodVideoDecoderCapabilities.Profile profile = profileFromArguments(args, true);
        if ((profile == null || !profile.known()) && prewarming) profile = lastProfile;
        if (profile != null && profile.known()) {
            PodVideoDecoderCapabilities.Result capability = decoderCapabilities.inspect(profile);
            lastProfile = profile; lastCapability = capability; decoderBudget.restrictTo(capability.limit);
        }
        MediaSource source = mediaSource(args);
        Session session = sessions.get(id);
        boolean created = false;
        if (session == null) {
            if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("video_session_limit");
            session = new Session(id);
            sessions.put(id, session);
            created = true;
        }
        if (session.prepareCallback != null) session.takePrepareCallback().fail("cancelled", "Video session preparation replaced");
        session.resetPlayer();
        if (!decoderBudget.acquire()) {
            if (created) { sessions.remove(id); session.close(); }
            throw new IllegalStateException("video_decoder_limit");
        }
        session.decoderLease = true;
        session.generation += 1;
        final long generation = session.generation;
        session.frameSource=new JSONObject(args.toString());
        session.bounds = bounds;
        session.prewarming = prewarming;
        session.profile = profile;
        session.autoplay = args.optBoolean("autoplay", true);
        session.muted = args.optBoolean("muted", false);
        session.volume = session.muted ? 0f : 1f;
        session.errorCode = null;
        session.state = "preparing";
        session.hasRenderedFirstFrame = false;
        session.presented = !session.prewarming && bounds.visible;
        session.prepareCallback = callback;
        session.applyBounds(bounds);
        if (session.prewarming) session.hideOffscreen(); else session.showPresented();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setBufferDurationsMs(1_000, 4_000, 100, 250)
                .build();
        ExoPlayer player;
        try { player = new ExoPlayer.Builder(context).setLoadControl(loadControl).build(); }
        catch (RuntimeException error) {
            session.takePrepareCallback(); session.resetPlayer(); session.state = "error"; session.hideOffscreen();
            if (created) { sessions.remove(id); session.close(); }
            throw error;
        }
        session.player = player;
        Session own = session;
        player.setAudioAttributes(mediaAudioAttributes(), !session.prewarming);
        session.completedLoops = 0;
        player.setRepeatMode(args.optBoolean("loop", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setVolume(session.prewarming ? 0f : session.volume);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                if (own.matches(player, generation)) own.fitVideo();
            }

            @Override public void onTracksChanged(Tracks tracks) {
                if (!own.matches(player, generation)) return;
                for (Tracks.Group group : tracks.getGroups()) {
                    for (int index = 0; index < group.length; index++) {
                        if (!group.isTrackSelected(index)) continue;
                        androidx.media3.common.Format format = group.getTrackFormat(index);
                        if (format.sampleMimeType == null || !format.sampleMimeType.startsWith("video/")) continue;
                        own.profile = new PodVideoDecoderCapabilities.Profile(format.sampleMimeType, format.width, format.height);
                        PodVideoDecoderCapabilities.Result capability = decoderCapabilities.inspect(own.profile);
                        lastProfile = own.profile; lastCapability = capability; decoderBudget.restrictTo(capability.limit);
                        if (own.prewarming && decoderBudget.inUse() > capability.limit) {
                            failPrepare(own, player, generation, "resource_exhausted", "video_decoder_limit", false);
                        }
                        return;
                    }
                }
            }

            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if(own.matches(player, generation) && reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)own.completedLoops++;
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (!own.matches(player, generation)) return;
                if (playbackState == Player.STATE_BUFFERING) own.state = "buffering";
                else if (playbackState == Player.STATE_ENDED) own.state = "ended";
                else if (playbackState == Player.STATE_READY && !own.prewarming) {
                    own.state = player.isPlaying() ? "playing" : "ready";
                    own.completePrepare();
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (!own.matches(player, generation) || own.prewarming) return;
                own.state = player.getPlaybackState()==Player.STATE_ENDED ? "ended" : playing ? "playing" : "paused";
            }

            @Override public void onRenderedFirstFrame() {
                if (!own.matches(player, generation)) return;
                own.hasRenderedFirstFrame = true;
                if (own.prewarming) {
                    player.pause();
                    player.setPlayWhenReady(false);
                    own.state = "prewarmed";
                    own.hideOffscreen();
                    own.completePrepare();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (!own.matches(player, generation)) return;
                boolean decoderFailure = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
                String code = PodPlaybackFailure.isNetwork(error) ? "network_error" : "prepare_failed";
                if ("network_error".equals(code) && own.prepareCallback == null) {
                    // Retain the terminal state for polling, but release decoder/player resources.
                    own.errorCode = code; own.state = "error"; own.resetPlayer(); own.hideOffscreen();
                    return;
                }
                failPrepare(own, player, generation, code,
                        "Video playback failed",
                        own.prewarming && decoderFailure);
            }
        });
        try {
            player.setMediaSource(source);
            player.setVideoTextureView(session.videoView);
            player.prepare();
            if (session.prewarming) player.play();
            else if (session.autoplay && !lifecyclePaused) player.play();
            else player.pause();
            if (session.prewarming) session.armPrewarmTimeout(player, generation);
        } catch (RuntimeException error) {
            session.takePrepareCallback(); session.resetPlayer(); session.state = "error"; session.hideOffscreen();
            if (created) { sessions.remove(id); session.close(); }
            throw error;
        }
    }

    private void failPrepare(Session session, ExoPlayer player, long generation, String code, String message, boolean downgrade) {
        if (!session.matches(player, generation)) return;
        Callback pending = session.takePrepareCallback();
        PodVideoDecoderCapabilities.Profile profile = session.profile;
        if (downgrade && profile != null && profile.known()) {
            decoderCapabilities.recordSingleInstance(profile);
            decoderBudget.restrictTo(1);
            lastProfile = profile;
            PodVideoDecoderCapabilities.Result probed = decoderCapabilities.inspect(profile);
            lastCapability = new PodVideoDecoderCapabilities.Result(profile, probed.decoderName,
                    probed.advertisedInstances, 1, probed.sizeSupported, true);
        }
        session.state = "error";
        if (sessions.get(session.id) == session) sessions.remove(session.id);
        session.close();
        if (pending != null) pending.fail(code, message);
    }

    private void captureFrame(JSONObject args,Callback callback)throws Exception {
        Session session=require(args);session.requirePlayer();
        String key=PodVideoFrameCache.key(args),binding=PodVideoFrameCache.binding(args.getJSONObject("source"),mediaPrefixCache);
        if(session.prewarming||!binding.equals(PodVideoFrameCache.binding(session.frameSource,mediaPrefixCache)))throw new IllegalStateException("frame_source_changed");
        android.graphics.Bitmap frame=PodVideoFrameCache.capture(session.videoView,session.hasRenderedFirstFrame,session.generation,args);
        long generation=session.generation,position=session.player.getCurrentPosition();
        try{PodVideoFrameCache.IO.execute(()->{try{JSONObject result=new PodVideoFrameCache(mediaRoot).save(key,binding,generation,position,frame);main.post(()->callback.complete(result));}catch(Exception error){main.post(()->callback.fail("frame_unavailable","Frame snapshot unavailable"));}finally{frame.recycle();}});}catch(java.util.concurrent.RejectedExecutionException busy){frame.recycle();throw new IllegalStateException("frame_queue_busy");}
    }

    private void play(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        session.prewarming = false;
        session.player.setAudioAttributes(mediaAudioAttributes(), true);
        session.player.setVolume(session.volume);
        if (!lifecyclePaused) session.player.play();
        session.state = lifecyclePaused ? "paused" : "playing";
        callback.complete(session.snapshot());
    }

    private void pause(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        session.player.pause(); session.state = session.prewarming ? "prewarmed" : "paused";
        callback.complete(session.snapshot());
    }

    private void seek(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        long position = args.optLong("positionMs", -1);
        if (position < 0) throw new IllegalArgumentException("invalid_position");
        session.player.seekTo(position); callback.complete(session.snapshot());
    }

    private void stop(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        Callback pending = session.takePrepareCallback();
        session.resetPlayer(); session.state = "stopped"; session.presented = false; session.hideOffscreen();
        if (pending != null) pending.fail("cancelled", "Video session preparation stopped");
        callback.complete(session.snapshot());
    }

    private void release(JSONObject args, Callback callback) throws Exception {
        String id = sessionId(args, "sessionId");
        Session session = sessions.remove(id);
        if (session != null) session.close();
        callback.complete(new JSONObject().put("sessionId", id).put("released", session != null));
    }

    private void setBounds(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        Bounds bounds = Bounds.read(args.optJSONObject("bounds"), density, true);
        session.bounds = bounds; session.applyBounds(bounds);
        if (session.presented) session.showPresented(); else session.hideOffscreen();
        callback.complete(session.snapshot());
    }

    private void setTransform(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        String mode = args.optString("scaleMode", "standard");
        if (!mode.equals("standard") && !mode.equals("expanded") && !mode.equals("shrunk")) throw new IllegalArgumentException("invalid_scale_mode");
        float rotation = finite(args, "rotationDeg", 0, -100000, 100000);
        float panX = finite(args, "panX", 0, -4096, 4096);
        float panY = finite(args, "panY", 0, -4096, 4096);
        session.scaleMode = mode; session.rotationDeg = Math.round(rotation * 100f) / 100f; session.panX = panX; session.panY = panY;
        session.fitVideo(); callback.complete(session.snapshot());
    }

    private void setLoop(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        Object loop=args.opt("loop");
        if(!(loop instanceof Boolean))throw new IllegalArgumentException("loop_must_be_boolean");
        session.player.setRepeatMode((Boolean)loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        callback.complete(session.snapshot());
    }

    private void setVolume(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        if (!args.has("volume")) throw new IllegalArgumentException("invalid_volume");
        float volume = finite(args, "volume", -1, 0, 1);
        session.volume = volume; session.muted = volume == 0f;
        if (session.player != null) session.player.setVolume(session.prewarming ? 0f : volume);
        callback.complete(session.snapshot());
    }

    private void promote(JSONObject args, Callback callback) throws Exception {
        Session target = require(args);
        target.requirePlayer();
        if (!target.hasRenderedFirstFrame) throw new IllegalStateException("video_not_prewarmed");
        Bounds requested = args.has("bounds") ? Bounds.read(args.optJSONObject("bounds"), density, true) : target.bounds;
        if (requested == null) throw new IllegalArgumentException("missing_bounds");
        String replacedId = args.optString("replaceSessionId", "");
        if (!replacedId.isEmpty() && replacedId.equals(target.id)) throw new IllegalArgumentException("invalid_replace_session");
        Session replaced = replacedId.isEmpty() ? null : sessions.get(replacedId);
        if (!replacedId.isEmpty() && replaced == null) throw new IllegalArgumentException("unknown_replace_session");

        target.bounds = requested;
        target.prewarming = false;
        target.presented = requested.visible;
        target.applyBounds(requested);
        // Reveal the retained first-frame surface before hiding the old one.
        target.showPresented();
        if (replaced != null) {
            if (replaced.player != null) replaced.player.pause();
            replaced.prewarming = true;
            replaced.presented = false;
            if (replaced.player != null) replaced.player.setVolume(0f);
            replaced.state = replaced.player == null ? replaced.state : "prewarmed";
            replaced.hideOffscreen();
        }
        boolean autoplay = args.optBoolean("autoplay", true);
        target.player.setAudioAttributes(mediaAudioAttributes(), true);
        target.player.setVolume(target.volume);
        if (autoplay && !lifecyclePaused) { target.player.play(); target.state = "playing"; }
        else { target.player.pause(); target.state = "paused"; }
        callback.complete(target.snapshot());
    }

    private void transition(JSONObject args, Callback callback) throws Exception {
        String fromId = sessionId(args, "fromSessionId");
        String toId = sessionId(args, "toSessionId");
        if (fromId.equals(toId)) throw new IllegalArgumentException("invalid_transition_sessions");
        Session from = sessions.get(fromId), to = sessions.get(toId);
        if (from == null || to == null) throw new IllegalArgumentException("unknown_transition_session");
        if (!to.hasRenderedFirstFrame) throw new IllegalStateException("video_not_prewarmed");
        if (!args.has("progress")) throw new IllegalArgumentException("invalid_progress");
        float progress = finite(args, "progress", -1, 0, 1);
        String direction = args.optString("direction", "up");
        if (!direction.equals("up") && !direction.equals("down")) throw new IllegalArgumentException("invalid_transition_direction");
        Bounds bounds = args.has("bounds") ? Bounds.read(args.optJSONObject("bounds"), density, true) : from.bounds;
        if (bounds == null) throw new IllegalArgumentException("missing_bounds");
        from.bounds = bounds; to.bounds = bounds; from.applyBounds(bounds); to.applyBounds(bounds);
        from.videoView.setVisibility(View.VISIBLE); to.videoView.setVisibility(View.VISIBLE);
        from.videoView.setAlpha(1f); to.videoView.setAlpha(1f);
        from.background.setVisibility(View.VISIBLE); to.background.setVisibility(View.VISIBLE);
        float[] offsets = transitionOffsets(bounds.heightPx, progress, direction);
        from.setTranslation(bounds.translateXPx, offsets[0] + bounds.translateYPx);
        to.setTranslation(bounds.translateXPx, offsets[1] + bounds.translateYPx);
        callback.complete(new JSONObject()
                .put("fromSessionId", fromId).put("toSessionId", toId)
                .put("progress", progress).put("direction", direction)
                .put("fromOffsetYPx", offsets[0]).put("toOffsetYPx", offsets[1])
                .put("composited", true));
    }

    static float[] transitionOffsets(float heightPx, float progress, String direction) {
        float p = Math.max(0f, Math.min(1f, progress));
        if ("down".equals(direction)) return new float[]{p * heightPx, -(1f - p) * heightPx};
        return new float[]{-p * heightPx, (1f - p) * heightPx};
    }

    private MediaSource mediaSource(JSONObject args) throws Exception {
        String videoUrl = args.optString("videoUrl", "");
        String audioUrl = args.optString("audioUrl", "");
        String url = videoUrl.isEmpty() || audioUrl.isEmpty() ? source(args, "url") : sourceValue(videoUrl);
        if (!audioUrl.isEmpty()) audioUrl = sourceValue(audioUrl);
        if (!videoUrl.isEmpty()) videoUrl = sourceValue(videoUrl);
        Map<String, String> headers = headers(args.optJSONObject("headers"));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory();
        if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(mediaPrefixCache==null?new DefaultDataSource.Factory(context,http):mediaPrefixCache.factory(new DefaultDataSource.Factory(context,http)));
        if (!videoUrl.isEmpty() && !audioUrl.isEmpty()) {
            return new MergingMediaSource(factory.createMediaSource(item(videoUrl, args.optString("type", ""))), factory.createMediaSource(item(audioUrl, "")));
        }
        return factory.createMediaSource(item(url, args.optString("type", "")));
    }

    private PodVideoDecoderCapabilities.Profile profileFromArguments(JSONObject args, boolean inspectLocal) throws Exception {
        String mime = args.optString("videoMime", "").trim().toLowerCase(java.util.Locale.US);
        if (mime.isEmpty()) {
            String type = args.optString("type", "").trim().toLowerCase(java.util.Locale.US);
            if (type.equals("video/avc") || type.equals("video/hevc") || type.equals("video/mp4v-es")
                    || type.equals("video/x-vnd.on2.vp8") || type.equals("video/x-vnd.on2.vp9")
                    || type.equals("video/av01")) mime = type;
        }
        int width = positiveInt(args, "videoWidth"), height = positiveInt(args, "videoHeight");
        if (!mime.isEmpty()) return new PodVideoDecoderCapabilities.Profile(mime, width, height);
        if (!inspectLocal) return null;
        String videoUrl = args.optString("videoUrl", ""), audioUrl = args.optString("audioUrl", "");
        String value = !videoUrl.isEmpty() && !audioUrl.isEmpty() ? videoUrl : args.optString("url", "");
        if (value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")) return null;
        File file = new File(mediaRoot, value).getCanonicalFile();
        if (value.startsWith("/") || !file.getPath().startsWith(mediaRoot.getPath() + File.separator) || !file.isFile()) return null;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String trackMime = format.getString(MediaFormat.KEY_MIME);
                if (trackMime == null || !trackMime.startsWith("video/")) continue;
                int trackWidth = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
                int trackHeight = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
                return new PodVideoDecoderCapabilities.Profile(trackMime, trackWidth, trackHeight);
            }
        } finally { extractor.release(); }
        return null;
    }

    private static int positiveInt(JSONObject args, String key) {
        if (!args.has(key)) return 0;
        Object raw = args.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_" + key);
        int value = ((Number) raw).intValue();
        if (value <= 0 || value > 16_384) throw new IllegalArgumentException("invalid_" + key);
        return value;
    }

    private MediaItem item(String url, String type) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(url));
        if (!type.isEmpty()) builder.setMimeType(type);
        return builder.build();
    }

    private AudioAttributes mediaAudioAttributes() {
        return new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build();
    }

    private Session require(JSONObject args) {
        String id = sessionId(args, "sessionId");
        Session session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("unknown_video_session");
        return session;
    }

    private static String sessionId(JSONObject args, String key) {
        String id = args.optString(key, "");
        if (!id.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("invalid_session_id");
        return id;
    }

    private String source(JSONObject args, String key) throws Exception {
        String value = args.optString(key, "");
        if (value.isEmpty()) throw new IllegalArgumentException("missing_url");
        return sourceValue(value);
    }

    private String sourceValue(String value) throws Exception {
        if(mediaPrefixCache!=null&&mediaPrefixCache.owns(value))return value;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            Uri uri = Uri.parse(value);
            if (uri.getHost() == null) throw new IllegalArgumentException("invalid_url");
            return value;
        }
        File file = new File(mediaRoot, value).getCanonicalFile();
        if (value.startsWith("/") || !file.getPath().startsWith(mediaRoot.getPath() + File.separator) || !file.isFile()) throw new SecurityException("invalid_media_path");
        return file.toURI().toString();
    }

    private static Map<String, String> headers(JSONObject object) {
        Map<String, String> values = new HashMap<>();
        if (object == null) return values;
        if (object.length() > 32) throw new IllegalArgumentException("too_many_headers");
        int total = 0;
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            String key = iterator.next(), value = object.optString(key, null);
            if (value == null || key.length() > 128 || value.length() > 65536 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("invalid_headers");
            total += key.length() + value.length();
            if (total > 65536) throw new IllegalArgumentException("headers_too_large");
            values.put(key, value);
        }
        return values;
    }

    private static float finite(JSONObject args, String key, float fallback, float low, float high) {
        if (!args.has(key)) return fallback;
        Object raw = args.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_" + key);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < low || value > high) throw new IllegalArgumentException("invalid_" + key);
        return (float) value;
    }

    private static String errorCode(Exception error) {
        if (error instanceof SecurityException) return "permission_denied";
        if (error instanceof IllegalArgumentException) return "invalid_argument";
        if ("video_session_limit".equals(error.getMessage()) || "video_decoder_limit".equals(error.getMessage())) return "resource_exhausted";
        if ("video_not_prewarmed".equals(error.getMessage())) return "not_ready";
        return "video_session_error";
    }

    private int podIndex(ViewGroup host) {
        for (int index = 0; index < host.getChildCount(); index++) if (host.getChildAt(index) instanceof PodRuntimeView) return index;
        return host.getChildCount();
    }

    private final class Session implements AutoCloseable {
        final String id;
        TextureView videoView;
        View background;
        ExoPlayer player;
        Bounds bounds;
        Callback prepareCallback;
        Runnable prewarmTimeout;
        PodVideoDecoderCapabilities.Profile profile;
        String state = "idle";
        String errorCode;
        String scaleMode = "standard";
        float rotationDeg, panX, panY, volume = 1f;
        boolean decoderLease, prewarming, autoplay, muted, presented, hasRenderedFirstFrame, disposed;
        long generation, completedLoops;
        JSONObject frameSource;

        Session(String id) { this.id = id; attachViews(parent); }

        void attachViews(ViewGroup host) {
            if (host == null || disposed) return;
            if (background == null) {
                background = new View(context);
                background.setBackgroundColor(android.graphics.Color.BLACK);
                background.setVisibility(View.GONE);
            }
            if (videoView == null) {
                videoView = new TextureView(context) {
                    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { return false; }
                };
                videoView.setClickable(false); videoView.setFocusable(false); videoView.setVisibility(View.GONE);
            }
            if (background.getParent() != host) {
                if (background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
                if (videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
                int index = podIndex(host);
                host.addView(background, index, new FrameLayout.LayoutParams(1, 1));
                host.addView(videoView, index + 1, new FrameLayout.LayoutParams(1, 1));
            }
        }

        boolean matches(ExoPlayer candidate, long expectedGeneration) { return !disposed && player == candidate && generation == expectedGeneration; }
        void requirePlayer() { if (player == null) throw new IllegalStateException("video_not_ready"); }
        Callback takePrepareCallback() { Callback callback = prepareCallback; prepareCallback = null; return callback; }
        void completePrepare() {
            cancelPrewarmTimeout();
            Callback callback = takePrepareCallback();
            if (callback == null) return;
            try { callback.complete(snapshot()); }
            catch (Exception error) { callback.fail("video_session_error", error.getMessage()); }
        }

        void resetPlayer() {
            cancelPrewarmTimeout();
            generation += 1;
            ExoPlayer previous = player; player = null;
            if (previous != null) {
                try { previous.clearVideoTextureView(videoView); } catch (RuntimeException ignored) { }
                try { previous.stop(); } catch (RuntimeException ignored) { }
                previous.release();
            }
            if (decoderLease) { decoderLease = false; decoderBudget.release(); }
            hasRenderedFirstFrame = false;
            if (videoView != null) videoView.setKeepScreenOn(false);
        }

        void armPrewarmTimeout(ExoPlayer expected, long expectedGeneration) {
            cancelPrewarmTimeout();
            prewarmTimeout = () -> {
                prewarmTimeout = null;
                if (!matches(expected, expectedGeneration) || !prewarming || prepareCallback == null) return;
                failPrepare(this, expected, expectedGeneration, "timeout", "video_prewarm_timeout", true);
            };
            main.postDelayed(prewarmTimeout, PREWARM_TIMEOUT_MS);
        }

        void cancelPrewarmTimeout() {
            if (prewarmTimeout == null) return;
            main.removeCallbacks(prewarmTimeout); prewarmTimeout = null;
        }

        void applyBounds(Bounds value) {
            attachViews(parent);
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(value.widthPx, value.heightPx);
            videoParams.leftMargin = value.xPx; videoParams.topMargin = value.yPx;
            videoView.setLayoutParams(videoParams);
            FrameLayout.LayoutParams backgroundParams = new FrameLayout.LayoutParams(value.widthPx, value.heightPx);
            backgroundParams.leftMargin = value.xPx; backgroundParams.topMargin = value.yPx;
            background.setLayoutParams(backgroundParams);
            if (value.clipRadiusPx > 0) {
                final Bounds outline = value;
                ViewOutlineProvider provider = new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline result) {
                        result.setRoundRect(
                                Math.round(outline.clipCxPx - outline.clipRadiusPx),
                                Math.round(outline.clipCyPx - outline.clipRadiusPx),
                                Math.round(outline.clipCxPx + outline.clipRadiusPx),
                                Math.round(outline.clipCyPx + outline.clipRadiusPx),
                                outline.clipRadiusPx);
                    }
                };
                videoView.setOutlineProvider(provider); background.setOutlineProvider(provider);
                videoView.setClipToOutline(true); background.setClipToOutline(true);
            } else {
                videoView.setClipToOutline(false); background.setClipToOutline(false);
            }
            setTranslation(value.translateXPx, value.translateYPx);
            fitVideo();
        }

        void setTranslation(float x, float y) {
            videoView.setTranslationX(x); videoView.setTranslationY(y);
            background.setTranslationX(x); background.setTranslationY(y);
        }

        void showPresented() {
            if (bounds == null) return;
            setTranslation(bounds.translateXPx, bounds.translateYPx);
            videoView.setAlpha(1f);
            videoView.setVisibility(bounds.visible ? View.VISIBLE : View.GONE);
            background.setVisibility(bounds.visible ? View.VISIBLE : View.GONE);
            videoView.setKeepScreenOn(bounds.visible && player != null && player.getPlayWhenReady());
        }

        void hideOffscreen() {
            if (videoView == null || background == null) return;
            int base = Math.max(parent == null ? 0 : parent.getHeight(), bounds == null ? 0 : bounds.heightPx);
            float offscreen = Math.max(base, Math.round(1024 * density)) * 2f;
            videoView.setAlpha(PREWARM_ALPHA);
            videoView.setVisibility(player == null ? View.GONE : View.VISIBLE);
            background.setVisibility(View.GONE);
            setTranslation(0, offscreen);
            videoView.setKeepScreenOn(false);
        }

        void fitVideo() {
            if (player == null || videoView == null || bounds == null) return;
            androidx.media3.common.VideoSize size = player.getVideoSize();
            if (size.width <= 0 || size.height <= 0 || bounds.widthPx <= 0 || bounds.heightPx <= 0) return;
            float width = size.width * size.pixelWidthHeightRatio, height = size.height;
            videoView.setTransform(PodVideoServices.videoTransform(bounds.widthPx, bounds.heightPx, width, height, rotationDeg, scaleMode, Math.round(panX * density), Math.round(panY * density)));
        }

        JSONObject snapshot() throws Exception {
            return new JSONObject()
                    .put("ok", true).put("sessionId", id).put("state", state).put("errorCode", "error".equals(state) ? errorCode : null)
                    .put("positionMs", player == null ? 0 : player.getCurrentPosition())
                    .put("durationMs", player == null ? 0 : Math.max(0, player.getDuration()))
                    .put("width", player == null ? 0 : player.getVideoSize().width)
                    .put("height", player == null ? 0 : player.getVideoSize().height)
                    .put("muted", prewarming || volume == 0f)
                    .put("volume", volume)
                    .put("prewarming", prewarming)
                    .put("presented", presented)
                    .put("hasRenderedFirstFrame", hasRenderedFirstFrame)
                    .put("sourceGeneration",generation).put("completedLoops", completedLoops).put("loop", player!=null && player.getRepeatMode()==Player.REPEAT_MODE_ONE)
                    .put("targetBufferBytes", TARGET_BUFFER_BYTES);
        }

        @Override public void close() {
            if (disposed) return;
            disposed = true;
            Callback pending = takePrepareCallback();
            resetPlayer();
            if (pending != null) pending.fail("cancelled", "Video session released");
            if (videoView != null && videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
            if (background != null && background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
            videoView = null; background = null; bounds = null; profile = null;
        }
    }

    private static final class Bounds {
        final int xPx, yPx, widthPx, heightPx;
        final float translateXPx, translateYPx, clipCxPx, clipCyPx, clipRadiusPx;
        final boolean visible;

        private Bounds(int xPx, int yPx, int widthPx, int heightPx, float translateXPx, float translateYPx,
                       float clipCxPx, float clipCyPx, float clipRadiusPx, boolean visible) {
            this.xPx = xPx; this.yPx = yPx; this.widthPx = widthPx; this.heightPx = heightPx;
            this.translateXPx = translateXPx; this.translateYPx = translateYPx;
            this.clipCxPx = clipCxPx; this.clipCyPx = clipCyPx; this.clipRadiusPx = clipRadiusPx;
            this.visible = visible;
        }

        static Bounds read(JSONObject object, float density, boolean required) {
            if (object == null) {
                if (required) throw new IllegalArgumentException("missing_bounds");
                return null;
            }
            float x = bounded(object, "x", 0, -4096, 4096), y = bounded(object, "y", 0, -4096, 4096);
            float width = bounded(object, "width", -1, 1, 1024), height = bounded(object, "height", -1, 1, 1024);
            float tx = bounded(object, "translateX", 0, -4096, 4096), ty = bounded(object, "translateY", 0, -4096, 4096);
            JSONObject clip = object.optJSONObject("roundClip");
            float radius = clip == null ? 0 : bounded(clip, "radius", 0, 0, 1024);
            float cx = clip == null ? width / 2f : bounded(clip, "cx", width / 2f, -1024, 2048);
            float cy = clip == null ? height / 2f : bounded(clip, "cy", height / 2f, -1024, 2048);
            return new Bounds(Math.round(x * density), Math.round(y * density), Math.round(width * density), Math.round(height * density),
                    tx * density, ty * density, cx * density, cy * density, radius * density, object.optBoolean("visible", true));
        }

        private static float bounded(JSONObject object, String key, float fallback, float low, float high) {
            if (!object.has(key)) return fallback;
            Object raw = object.opt(key);
            if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_bounds");
            double value = ((Number) raw).doubleValue();
            if (!Double.isFinite(value) || value < low || value > high) throw new IllegalArgumentException("invalid_bounds");
            return (float) value;
        }
    }

    @Override public void close() {
        closed = true;
        main.post(() -> {
            for (Session session : sessions.values()) session.close();
            sessions.clear(); parent = null;
        });
    }
}
