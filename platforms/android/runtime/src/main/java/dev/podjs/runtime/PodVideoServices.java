package dev.podjs.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Build;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.AudioManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** One bounded Media3 video session. All player and view operations run on main. */
public final class PodVideoServices implements AutoCloseable {
    public interface Callback extends VideoFrameObserver { void complete(JSONObject value); void fail(String code, String message); }
    private void mediaGate(boolean active) {
        boolean before=HostWorkPriority.DEVICE.mediaActive();
        HostWorkPriority.DEVICE.foregroundMedia(this,active);
        if(mediaPrefixCache!=null)mediaPrefixCache.foregroundBusy(this,active&&(player==null||!hasRenderedFirstFrame||player.getPlaybackState()==Player.STATE_BUFFERING));
        boolean after=HostWorkPriority.DEVICE.mediaActive();
        if(before!=after)try{PodBenchmark.begin("background_gate",new JSONObject().put("state",after?"paused":"running").put("reason",after?"foreground_media":"media_stopped"));}catch(Exception ignored){}
    }
    private final Context context;
    private Runnable progressObserver=()->{};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File mediaRoot;
    private final PodMediaPrefixCache mediaPrefixCache;
    private final PodVideoDecoderBudget decoderBudget;
    private boolean decoderLease;
    private ExoPlayer player;
    // Borrowed from the playback service; background playback owns its lifetime.
    private PodMediaAudioPolicy audioPolicy;
    private PodVideoAudioStop audioStop;
    private PodBenchmark.VideoAction benchmarkAction;
    private boolean benchmarkFramePending;
    private TextureView videoView;
    private View videoBackground;
    private boolean opaqueBackdrop;
    private ViewGroup parent;
    private ImageView backdrop;
    private Bitmap backdropBitmap;
    private float rotationDeg, scaleRotationDeg;
    private final PodVideoTransformCache geometry=new PodVideoTransformCache();
    private String scaleMode = "standard";
    private float panX, panY;
    private Callback prepareCallback;
    private String state = "idle";
    private String playbackErrorCode;
    private boolean hasRenderedFirstFrame;
    private long completedLoops;
    private int observedTextureFrames;
    private PodVideoFrameStats frameStats;
    private PodVideoAnalytics analytics;
    private int statsSequence;
    private boolean statsWindowLogged;
    private boolean boundsVisible=true;
    private long prepareStartedMs;
    private boolean firstRevealLogged;
    private int prepareSequence;
    private JSONObject frameSource;
    private boolean closed;
    private boolean autoplay;
    private boolean backgroundPlayback, lifecyclePaused;
    private ServiceConnection connection;
    private Player.Listener playerListener;
    private float density;

    public PodVideoServices(Context context) {
        this(context, new PodVideoDecoderBudget(PodVideoSessionServices.MAX_SESSIONS));
    }

    PodVideoServices(Context context, PodVideoDecoderBudget decoderBudget) {
        this(context,decoderBudget,null);
    }
    PodVideoServices(Context context, PodVideoDecoderBudget decoderBudget, PodMediaPrefixCache cache) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        this.decoderBudget = decoderBudget;
        this.mediaPrefixCache=cache;
        try { mediaRoot = PodTakeover.filesRoot(this.context).getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
        density = this.context.getResources().getDisplayMetrics().density;
    }

    /** Installs a non-interactive video texture below the transparent PodJS controls. */
    public void attachToParent(ViewGroup host) {
        main.post(() -> {
            if (closed || host == null) return;
            parent = host;
            if (videoView == null) {
                videoView = new PodObservedTextureView(context, texture -> {
                    if(benchmarkFramePending&&benchmarkAction!=null&&hasRenderedFirstFrame&&boundsVisible&&videoView.getAlpha()>0){
                        PodBenchmark.VideoAction action=benchmarkAction;final int own=prepareSequence;benchmarkFramePending=false;
                        PodBenchmark.watchVideo(videoView,action,()->prepareSequence==own&&boundsVisible&&hasRenderedFirstFrame&&videoView.getTranslationY()==0);
                    }
                    if(mediaPrefixCache!=null&&mediaPrefixCache.startupPreview!=null&&frameSource!=null){int own=prepareSequence;main.post(()->mediaPrefixCache.startupPreview.handoff(frameSource.optString("url"),videoView,()->prepareSequence==own&&hasRenderedFirstFrame&&boundsVisible));}
                    if(frameStats!=null)frameStats.updated(android.os.SystemClock.elapsedRealtimeNanos());
                    if(!hasRenderedFirstFrame||observedTextureFrames>=8)return;
                    observedTextureFrames++;
                    android.util.Log.i("PodVideoTiming","textureUpdated seq="+prepareSequence+" count="+observedTextureFrames+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs)+" producerNs="+texture.getTimestamp()+" alpha="+videoView.getAlpha()+" playing="+(player!=null&&player.isPlaying()));
                }) {
                    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { return false; }
                };
                videoView.setClickable(false); videoView.setFocusable(false); videoView.setVisibility(View.GONE);
                int podIndex=host.getChildCount(); for(int i=0;i<host.getChildCount();i++) if(host.getChildAt(i) instanceof PodRuntimeView){podIndex=i;break;}
                videoBackground = new View(context); videoBackground.setBackgroundColor(android.graphics.Color.BLACK); videoBackground.setVisibility(View.GONE);
                host.addView(videoBackground, podIndex, new FrameLayout.LayoutParams(1, 1));
                host.addView(videoView, podIndex + 1, new FrameLayout.LayoutParams(1, 1));
            } else if (videoView.getParent() != host) {
                if (videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
                if (videoBackground.getParent() instanceof ViewGroup) ((ViewGroup) videoBackground.getParent()).removeView(videoBackground);
                int podIndex=host.getChildCount(); for(int i=0;i<host.getChildCount();i++) if(host.getChildAt(i) instanceof PodRuntimeView){podIndex=i;break;}
                host.addView(videoBackground,podIndex); host.addView(videoView,podIndex+1);
            }
        });
    }

    public void dispatch(String method, JSONObject args, Callback callback) {
        ForegroundMediaCommand.dispatch(main, method, () -> {
            if (closed) { callback.fail("service_closed", "Video service is closed"); return; }
            try {
                switch (method) {
                    case "video.prepare": prepare(args, callback); break;
                    case "video.play": play(callback); break;
                    case "video.retire": retire(args,callback); break;
                    case "video.pause": pause(args,callback); break;
                    case "video.seek": seek(args, callback); break;
                    case "video.stop": stop(callback); break;
                    case "video.setBounds": bounds(args, callback); break;
                    case "video.frame.capture": captureFrame(args,callback); break;
                    case "video.frame.restore": restoreFrame(args,callback); break;
                    case "video.frame.clear": PodVideoFrameCache.IO.execute(()->{try{JSONObject result=new PodVideoFrameCache(mediaRoot).clear();main.post(()->callback.complete(result));}catch(Exception error){main.post(()->callback.fail("frame_unavailable","Frame cleanup unavailable"));}}); break;
                    case "video.captureBackdrop": captureBackdrop(args, callback); break;
                    case "video.setTransform": transform(args, callback); break;
                    case "video.setLoop": setLoop(args, callback); break;
                    case "video.setSpeed": setSpeed(args, callback); break;
                    case "video.setVolume": setVolume(args, callback); break;
                    case "video.state": callback.complete(state()); break;
                    default: callback.fail("unsupported_method", "Unsupported video method: " + method);
                }
            } catch (Exception e) { PodVideoFailure.record("legacy",PodVideoFailure.stage(method),errorCode(e),e,args,decoderBudget.inUse(),decoderBudget.limit(),videoView==null?"missing":videoView.isAttachedToWindow()?"attached":"detached","unknown"); if("video.prepare".equals(method)){prepareCallback=null;releasePlayer();state="error";} callback.fail(errorCode(e), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
        });
    }

    public void cancel(String method) {
        if (!"video.prepare".equals(method)) return;
        main.post(() -> { if (prepareCallback != null) { Callback c=prepareCallback; prepareCallback=null; c.fail("cancelled", "Video preparation cancelled"); releasePlayer(); state="stopped"; } });
    }
    public void pauseForLifecycle() { mediaGate(false); main.post(() -> { if (!backgroundPlayback) { lifecyclePaused=true; if(player!=null)player.pause(); state="paused"; } }); }

    private void prepare(JSONObject a, Callback cb) throws Exception {
        PodBenchmark.startup(a.optLong("startupSeq",0),"prepare_start");
        benchmarkAction=PodBenchmark.videoAction(a);benchmarkFramePending=benchmarkAction!=null;
        firstRevealLogged=false;prepareSequence++;android.util.Log.i("PodVideoTiming","prepareBegin seq="+prepareSequence);hasRenderedFirstFrame=false;observedTextureFrames=0; prepareStartedMs=android.os.SystemClock.elapsedRealtime(); playbackErrorCode=null;
        if(videoView==null || parent==null) throw new IllegalStateException("video_surface_unavailable");
        String videoUrl = a.optString("videoUrl", ""); String audioUrl = a.optString("audioUrl", "");
        if(videoUrl.isEmpty()&&!audioUrl.isEmpty())videoUrl=a.optString("url", "");
        String url = videoUrl.isEmpty() || audioUrl.isEmpty() ? source(a, "url") : sourceValue(videoUrl);
        if (!audioUrl.isEmpty()) audioUrl = sourceValue(audioUrl);
        if (!videoUrl.isEmpty()) videoUrl = sourceValue(videoUrl);
        if (prepareCallback != null) { prepareCallback.fail("cancelled", "Video preparation replaced"); prepareCallback=null; }
        releasePlayer();
        if(!decoderBudget.acquire())throw new IllegalStateException("video_decoder_limit");
        decoderLease=true;
        applyBounds(a.optJSONObject("bounds")); autoplay=a.optBoolean("autoplay", true); mediaGate(true); backgroundPlayback=a.optBoolean("backgroundPlayback",false); lifecyclePaused=false; density=context.getResources().getDisplayMetrics().density;
        Map<String,String> headers = headers(a.optJSONObject("headers"));
        analytics=new PodVideoAnalytics();analytics.startupSeq=a.optLong("startupSeq",0);
        androidx.media3.datasource.DataSource.Factory http = PodVideoHttpDataSources.create(headers,null);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(context, http);
        androidx.media3.datasource.DataSource.Factory sources=mediaPrefixCache==null?data:mediaPrefixCache.factory(data);
        PodVideoAnalytics observer=analytics;
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(()->{androidx.media3.datasource.DataSource source=sources.createDataSource();source.addTransferListener(observer);return source;});
        MediaSource source;
        if (!videoUrl.isEmpty() && !audioUrl.isEmpty()) source = new MergingMediaSource(factory.createMediaSource(item(videoUrl, a.optString("type", ""))), factory.createMediaSource(item(audioUrl, "")));
        else source = factory.createMediaSource(item(url, a.optString("type", "")));
        prepareCallback=cb; state="preparing";
        final MediaSource preparedSource=source;
        if(backgroundPlayback && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED) throw new SecurityException("notification_permission_denied");
        final Intent serviceIntent=new Intent(context,PodVideoPlaybackService.class);
        final ServiceConnection pendingConnection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){
                if(connection!=this||closed)return;
                try {
                    PodVideoPlaybackService service=((PodVideoPlaybackService.LocalBinder)binder).service();
                    player=service.player();
                    audioPolicy=service.audioPolicy();audioStop=service.audioStop();
                    configurePlayer(a,preparedSource);
                } catch(Exception error){Callback pending=prepareCallback;prepareCallback=null;releasePlayer();state="error";if(pending!=null)pending.fail("prepare_failed",error.getMessage());}
            }
            public void onServiceDisconnected(ComponentName name){if(connection!=this)return;Callback pending=prepareCallback;prepareCallback=null;player=null;releasePlayer();state="error";if(pending!=null)pending.fail("service_disconnected","Video service disconnected");}
            public void onNullBinding(ComponentName name){onServiceDisconnected(name);}
        };
        connection=pendingConnection;
        if(backgroundPlayback) context.startForegroundService(serviceIntent);
        if(!context.bindService(new Intent(serviceIntent).setAction("dev.podjs.runtime.BIND_VIDEO"),pendingConnection,Context.BIND_AUTO_CREATE)) throw new IllegalStateException("video_service_unavailable");
    }
    private void configurePlayer(JSONObject a,MediaSource source) throws Exception {
        if(player==null)throw new IllegalStateException("video_service_unavailable");
        android.util.Log.i("PodVideoTiming","playerAttached seq="+prepareSequence+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs));
        frameSource=new JSONObject(a.toString());
        final ExoPlayer session = player;
        final Callback frameCallback=prepareCallback;
        completedLoops = 0;
        frameStats=new PodVideoFrameStats();statsSequence=prepareSequence;statsWindowLogged=false;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setRepeatMode(a.optBoolean("loop", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        audioPolicy.respectSilentMode(a.optBoolean("respectSilentMode",false));
        audioPolicy.setVolume(1f, false);
        audioPolicy.observeVolume(()->{if(player==session&&frameCallback!=null)try{frameCallback.volumeChanged(state());}catch(Exception ignored){}});
        playerListener=new Player.Listener() {
            { progressObserver=this::changed; }
            private boolean pendingProgress=false;
            private void changed(){
                if(player!=session)return;
                try{if(frameCallback!=null)frameCallback.stateChanged(state());}catch(Exception ignored){}
                if(!pendingProgress&&(session.isPlaying()||session.getPlaybackState()==Player.STATE_BUFFERING)&&boundsVisible){pendingProgress=true;main.postDelayed(()->{pendingProgress=false;if(player==session&&boundsVisible&&(session.isPlaying()||session.getPlaybackState()==Player.STATE_BUFFERING))changed();},400);}
            }
            @Override public void onEvents(Player ignored,Player.Events events){changed();}

            @Override public void onMediaItemTransition(MediaItem item, int reason) { if(player==session && reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)completedLoops++; }
            @Override public void onRenderedFirstFrame() { if(player==session){hasRenderedFirstFrame=true;if(mediaPrefixCache!=null)mediaPrefixCache.foregroundBusy(PodVideoServices.this,false);if(mediaPrefixCache!=null)mediaPrefixCache.logPlaybackStats();try{if(frameCallback!=null)frameCallback.firstFrame(state());}catch(Exception ignored){}frameStats.playing(player.isPlaying(),android.os.SystemClock.elapsedRealtimeNanos());final int observedSequence=statsSequence;main.postDelayed(()->{if(player==session&&statsSequence==observedSequence){logFrameStats("window");statsWindowLogged=true;}},8000);androidx.media3.common.Format decoded=player.getVideoFormat();if(decoded!=null)android.util.Log.i("PodVideoTiming","decoded mime="+("video/avc".equals(decoded.sampleMimeType)?"avc":"video/hevc".equals(decoded.sampleMimeType)?"hevc":"other")+" width="+decoded.width+" height="+decoded.height);if(boundsVisible&&!firstRevealLogged){firstRevealLogged=true;android.util.Log.i("PodVideoTiming","surfaceReveal seq="+prepareSequence+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs));} if(videoView!=null)videoView.setAlpha(boundsVisible?1f:0f); if(videoBackground!=null)videoBackground.setAlpha(boundsVisible?1f:0f); android.util.Log.i("PodVideoTiming","firstFrame prepareMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs));} }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size){if(player==session)fitVideo();}
            @Override public void onPlaybackStateChanged(int s) { if(player!=session)return; if(mediaPrefixCache!=null)mediaPrefixCache.foregroundBusy(PodVideoServices.this,s==Player.STATE_BUFFERING||!hasRenderedFirstFrame); if(frameStats!=null&&hasRenderedFirstFrame)frameStats.buffering(s==Player.STATE_BUFFERING,android.os.SystemClock.elapsedRealtimeNanos()); if (s==Player.STATE_BUFFERING) { if (player.getPlayWhenReady()) state="buffering"; } else if(s==Player.STATE_READY && player!=null && player.isPlaying()) state="playing"; else if(s==Player.STATE_ENDED) { state="ended"; mediaGate(false); if(videoView!=null)videoView.setKeepScreenOn(false); } else if(s==Player.STATE_READY && !"paused".equals(state)) state="ready"; if (s==Player.STATE_READY && prepareCallback != null) { Callback c=prepareCallback; prepareCallback=null; try { c.complete(state()); } catch (Exception e) { c.fail("video_error", e.getMessage()); } } }
            @Override public void onIsPlayingChanged(boolean playing) { if(player!=session)return; if(frameStats!=null)frameStats.playing(playing&&hasRenderedFirstFrame,android.os.SystemClock.elapsedRealtimeNanos()); if (playing) state="playing"; else if (state.equals("playing")) state="paused"; }
            @Override public void onPlayerError(PlaybackException error) { if(player!=session)return; PodVideoFailure.record("legacy",prepareCallback==null?"playback":"prepare",PodVideoAnalytics.errorCode(error),error,a,decoderBudget.inUse(),decoderBudget.limit(),videoView==null?"missing":videoView.isAttachedToWindow()?"attached":"detached",analytics==null?"unknown":analytics.contentKind()); state="error"; playbackErrorCode=PodVideoAnalytics.errorCode(error); try{if(frameCallback!=null)frameCallback.stateChanged(state());}catch(Exception ignored){} Callback c=prepareCallback; prepareCallback=null; releasePlayer(); if(c!=null)c.fail(playbackErrorCode,"Video playback failed"); if(videoView!=null)videoView.setVisibility(View.GONE); }
        };
        player.addAnalyticsListener(analytics);player.addAnalyticsListener(analytics.playbackStats);
        player.addListener(playerListener);
        state=lifecyclePaused?"paused":"preparing"; if(videoView!=null)videoView.setKeepScreenOn(true); player.setMediaSource(source); player.setVideoTextureView(videoView); android.util.Log.i("PodVideoTiming","prepareCall seq="+prepareSequence+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs));player.prepare();
        if (autoplay && !lifecyclePaused) player.play(); else player.pause();
    }
    private MediaItem item(String url, String type) { MediaItem.Builder b=new MediaItem.Builder().setUri(Uri.parse(url)); if(!type.isEmpty()) b.setMimeType(type); return b.build(); }
    private void play(Callback c) throws Exception { require();if(audioStop!=null)audioStop.cancel(); mediaGate(true);android.util.Log.i("PodVideoTiming","playCall seq="+prepareSequence+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs)); lifecyclePaused=false;audioPolicy.setHeldMuted(false);if(videoView!=null)videoView.setKeepScreenOn(true);player.play(); c.complete(state()); }
    private void retire(JSONObject args,Callback callback) throws Exception {
        PodBenchmark.cancelVideo(videoView);
        mediaGate(args.optBoolean("handoff",false)&&!lifecyclePaused);
        if(prepareCallback != null) { Callback pending=prepareCallback;prepareCallback=null;detachPlayer(false);pending.fail("cancelled","Video retired"); }
        if(player != null) { if(audioPolicy!=null)audioPolicy.setHeldMuted(true);else player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build()); player.pause(); }
        state="paused"; boundsVisible=false;
        if(videoView != null) { videoView.setAlpha(0f); videoView.setKeepScreenOn(false); }
        if(videoBackground != null) videoBackground.setAlpha(0f);
        mediaGate(args.optBoolean("handoff",false)&&!lifecyclePaused);
        callback.complete(state());
    }
    private void pause(JSONObject args,Callback c) throws Exception { mediaGate(args.optBoolean("handoff",false)&&!lifecyclePaused); if(player==null){c.complete(state());return;} if(audioStop!=null)audioStop.arm(args,prepareSequence);player.pause();PodBenchmark.playerPaused(args,player.getPlayWhenReady(),prepareSequence); if(videoView!=null)videoView.setKeepScreenOn(false); state="paused"; c.complete(state()); }
    private void seek(JSONObject a, Callback c) throws Exception { require(); long p=a.optLong("positionMs", -1); if(p<0) throw new IllegalArgumentException("invalid_position"); player.seekTo(p); c.complete(state()); }
    private void stop(Callback c) throws Exception { releasePlayer(); clearBackdrop(); state="stopped"; if(videoView!=null){videoView.setVisibility(View.GONE);videoView.setKeepScreenOn(false);} c.complete(state()); }
    private void bounds(JSONObject b, Callback c) throws Exception { applyBounds(b);progressObserver.run();PodBenchmark.VideoAction action=PodBenchmark.videoAction(b);if(action!=null&&hasRenderedFirstFrame&&videoView!=null&&videoView.getTranslationY()==0){final int own=prepareSequence;PodBenchmark.present(videoView,action.event("snap_back"),()->prepareSequence==own&&boundsVisible&&hasRenderedFirstFrame&&videoView.getTranslationY()==0);}c.complete(state()); }
    private void captureFrame(JSONObject args,Callback callback)throws Exception {
        String key=PodVideoFrameCache.key(args),binding=PodVideoFrameCache.binding(args.getJSONObject("source"),mediaPrefixCache);
        if(!binding.equals(PodVideoFrameCache.binding(frameSource,mediaPrefixCache)))throw new IllegalStateException("frame_source_changed");
        Bitmap frame=PodVideoFrameCache.capture(videoView,hasRenderedFirstFrame,prepareSequence,args);long own=prepareSequence,position=player==null?0:player.getCurrentPosition();
        try{PodVideoFrameCache.IO.execute(()->{try{JSONObject result=new PodVideoFrameCache(mediaRoot).save(key,binding,own,position,frame);main.post(()->callback.complete(result));}catch(Exception error){main.post(()->callback.fail("frame_unavailable","Frame snapshot unavailable"));}finally{frame.recycle();}});}catch(java.util.concurrent.RejectedExecutionException busy){frame.recycle();throw new IllegalStateException("frame_queue_busy");}
    }
    private void restoreFrame(JSONObject args,Callback callback)throws Exception {
        String key=PodVideoFrameCache.key(args),binding=PodVideoFrameCache.binding(args.getJSONObject("source"),mediaPrefixCache);
        PodVideoFrameCache.IO.execute(()->{try{JSONObject result=new PodVideoFrameCache(mediaRoot).restore(key,binding);main.post(()->callback.complete(result));}catch(Exception error){main.post(()->callback.fail("frame_unavailable","Frame snapshot unavailable"));}});
    }
    private void captureBackdrop(JSONObject args, Callback c) throws Exception {
        if(parent==null)throw new IllegalStateException("video_surface_unavailable");
        boolean opaque=args.optBoolean("opaque",false);
        Bitmap shot;
        if(opaque) {
            // Native textures are BELOW PodRuntimeView. A black JS rectangle
            // would cover the video. Keep this stationary, opaque native floor
            // below every slot, including during unready/cancelled handoffs.
            shot=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
            shot.eraseColor(android.graphics.Color.BLACK);
        } else {
            PodRuntimeView pod=null;
            for(int i=0;i<parent.getChildCount();i++)if(parent.getChildAt(i) instanceof PodRuntimeView){pod=(PodRuntimeView)parent.getChildAt(i);break;}
            if(pod==null)throw new IllegalStateException("pod_surface_unavailable");
            shot=pod.getBitmap();if(shot==null)throw new IllegalStateException("backdrop_unavailable");
        }
        clearBackdrop();opaqueBackdrop=opaque;backdropBitmap=shot;
        if(backdrop==null){backdrop=new ImageView(context);backdrop.setScaleType(ImageView.ScaleType.FIT_XY);parent.addView(backdrop,0,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));}
        backdrop.setImageBitmap(shot);backdrop.setAlpha(1f);backdrop.setVisibility(View.VISIBLE);
        c.complete(new JSONObject().put("ok",true));
    }
    private void clearBackdrop(){opaqueBackdrop=false;if(backdrop!=null){backdrop.setImageDrawable(null);backdrop.setVisibility(View.GONE);}if(backdropBitmap!=null&&!backdropBitmap.isRecycled())backdropBitmap.recycle();backdropBitmap=null;}
    private void transform(JSONObject a, Callback c)throws Exception { String mode=a.optString("scaleMode","standard");if(!mode.equals("standard")&&!mode.equals("expanded")&&!mode.equals("shrunk"))throw new IllegalArgumentException("invalid_scale_mode");float rot=(float)a.optDouble("rotationDeg",0);if(!Float.isFinite(rot)||Math.abs(rot)>100000)throw new IllegalArgumentException("invalid_rotation");float scaleRot=(float)a.optDouble("scaleRotationDeg",rot);if(!Float.isFinite(scaleRot)||Math.abs(scaleRot)>100000)throw new IllegalArgumentException("invalid_scale_rotation");rotationDeg=Math.round(rot*100f)/100f;scaleRotationDeg=Math.round(scaleRot*100f)/100f;scaleMode=mode;panX=(float)a.optDouble("panX",0);panY=(float)a.optDouble("panY",0);if(!Float.isFinite(panX)||!Float.isFinite(panY))throw new IllegalArgumentException("invalid_pan");fitVideo();c.complete(state()); }
    private void setLoop(JSONObject a, Callback c)throws Exception { require(); Object loop=a.opt("loop"); if(!(loop instanceof Boolean))throw new IllegalArgumentException("loop_must_be_boolean"); player.setRepeatMode((Boolean)loop?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF); c.complete(state()); }
    private void setSpeed(JSONObject a, Callback c)throws Exception { require(); player.setPlaybackSpeed(PodVideoSpeed.parse(a)); c.complete(state()); }
    private void setVolume(JSONObject a, Callback c)throws Exception {PodVideoSystemAudio.update(context,a);if(audioPolicy!=null)audioPolicy.refresh();c.complete(state());}
    private void applyBounds(JSONObject b) { if(videoView==null||b==null)return; if(backdrop!=null)backdrop.setVisibility(opaqueBackdrop||b.optDouble("translateX",0)!=0?View.VISIBLE:View.GONE); int x=dp(b.optDouble("x",0)+b.optDouble("translateX",0)),y=dp(b.optDouble("y",0)),w=dp(b.optDouble("width",0)),h=dp(b.optDouble("height",0)); final float ty=(float)b.optDouble("translateY",0)*density;
        // A vertical pager drag moves the surface every frame: translate it, and relayout only when the frame itself changes.
        FrameLayout.LayoutParams current=(FrameLayout.LayoutParams)videoView.getLayoutParams(); boolean relayout=current==null||current.leftMargin!=x||current.topMargin!=y||current.width!=Math.max(0,w)||current.height!=Math.max(0,h);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.max(0,w),Math.max(0,h));p.leftMargin=x;p.topMargin=y; if(relayout)videoView.setLayoutParams(p); videoView.setTranslationY(ty); boundsVisible=b.optBoolean("visible",true);
        // Keep the TextureView surface alive while hidden: GONE during a handoff
        // prevents a replacement decoder from producing its first frame.
        videoView.setVisibility(w>0&&h>0?View.VISIBLE:View.GONE); videoView.setAlpha(boundsVisible&&hasRenderedFirstFrame?1f:0f);
        if(boundsVisible&&hasRenderedFirstFrame&&!firstRevealLogged){firstRevealLogged=true;android.util.Log.i("PodVideoTiming","surfaceReveal seq="+prepareSequence+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-prepareStartedMs));}
        // The round clip stays fixed to the screen while the surface is translated.
        JSONObject clip=b.optJSONObject("roundClip"); if(clip!=null){final float r=(float)clip.optDouble("radius",0)*density; final float cx=(float)clip.optDouble("cx",w/(2*density))*density,cy=(float)clip.optDouble("cy",h/(2*density))*density-ty; videoView.setClipToOutline(r>0); videoView.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){o.setRoundRect((int)(cx-r),(int)(cy-r),(int)(cx+r),(int)(cy+r),r);}}); } else videoView.setClipToOutline(false);
        if(videoBackground!=null){if(relayout)videoBackground.setLayoutParams(new FrameLayout.LayoutParams(p));videoBackground.setTranslationY(ty);videoBackground.setVisibility(videoView.getVisibility());videoBackground.setAlpha(videoView.getAlpha());videoBackground.setOutlineProvider(videoView.getOutlineProvider());videoBackground.setClipToOutline(videoView.getClipToOutline());} if(relayout)fitVideo(); }
    private void fitVideo(){
        if(player==null||videoView==null||videoView.getLayoutParams()==null)return;
        androidx.media3.common.VideoSize size=player.getVideoSize();int w=videoView.getLayoutParams().width,h=videoView.getLayoutParams().height;
        if(size.width<=0||size.height<=0||w<=0||h<=0)return;
        float vw=size.width*size.pixelWidthHeightRatio, vh=size.height;
        Matrix transform=geometry.get(w,h,vw,vh,rotationDeg,scaleRotationDeg,scaleMode,dp(panX),dp(panY));
        videoView.setTransform(transform);
        // A paused single-player fallback has no decoder buffer to invalidate
        // its display list. Match retained sessions when applying a transform.
        videoView.invalidate();
    }
    static Matrix videoTransform(float w,float h,float vw,float vh,float rotation,String mode,float panX,float panY) {
        return videoTransform(w,h,vw,vh,rotation,rotation,mode,panX,panY);
    }
    static Matrix videoTransform(float w,float h,float vw,float vh,float rotation,float scaleRotation,String mode,float panX,float panY) {
        return videoTransformWithScale(w,h,vw,vh,rotation,videoScale(w,h,vw,vh,scaleRotation,mode),panX,panY);
    }
    static float videoScale(float w,float h,float vw,float vh,float scaleRotation,String mode) {
        double angle=Math.toRadians(scaleRotation),ca=Math.abs(Math.cos(angle)),sa=Math.abs(Math.sin(angle));
        float rotatedW=(float)(vw*ca+vh*sa),rotatedH=(float)(vw*sa+vh*ca);
        float factor="expanded".equals(mode)?Math.max(w/rotatedW,h/rotatedH):Math.min(w/rotatedW,h/rotatedH);
        if("shrunk".equals(mode))factor=Math.min(factor,(float)(Math.min(w,h)/Math.hypot(vw,vh)));
        return factor;
    }
    static Matrix videoTransformWithScale(float w,float h,float vw,float vh,float rotation,float factor,float panX,float panY) {
        Matrix matrix=new Matrix(); matrix.setScale(vw*factor/w,vh*factor/h,w/2,h/2);
        matrix.postRotate(rotation,w/2,h/2); matrix.postTranslate(panX,panY); return matrix;
    }
    private int dp(double v) { if(!Double.isFinite(v)) throw new IllegalArgumentException("invalid_bounds"); return Math.max(-100000,Math.min(100000,(int)Math.round(v*density))); }
    private void require(){if(player==null)throw new IllegalStateException("video_not_ready");}
    private JSONObject state() throws Exception { AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);return PodVideoSystemAudio.snapshot(context,new JSONObject().put("silenced",audioPolicy!=null&&audioPolicy.silenced()).put("appliedVolume",player==null?null:player.getVolume()).put("audioTrackSelected",player!=null&&player.getAudioFormat()!=null).put("metrics",analytics==null?null:analytics.snapshot()).put("ok",true).put("state",state).put("hasRenderedFirstFrame",hasRenderedFirstFrame).put("sourceGeneration",prepareSequence).put("completedLoops",completedLoops).put("loop",player!=null&&player.getRepeatMode()==Player.REPEAT_MODE_ONE).put("speed",player==null?1:player.getPlaybackParameters().speed).put("errorCode", "error".equals(state)?playbackErrorCode:null).put("bufferedMs",player==null?0:Math.max(0,player.getBufferedPosition()-player.getCurrentPosition())).put("networkStalled",analytics!=null&&analytics.networkStalled()).put("positionMs",player==null?0:player.getCurrentPosition()).put("durationMs",player==null?0:Math.max(0,player.getDuration())).put("width",player==null?0:player.getVideoSize().width).put("height",player==null?0:player.getVideoSize().height)); }
    private String source(JSONObject a,String key)throws Exception{String s=a.optString(key,"");if(s.isEmpty())throw new IllegalArgumentException("missing_url");return sourceValue(s);}
    private String sourceValue(String s)throws Exception{if(mediaPrefixCache!=null&&(mediaPrefixCache.owns(s)||mediaPrefixCache.ownsLocal(s)))return s;if(s.startsWith("http://")||s.startsWith("https://")){Uri u=Uri.parse(s);if(u.getHost()==null)throw new IllegalArgumentException("invalid_url");return s;}File f=new File(mediaRoot,s).getCanonicalFile();if(s.startsWith("/")||!f.getPath().startsWith(mediaRoot.getPath()+File.separator)||!f.isFile())throw new SecurityException("invalid_media_path");return f.toURI().toString();}
    private Map<String,String> headers(JSONObject o)throws Exception{Map<String,String> m=new HashMap<>();if(o==null)return m; if(o.length()>32)throw new IllegalArgumentException("too_many_headers");int total=0;java.util.Iterator<String> i=o.keys();while(i.hasNext()){String k=i.next();String v=o.optString(k,null);if(v==null||k.length()>128||v.length()>65536||k.indexOf('\r')>=0||k.indexOf('\n')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0)throw new IllegalArgumentException("invalid_headers");total+=k.length()+v.length();if(total>65536)throw new IllegalArgumentException("headers_too_large");m.put(k,v);}return m;}
    private void logFrameStats(String scope){
        if(player==null||frameStats==null)return;
        androidx.media3.exoplayer.DecoderCounters counters=player.getVideoDecoderCounters();
        if(counters!=null)counters.ensureUpdated();
        android.util.Log.i("PodVideoTiming","playbackStats seq="+statsSequence+" scope="+scope+" "+frameStats.summary(android.os.SystemClock.elapsedRealtimeNanos())
            +" decoderRendered="+(counters==null?-1:counters.renderedOutputBufferCount)+" decoderDropped="+(counters==null?-1:counters.droppedBufferCount)+" decoderSkipped="+(counters==null?-1:counters.skippedOutputBufferCount));
    }
    private void detachPlayer(boolean keepPlaying){
        progressObserver=()->{};
        PodBenchmark.cancelVideo(videoView);
        if(!statsWindowLogged)logFrameStats("end");frameStats=null;
        ExoPlayer previous=player;player=null;audioPolicy=null;audioStop=null;
        if(previous!=null){if(analytics!=null){previous.removeAnalyticsListener(analytics);previous.removeAnalyticsListener(analytics.playbackStats);}if(playerListener!=null)previous.removeListener(playerListener);previous.clearVideoSurface();if(!keepPlaying){previous.stop();previous.clearMediaItems();}}
        playerListener=null;
        ServiceConnection previousConnection=connection;connection=null;
        if(previousConnection!=null){try{context.unbindService(previousConnection);}catch(IllegalArgumentException ignored){}}
        if(!keepPlaying){context.stopService(new Intent(context,PodVideoPlaybackService.class));if(decoderLease){decoderLease=false;decoderBudget.release();}}
        if(videoView!=null)videoView.setVisibility(View.GONE);
        if(videoBackground!=null)videoBackground.setVisibility(View.GONE);
        if(prepareCallback!=null){Callback pending=prepareCallback;prepareCallback=null;pending.fail("cancelled","Video preparation cancelled");}
    }
    private void releasePlayer(){mediaGate(false);detachPlayer(false);if(videoView!=null)videoView.setKeepScreenOn(false);}
    @Override public void close(){closed=true;mediaGate(false);main.post(()->{detachPlayer(backgroundPlayback&&player!=null&&prepareCallback==null&&player.getPlayWhenReady());if(videoView!=null&&videoView.getParent() instanceof ViewGroup)((ViewGroup)videoView.getParent()).removeView(videoView);if(videoView!=null)videoView.setKeepScreenOn(false);videoView=null;if(videoBackground!=null&&videoBackground.getParent() instanceof ViewGroup)((ViewGroup)videoBackground.getParent()).removeView(videoBackground);videoBackground=null;clearBackdrop();if(backdrop!=null&&backdrop.getParent() instanceof ViewGroup)((ViewGroup)backdrop.getParent()).removeView(backdrop);backdrop=null;parent=null;state="idle";});}
    private static String errorCode(Exception e){return e instanceof SecurityException?"permission_denied":e instanceof IllegalArgumentException?"invalid_argument":"video_decoder_limit".equals(e.getMessage())?"resource_exhausted":"video_error";}
}
