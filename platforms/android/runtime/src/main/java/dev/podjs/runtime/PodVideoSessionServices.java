package dev.podjs.runtime;

import android.content.Context;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.app.ActivityManager;
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
 * Capability-bounded foreground video pool for pager-style feeds. A prewarm slot decodes
 * silently into a retained, off-screen TextureView. Promotion reveals that same
 * surface, so no decoder or SurfaceTexture is replaced at the page boundary.
 */
final class PodVideoSessionServices implements AutoCloseable, ComponentCallbacks2 {
    interface Callback extends VideoFrameObserver { void complete(JSONObject value); }

    static final int MAX_SESSIONS = 3;
    static final int TARGET_BUFFER_BYTES = 4 * 1024 * 1024;
    static int targetBufferBytes(){return PodProcessMemoryBudget.limit()>0?1024*1024:TARGET_BUFFER_BYTES;}
    static final long PREWARM_TIMEOUT_MS = 2_500L;
    static final long PREWARM_MAX_LIFETIME_MS = 10_000L;
    static final long GEOMETRY_WAIT_MAX_MS = 1_000L;
    private static final float PREWARM_ALPHA = 0.01f;
    private static final String CAPABILITY = "media.video.sessions";

    private void mediaGate(boolean active) {
        boolean before=HostWorkPriority.DEVICE.mediaActive();
        HostWorkPriority.DEVICE.foregroundMedia(this,active);
        if(mediaPrefixCache!=null){boolean busy=false,found=false;for(Session s:sessions.values())if(!s.prewarming&&s.player!=null){found=true;busy|=!s.hasRenderedFirstFrame||s.player.getPlaybackState()==Player.STATE_BUFFERING;}mediaPrefixCache.foregroundBusy(this,active&&(!found||busy));}
        boolean after=HostWorkPriority.DEVICE.mediaActive();
        if(before!=after)try{PodBenchmark.begin("background_gate",new JSONObject().put("state",after?"paused":"running").put("reason",after?"foreground_media":"media_stopped"));}catch(Exception ignored){}
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    interface PaintAwaiter { Runnable await(View view,java.util.function.Consumer<Boolean> complete); }
    private PaintAwaiter handoffPaint=(view,complete)->PodAfterPaint.await(view,main,1,(frame,at)->{},complete);
    private final File mediaRoot;
    private final PodMediaPrefixCache mediaPrefixCache;
    private final PodVideoDecoderBudget decoderBudget;
    private final PodVideoDecoderCapabilities decoderCapabilities;
    private final PodVideoMemoryBudget memoryBudget=new PodVideoMemoryBudget(MAX_SESSIONS);
    private final PodVideoKeyframes keyframes = new PodVideoKeyframes();
    private final PodVideoPrefixFrames prefixFrames;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private ViewGroup parent;
    private boolean closed;
    private long shellCreated,shellReleased;
    JSONObject shellCounters() throws Exception {
        int attached=0,available=0,idleAvailable=0,width=0,height=0,directOutputs=0;
        float formatFrameRate = androidx.media3.common.Format.NO_VALUE;
        for(Session session:sessions.values()){
            TextureView view=session.videoView;
            if (session.direct.active()) directOutputs++;
            if(view!=null&&view.isAttachedToWindow())attached++;
            if(view!=null&&view.isAvailable()){
                available++;
                if(session.player==null)idleAvailable++;
            }
            if(!session.prewarming&&session.player!=null&&session.player.isPlaying()){
                androidx.media3.common.VideoSize size=session.player.getVideoSize();
                if((long)size.width*size.height>(long)width*height){
                    width=size.width;
                    height=size.height;
                    androidx.media3.common.Format format = session.player.getVideoFormat();
                    formatFrameRate = format == null ? androidx.media3.common.Format.NO_VALUE : format.frameRate;
                }
            }
        }
        JSONObject surfaces=new JSONObject().put("attached",attached).put("available",available)
                .put("idleAvailable",idleAvailable).put("directOutputs",directOutputs).put("playingWidth",width).put("playingHeight",height);
        if (formatFrameRate > 0 && Float.isFinite(formatFrameRate)) {
            surfaces.put("playingFormatFrameRate", (double) formatFrameRate);
        }
        return new JSONObject().put("active",sessions.size()).put("created",shellCreated)
                .put("released",shellReleased).put("mediaMemory",PodMediaMemoryAccounting.stats())
                .put("surfaceViews",surfaces);
    }
    private boolean lifecyclePaused;
    private int lastMemoryLimit=MAX_SESSIONS;
    private long lastSystemHeadroom;
    private int lastTrimLevel;
    private float density;
    private PodVideoDecoderCapabilities.Profile lastProfile;
    private PodVideoDecoderCapabilities.Result lastCapability;
    private Runnable unsubscribeMemory=()->{};

    PodVideoSessionServices(Context context, PodVideoDecoderBudget decoderBudget) {
        this(context,decoderBudget,null);
    }
    PodVideoSessionServices(Context context, PodVideoDecoderBudget decoderBudget, PodMediaPrefixCache cache) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        PodBenchmark.videoReleaseSource(releaseFrame::bind);
        this.decoderBudget = decoderBudget;
        this.mediaPrefixCache=cache;
        prefixFrames=cache==null?null:new PodVideoPrefixFrames(cache,main,keyframes,this::prefixFrameReady,decoderBudget);
        decoderCapabilities = new PodVideoDecoderCapabilities(this.context);
        try { mediaRoot = PodTakeover.filesRoot(this.context).getCanonicalFile(); }
        catch (Exception error) { throw new IllegalStateException("service_root_unavailable", error); }
        density = this.context.getResources().getDisplayMetrics().density;
        this.context.registerComponentCallbacks(this);
        unsubscribeMemory=PodProcessMemoryBudget.observe(level->{if(level>0)main.post(()->{if(!closed)keyframes.trim();});});
    }

    @Override public void onConfigurationChanged(Configuration configuration) {}
    @Override public void onLowMemory() { onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE); }
    @Override public void onTrimMemory(int level) {
        main.post(()->{if(!closed){lastTrimLevel=level;memoryBudget.trim(level);enforceMemoryBudget(lastProfile);if(level!=20&&level>=5)keyframes.trim();}});
    }
    private int memoryLimit(PodVideoDecoderCapabilities.Profile requested) {
        long bytes=PodVideoMemoryBudget.decoderBytes(requested==null?0:requested.width,requested==null?0:requested.height);
        int resident=0;
        for(Session slot:sessions.values())if(slot.player!=null) {
            if(slot.decoderLease&&slot.hasRenderedFirstFrame)resident++;
            if(slot.profile!=null)bytes=Math.max(bytes,PodVideoMemoryBudget.decoderBytes(slot.profile.width,slot.profile.height));
        }
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null)manager.getMemoryInfo(info);
        long system=manager==null?0:Math.max(0,info.availMem-info.threshold-keyframes.bytes*2-(long)PodVideoKeyframes.MAX_EDGE*PodVideoKeyframes.MAX_EDGE*4);
        lastSystemHeadroom=system;
        lastMemoryLimit=Math.min(decoderBudget.limit(),memoryBudget.limit(system,info.lowMemory,bytes,decoderBudget.inUse(),Math.max(0,decoderBudget.inUse()-resident)));return lastMemoryLimit;
    }
    private int enforceMemoryBudget(PodVideoDecoderCapabilities.Profile requested) {
        int limit=memoryLimit(requested);
        evictStandbyTo(limit,null);
        return limit;
    }
    private void evictStandbyTo(int limit,String keep) {
        for(Session slot:new java.util.ArrayList<>(sessions.values())) {
            if(decoderBudget.inUse()<=limit)break;
            if(slot.prewarming&&!slot.id.equals(keep)) {
                if(prefixFrames!=null&&keyframes.get(slot.frameBinding)!=null)slot.lendDecoder();else {sessions.remove(slot.id);slot.close();}
            }
        }
    }

    void attachToParent(ViewGroup host) {
        main.post(() -> {
            if (closed || host == null) return;
            parent = host;
            density = context.getResources().getDisplayMetrics().density;
            for (Session session : sessions.values())try{session.attachViews(host);}catch(RuntimeException unavailable){
                if(!PodVideoCodecGate.isAdmissionDenied(unavailable))throw unavailable;
                // Keep authorized playback and its source while the UI waits
                // for another attach/resume after memory recovery.
                android.util.Log.i("PodMediaMemory","surface_admission_deferred");
            }
        });
    }

    void lifecycle(boolean resumed) {
        main.post(() -> {
            lifecyclePaused = !resumed;
            if(!resumed)mediaGate(false);
            if (!resumed) {
                endDrag();
                for (Session session : new java.util.ArrayList<>(sessions.values())) {
                    session.direct.discard(true);
                    if(session.prewarming){sessions.remove(session.id);session.close();continue;}
                    if(session.player!=null&&!session.mayPlay()){session.player.pause();session.state="paused";}
                    session.updateKeepScreenOn();
                }
                for(Session session:sessions.values())if(session.mayPlay()&&session.autoplay)mediaGate(true);
            }
        });
    }

    void dispatch(String method, JSONObject args, Callback callback) {
        ForegroundMediaCommand.dispatch(main, method, () -> {
            if (closed) { callback.fail("service_closed", "Video session service is closed"); return; }
            try {
                switch (method) {
                    case "video.session.captureFrame": captureFrame(args,callback); break;
                    case "video.session.capabilities": callback.complete(capabilities(args)); break;
                    case "video.session.prepare": prepare(args, callback); break;
                    case "video.session.play": play(args, callback); break;
                    case "video.session.retire": retire(args, callback); break;
                    case "video.session.pause": pause(args, callback); break;
                    case "video.session.seek": seek(args, callback); break;
                    case "video.session.stop": stop(args, callback); break;
                    case "video.session.release": release(args, callback); break;
                    case "video.session.setBounds": setBounds(args, callback); break;
                    case "video.session.setTransform": setTransform(args, callback); break;
                    case "video.session.setLoop": setLoop(args, callback); break;
                    case "video.session.setSpeed": setSpeed(args, callback); break;
                    case "video.session.setVolume": setVolume(args, callback); break;
                    case "video.session.state": {
                        Session session = require(args);
                        if (session.audioPolicy != null) session.audioPolicy.refresh();
                        callback.complete(session.snapshot());
                        break;
                    }
                    case "video.session.promote": promote(args, callback); break;
                    case "video.session.transition": transition(args, callback); break;
                    default: callback.fail("unsupported_method", "Unsupported video session method: " + method);
                }
            } catch (Exception error) {
                if(PodVideoCodecGate.isAdmissionDenied(error)){
                    callback.fail("decoder_admission_deferred",PodVideoCodecGate.isByteAdmissionDenied(error)
                        ?"video_memory_budget_deferred":"Video prewarm admission deferred");
                    return;
                }
                PodVideoFailure.record("sessions",PodVideoFailure.stage(method),errorCode(error),error,args,decoderBudget.inUse(),lastMemoryLimit,parent==null?"missing":parent.isAttachedToWindow()?"attached":"detached","unknown");
                callback.fail(errorCode(error), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    void cancel(String sessionId) {
        if (sessionId == null) return;
        main.post(() -> {
            Session session = sessions.get(sessionId);
            if (session == null || session.prepareCallback == null) return;
            if(session.analytics!=null)session.analytics.stage("cancel",session.player,session.videoView);
            Callback pending = session.takePrepareCallback();
            if(!session.prewarming)mediaGate(false);
            session.resetPlayer();
            session.state = "stopped";
            session.hideOffscreen();
            if(session.keyframeOnly){sessions.remove(session.id);session.close();}
            pending.fail("cancelled", "Video session preparation cancelled");
        });
    }

    JSONObject memoryStats()throws Exception {
        int players=0,views=0;
        for(Session session:sessions.values()){if(session.player!=null)players++;if(session.hasCachedFrame())views++;}
        return new JSONObject().put("sessions",sessions.size()).put("players",players).put("cachedViews",views)
            .put("decoderLeases",decoderBudget.inUse()).put("lastFrameBytes",0)
            .put("keyframes",keyframes.memoryStats()).put("prefixFrames",prefixFrames==null?JSONObject.NULL:prefixFrames.memoryStats());
    }
    private JSONObject capabilities(JSONObject args) throws Exception {
        PodVideoDecoderCapabilities.Profile requested = profileFromArguments(args, false);
        PodVideoDecoderCapabilities.Result capability = requested == null ? lastCapability : decoderCapabilities.inspect(requested);
        int limit = Math.min(enforceMemoryBudget(requested),capability == null ? decoderBudget.limit() : capability.limit);
        JSONObject value = new JSONObject()
                .put("capability", CAPABILITY)
                .put("maxSessions", MAX_SESSIONS).put("independentKeyframes",prefixFrames!=null)
                .put("keyframeCache", true).put("keyframeEntries",keyframes.size()).put("keyframeBytes",keyframes.bytes).put("keyframeMaxBytes",PodVideoKeyframes.MAX_BYTES).put("decoderPeak",decoderBudget.peakInUse())
                .put("decoderLimit", limit)
                .put("decoderInUse", decoderBudget.inUse()).put("physicalDecoderInUse",PodVideoCodecGate.PROCESS.live()).put("physicalDecoderPeak",PodVideoCodecGate.PROCESS.peak()).put("maxPrewarm",1)
                .put("targetBufferBytesPerSession", targetBufferBytes())
                .put("targetBufferBytesTotal", (long) targetBufferBytes() * limit)
                .put("prewarmTimeoutMs", PREWARM_MAX_LIFETIME_MS)
                .put("prewarmIdleTimeoutMs", PREWARM_TIMEOUT_MS)
                .put("prewarmMode", "hidden_first_frame")
                .put("surfacePromotion", limit >= 2)
                .put("verticalTransition", limit >= 2)
                .put("surfaceComposition", "android_texture_view")
                .put("backgroundPlayback", true);
        if (capability != null) {
            JSONObject details = capability.json();
            java.util.Iterator<String> keys = details.keys();
            while (keys.hasNext()) { String key = keys.next(); value.put(key, details.get(key)); }
        }
        return value;
    }

    private static int sessionPriority(Session slot){return slot.prewarming?(slot.keyframeOnly?PodVideoCodecGate.OTHER:PodVideoCodecGate.PREWARM):PodVideoCodecGate.CURRENT;}

    private void prefixFrameReady(String binding) {
        for(Session slot:new java.util.ArrayList<>(sessions.values()))if(binding.equals(slot.frameBinding)&&!slot.hasCachedFrame()&&!slot.hasRenderedFirstFrame){
            slot.revealKeyframe();
            if(slot.prewarming&&slot.hasCachedFrame()){slot.state="prewarmed";slot.completePrepare();}
        }
    }
    /** A pixel standby has no player and occupies no live codec slot. */
    private void preparePixels(JSONObject args,Bounds bounds,Callback callback)throws Exception {
        boolean transientFrame=args.optBoolean("keyframeOnly",false)&&!args.optBoolean("retainKeyframe",false);
        String id=sessionId(args,"sessionId");Session slot=sessions.get(id);
        if(slot==null){if(sessions.size()>=MAX_SESSIONS)throw new IllegalStateException("video_session_limit");slot=new Session(id,true,transientFrame);sessions.put(id,slot);}
        if(slot.prepareCallback!=null)slot.takePrepareCallback().fail("cancelled","Video session preparation replaced");
        slot.resetPlayer();slot.generation++;slot.bounds=bounds;slot.prewarming=true;slot.presented=false;
        slot.frameSource=new JSONObject(args.toString());slot.readTransform(args.optJSONObject("transform"));slot.frameBinding=keyframeBinding(args,bounds);
        slot.analytics=new PodVideoAnalytics();slot.analytics.sourceOrdinal=args.optLong("sourceOrdinal",0);
        slot.analytics.slotOrdinal=args.optLong("slotOrdinal",0);slot.analytics.preparation(args.optLong("prepareSeq",0),slot.generation,true);
        slot.state="preparing";slot.prepareCallback=callback;slot.keyframeOnly=transientFrame;
        slot.applyBounds(bounds);slot.revealKeyframe();
        if(slot.hasCachedFrame()){slot.state="prewarmed";slot.completePrepare();}
        else {Session own=slot;long generation=slot.generation;slot.prewarmTimeout=()->{
            if(own.disposed||own.generation!=generation||own.prepareCallback==null)return;
            Callback pending=own.takePrepareCallback();sessions.remove(own.id);own.close();pending.fail("timeout","Keyframe bytes unavailable");
        };main.postDelayed(slot.prewarmTimeout,PREWARM_MAX_LIFETIME_MS);}
    }

    private void prepare(JSONObject args, Callback callback) throws Exception {
        prepare(args,callback,false);
    }
    private void prepare(JSONObject args, Callback callback, boolean preservePixels) throws Exception {
        if(!args.optBoolean("prewarm",false))PodBenchmark.startup(PodBenchmark.videoPreparationSequence(args),"prepare_start");
        prepare(args,callback,preservePixels,android.os.SystemClock.uptimeMillis());
    }

    private void prepare(JSONObject args, Callback callback, boolean preservePixels,long admissionStarted) throws Exception {
        if (parent == null) throw new IllegalStateException("video_surface_unavailable");
        if(!args.optBoolean("prewarm",false)&&args.optBoolean("backgroundPlayback",false))PodSessionPlaybackService.checkPermission(context);
        String id = sessionId(args, "sessionId");
        Bounds bounds = Bounds.read(args.optJSONObject("bounds"), density, true);
        boolean prewarming = args.optBoolean("prewarm", false);
        boolean keyframeOnly = prewarming && args.optBoolean("keyframeOnly", false);
        // Reclaim lower-priority purposes before older warm neighbours.
        // Current playback is never an eviction candidate.
        PodVideoCodecGate.PROCESS.reclaim(sessions.get(id),prewarming&&args.optBoolean("keyframeOnly",false)?PodVideoCodecGate.OTHER:PodVideoCodecGate.PREWARM);
        for(Session other:new java.util.ArrayList<>(sessions.values()))if(!other.id.equals(id)&&other.prewarming&&other.player!=null&&(!args.optBoolean("keyframeOnly",false)||other.keyframeOnly)){other.reclaimDecoder();}
        String requestedBinding=keyframeBinding(args,bounds);
        if(keyframeOnly&&!args.optBoolean("retainKeyframe",false)&&keyframes.get(requestedBinding)!=null){
            callback.complete(new JSONObject().put("ok",true).put("sessionId",id).put("state","cached").put("keyframeAvailable",true));return;
        }
        // Only explicit frame-only work can settle on cached pixels. The next
        // playback neighbour still owns one live prewarm decoder when admitted.
        if(prefixFrames!=null&&prewarming&&args.optBoolean("keyframeOnly",false)){preparePixels(args,bounds,callback);return;}
        if(prewarming&&args.optBoolean("keyframeOnly",false)){
            for(Session foreground:sessions.values())if(!foreground.prewarming&&foreground.player!=null&&
                (!foreground.hasRenderedFirstFrame||foreground.player.getPlaybackState()==Player.STATE_BUFFERING||
                 foreground.player.getPlayWhenReady()&&foreground.player.getBufferedPosition()-foreground.player.getCurrentPosition()<1000))
                throw new IllegalStateException("video_foreground_busy");
        }
        PodVideoDecoderCapabilities.Profile profile = profileFromArguments(args, true);
        if ((profile == null || !profile.known()) && prewarming) profile = lastProfile;
        if (profile != null && profile.known()) {
            PodVideoDecoderCapabilities.Result capability = decoderCapabilities.inspect(profile);
            lastProfile = profile; lastCapability = capability; decoderBudget.restrictTo(capability.limit);
        }
        int memoryLimit=enforceMemoryBudget(profile);
        // Foreground admission evicts speculative slots before claiming a lease.
        // No codec is built while the process is above its memory allowance.
        Session existing=sessions.get(id);
        if(!prewarming)evictStandbyTo(Math.max(0,memoryLimit-(existing!=null&&existing.decoderLease?0:1)),id);
        if(!prewarming&&decoderBudget.inUse()-(existing!=null&&existing.decoderLease?1:0)>=memoryLimit&&decoderBudget.spareInUse()){
            // Keep cached pixels visible while the worker closes its codec.
            // The pending callback belongs to the session, so release/reuse
            // cancels it and cannot resurrect an obsolete foreground player.
            Session waiting=existing;
            if(waiting==null){if(sessions.size()>=MAX_SESSIONS)throw new IllegalStateException("video_session_limit");waiting=new Session(id);sessions.put(id,waiting);}
            if(waiting.prepareCallback!=null)waiting.takePrepareCallback().fail("cancelled","Video session preparation replaced");
            waiting.prepareCallback=callback;
            final Session pendingSession=waiting;
            decoderBudget.preemptSpare(()->main.post(()->{
                try{
                    if(sessions.get(id)!=pendingSession||pendingSession.prepareCallback!=callback)return;
                    pendingSession.takePrepareCallback();
                    try{prepare(args,callback,preservePixels,admissionStarted);}catch(Exception error){callback.fail(errorCode(error),"Video player admission failed");}
                }finally{decoderBudget.foregroundAdmitted();}
            }));
            return;
        }
        if(prewarming&&decoderBudget.inUse()-(existing!=null&&existing.decoderLease?1:0)>=memoryLimit){
            if(prefixFrames!=null){preparePixels(args,bounds,callback);return;}
            admission(args,memoryLimit<decoderBudget.limit()?"memory":decoderBudget.limit()<MAX_SESSIONS?"codec":"lease");
            throw new PodVideoCodecGate.AdmissionDenied(PodVideoCodecGate.PREWARM);
        }
        admission(args,"allowed");
        PodVideoAnalytics analytics=new PodVideoAnalytics();analytics.startupSeq=prewarming?0:PodBenchmark.videoPreparationSequence(args);
        if(mediaPrefixCache!=null)try{PodMediaPrefixCache.Entry entry=mediaPrefixCache.target(args.optString("url",""));analytics.cacheEntry=entry.ordinal;analytics.cacheNetworkBytes=()->entry.networkBytes;analytics.cacheRangeRequests=()->entry.rangeRequests;analytics.cacheBytes=()->entry.file.length()+entry.tail().length();}catch(Exception ignored){}
        PodVideoHttpDataSources.PrepareGate prepareGate=new PodVideoHttpDataSources.PrepareGate(args.optBoolean("cacheOnlyPrepare",false));
        MediaSource source = mediaSource(args,analytics,prepareGate);
        Session session = sessions.get(id);
        boolean created = false;
        if (session == null) {
            if (sessions.size() >= (MAX_SESSIONS)) throw new IllegalStateException("video_session_limit");
            session = new Session(id,prewarming,keyframeOnly);
            sessions.put(id, session);
            created = true;
        }
        if (session.prepareCallback != null) session.takePrepareCallback().fail("cancelled", "Video session preparation replaced");
        boolean replacingSource = session.generation > 0;
        boolean retained=preservePixels&&session.player==null&&session.hasCachedFrame();
        if(retained)session.cancelPrewarmTimeout();else session.resetPlayer();
        // A reused optional slot can now be the selected source, and vice
        // versa. Reserve its replacement surface at the incoming priority.
        session.prewarming=prewarming;
        session.keyframeOnly=keyframeOnly;
        if (replacingSource) {
            // A released codec can still own its old BufferQueue. A new source
            // must not connect a second codec to that retained TextureView.
            if (session.videoView != null && session.videoView.getParent() instanceof ViewGroup)
                ((ViewGroup) session.videoView.getParent()).removeView(session.videoView);
            session.videoView = null;
            PodMediaMemoryAccounting.close(session.surfaceCharge);session.surfaceCharge=null;
            try{session.attachViews(parent);}catch(RuntimeException unavailable){
                sessions.remove(id);session.close();throw unavailable;
            }
        }
        if (!decoderBudget.acquire()) {
            if (created) { sessions.remove(id); session.close(); }
            throw new PodVideoCodecGate.AdmissionDenied(prewarming?PodVideoCodecGate.PREWARM:PodVideoCodecGate.CURRENT);
        }
        session.decoderLease = true;
        if(!retained)session.generation += 1;
        final long generation = session.generation;
        session.analytics=analytics;
        analytics.sourceOrdinal=Math.max(0,args.optLong("sourceOrdinal",0));analytics.slotOrdinal=Math.max(0,args.optLong("slotOrdinal",0));analytics.admissionMs=android.os.SystemClock.uptimeMillis()-admissionStarted;analytics.deadlineMs=prewarming?PREWARM_MAX_LIFETIME_MS:0;
        analytics.clientGeneration=args.optLong("clientGeneration",0);
        analytics.preparation(args.optLong("prepareSeq",analytics.startupSeq),generation,prewarming);
        analytics.startup("prepare_admitted",analytics.admissionMs);
        session.admissionBudget.reset();
        session.frameObserver=callback;
        session.benchmarkAction=PodBenchmark.videoAction(args);
        session.benchmarkFramePending=session.benchmarkAction!=null&&!prewarming;
        session.frameSource=new JSONObject(args.toString());
        session.readTransform(args.optJSONObject("transform"));
        session.frameBinding=keyframeBinding(args,bounds);session.decoderName=null;
        session.bounds = bounds;
        session.backgroundPlayback=args.optBoolean("backgroundPlayback",false);
        if(!prewarming)mediaGate(true);
        session.profile = profile;
        session.autoplay = args.optBoolean("autoplay", true);
        // Old private muted/volume arguments must not survive a new session.

        session.errorCode = null;session.errorMirrorIndex=0;
        session.state = "preparing";
        session.hasRenderedFirstFrame = false;
        session.presented = !session.prewarming && bounds.visible;
        session.prepareCallback = callback;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setTargetBufferBytes(session.keyframeOnly?256*1024:targetBufferBytes())
                .setBufferDurationsMs(session.keyframeOnly?250:1_000, session.keyframeOnly?500:4_000, 100, 250)
                .setPrioritizeTimeOverSizeThresholds(PodProcessMemoryBudget.limit()==0)
                .build();
        ExoPlayer player;
        try {
            session.applyBounds(bounds);
            if(!session.keyframeOnly&&args.optLong("resumePositionMs",0)==0)session.revealKeyframe();
            final Session audioSession=session;
            session.bufferCharge=PodMediaMemoryAccounting.reserveResource(session,generation,sessionPriority(session),PodMediaMemoryBudget.Kind.COMPRESSED_BUFFER,(session.keyframeOnly?256*1024:targetBufferBytes())+256*1024L);
            if(session.bufferCharge==null)throw new IllegalStateException("memory_budget_deferred",new PodVideoCodecGate.ByteAdmissionDenied(sessionPriority(session)));
            PodVideoCodecGate.PROCESS.register(session,()->sessionPriority(audioSession),audioSession::reclaimDecoder);
            session.audioStop=new PodVideoAudioStop();
            player = new ExoPlayer.Builder(context,session.audioStop.renderers(context,"session_"+audioSession.id,audioSession,()->generation,()->sessionPriority(audioSession),analytics)).setLoadControl(loadControl).build();
            session.player = player;
            analytics.startup("player_constructed",-1);
            session.audioPolicy = new PodMediaAudioPolicy(context, player);
            session.audioPolicy.respectSilentMode(args.optBoolean("respectSilentMode",false));
            final Session audioOwner=session;
            session.audioPolicy.observeVolume(()->{if(!audioOwner.prewarming&&!audioOwner.disposed)try{callback.volumeChanged(audioOwner.snapshot());}catch(Exception ignored){}});
            session.audioPolicy.observeMuteChanges(player::isPlaying, player::getVolume, new PodVideoMuteTelemetry());
        }
        catch (RuntimeException error) {
            session.takePrepareCallback(); if(!session.prewarming)mediaGate(false); session.resetPlayer(); session.state = "error"; session.hideOffscreen();
            if (created) { sessions.remove(id); session.close(); }
            throw error;
        }
        Session own = session;
        player.addAnalyticsListener(session.analytics);player.addAnalyticsListener(session.analytics.playbackStats);
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener(){
            @Override public void onVideoDecoderInitialized(EventTime time,String name,long at,long duration){
                if(!own.matches(player,generation))return;
                own.decoderName=name;
                // The feed profile can describe display orientation or another
                // rendition. Restrict leases only with the selected track format.
                androidx.media3.common.Format format=player.getVideoFormat();
                if(format!=null&&format.sampleMimeType!=null){
                    own.profile=new PodVideoDecoderCapabilities.Profile(format.sampleMimeType,format.width,format.height);
                    lastProfile=own.profile;lastCapability=decoderCapabilities.inspect(own.profile,name);
                    decoderBudget.restrictTo(lastCapability.limit);enforceMemoryBudget(own.profile);
                }
            }
        });
        player.setAudioAttributes(mediaAudioAttributes(), !session.prewarming);
        session.backgroundService();
        session.completedLoops = 0;
        player.setRepeatMode(args.optBoolean("loop", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        session.audioPolicy.setVolume(1f, session.prewarming);
        final String cacheUri=args.optString("url");
        final PodMediaPrefixCache.Entry cached=mediaPrefixCache==null?null:mediaPrefixCache.registration(cacheUri);
        player.addListener(new Player.Listener() {
            private boolean rejectOversized(int width,int height){
                if(!own.matches(player,generation)||!PodVideoShortEdge.exceeds(args.optInt(PodVideoShortEdge.KEY),width,height))return false;
                final boolean prewarming=own.prewarming;
                own.errorMirrorIndex=cached==null?0:cached.mirrorIndex();
                if(mediaPrefixCache!=null){
                    if(cached!=null)mediaPrefixCache.rejectResolution(cached);
                    else mediaPrefixCache.rejectResolution(cacheUri);
                }
                failPrepare(own,player,generation,PodVideoShortEdge.ERROR,"Video exceeds the short-edge limit",false);
                if(!prewarming)try{callback.stateChanged(own.snapshot());}catch(Exception ignored){}
                return true;
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                if(rejectOversized(size.width,size.height))return;
                if (own.matches(player, generation)) {
                    // VideoSize describes the displayed, possibly rotated image.
                    // Codec admission uses encoded dimensions from Tracks.
                    if(own.matches(player,generation))own.fitVideo();
                }
            }

            @Override public void onTracksChanged(Tracks tracks) {
                if (!own.matches(player, generation)) return;
                int audioTracks=0,selectedAudio=0;
                for(Tracks.Group group:tracks.getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO){audioTracks+=group.length;for(int i=0;i<group.length;i++)if(group.isTrackSelected(i))selectedAudio++;}
                android.util.Log.i("PodMediaCache","selected_tracks source_generation="+generation+" audio_tracks="+audioTracks+" selected_audio="+selectedAudio+" is_prewarm="+(own.prewarming?1:0));
                for (Tracks.Group group : tracks.getGroups()) {
                    for (int index = 0; index < group.length; index++) {
                        if (!group.isTrackSelected(index)) continue;
                        androidx.media3.common.Format format = group.getTrackFormat(index);
                        if (format.sampleMimeType == null || !format.sampleMimeType.startsWith("video/")) continue;
                        if(rejectOversized(format.width,format.height))return;
                        own.profile = new PodVideoDecoderCapabilities.Profile(format.sampleMimeType, format.width, format.height);
                        PodVideoDecoderCapabilities.Result capability = decoderCapabilities.inspect(own.profile,own.decoderName);
                        lastProfile = own.profile; lastCapability = capability; decoderBudget.restrictTo(capability.limit);
                        enforceMemoryBudget(own.profile);
                        if (own.matches(player,generation) && own.prewarming && decoderBudget.inUse() > capability.limit) {
                            failPrepare(own, player, generation, "resource_exhausted", "video_decoder_limit", false);
                        }
                        return;
                    }
                }
            }

            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if(own.matches(player, generation) && reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)own.completedLoops++;
            }

            { own.progressObserver=()->changed(false); }
            private boolean pendingProgress=false;
            private void changed(boolean periodic){
                if(!own.matches(player,generation)||own.prewarming)return;
                if(periodic)own.updateOutput();
                try{
                    if(periodic&&args.optBoolean("compactProgress")&&own.hasRenderedFirstFrame&&own.geometryReady)
                        callback.progress(PodVideoAnalytics.progress(player,generation,own.analytics).put("sessionId",own.id));
                    else callback.stateChanged(own.snapshot());
                }catch(Exception ignored){}
                if(!pendingProgress&&(player.isPlaying()||player.getPlaybackState()==Player.STATE_BUFFERING)&&(own.bounds!=null&&own.bounds.visible)){pendingProgress=true;main.postDelayed(()->{pendingProgress=false;if(own.matches(player,generation)&&!own.prewarming&&(own.bounds!=null&&own.bounds.visible)&&(player.isPlaying()||player.getPlaybackState()==Player.STATE_BUFFERING))changed(true);},400);}
            }
            @Override public void onEvents(Player ignored,Player.Events events){changed(false);}

            @Override public void onPlaybackStateChanged(int playbackState) {
                if(own.matches(player,generation)&&!own.prewarming&&mediaPrefixCache!=null)mediaPrefixCache.foregroundBusy(PodVideoSessionServices.this,playbackState==Player.STATE_BUFFERING||!own.hasRenderedFirstFrame);
                if(own.matches(player,generation)&&!own.prewarming&&playbackState==Player.STATE_BUFFERING)
                    for(Session worker:new java.util.ArrayList<>(sessions.values()))if(worker.keyframeOnly)cancel(worker.id);
                if (!own.matches(player, generation)) return;
                if (playbackState == Player.STATE_BUFFERING) own.state = "buffering";
                else if (playbackState == Player.STATE_ENDED) { own.state = "ended"; if(!own.prewarming){if(own.analytics!=null)own.analytics.completed(generation,player.getCurrentPosition(),player.getDuration());mediaGate(false);} }
                else if (playbackState == Player.STATE_READY && !own.prewarming) {
                    own.state = player.isPlaying() ? "playing" : "ready";
                    prepareGate.ready();
                    own.completePrepare();
                }
                own.updateKeepScreenOn();
            }

            @Override public void onPlayWhenReadyChanged(boolean ready,int reason) {
                if (own.matches(player,generation)) own.updateKeepScreenOn();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (!own.matches(player, generation) || own.prewarming) return;
                own.state = player.getPlaybackState()==Player.STATE_ENDED ? "ended" : playing ? "playing" : "paused";
                own.updateKeepScreenOn();
            }

            @Override public void onRenderedFirstFrame() {
                if (!own.matches(player, generation)) return;
                androidx.media3.common.VideoSize size=player.getVideoSize();
                if(rejectOversized(size.width,size.height))return;
                if (!own.hasRenderedFirstFrame || !own.direct.hasLayer()) own.renderedFrame(callback);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (!own.matches(player, generation)) return;
                if(PodVideoCodecGate.isAdmissionDenied(error)){
                    if(PodVideoCodecGate.isByteAdmissionDenied(error)&&own.prewarming){
                        Callback pending=own.takePrepareCallback();own.resetPlayer();own.state="prewarmed";own.hideOffscreen();
                        if(pending!=null)pending.fail("decoder_admission_deferred","Optional video prewarm exceeds memory budget");
                    }else{
                        if(!own.prewarming&&args.optBoolean("cacheOnlyAdmissionRetry",false))prepareGate.restrict();
                        deferAdmission(own,player,generation,callback);
                    }
                    return;
                }
                if(!own.prewarming)mediaGate(false);
                boolean decoderFailure = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
                String code = PodVideoAnalytics.errorCode(error);
                own.errorCode=code;own.state="error";try{callback.stateChanged(own.snapshot());}catch(Exception ignored){}
                PodVideoFailure.record("sessions",own.prepareCallback==null?"playback":"prepare",code,error,own.frameSource,decoderBudget.inUse(),lastMemoryLimit,own.videoView==null?"missing":own.videoView.isAttachedToWindow()?"attached":"detached",own.analytics==null?"unknown":own.analytics.contentKind(),own.prewarming);
                if ("network_error".equals(code) && own.prepareCallback == null) {
                    // Retain the terminal state for polling, but release decoder/player resources.
                    own.errorCode = code; own.state = "error"; own.resetPlayer(); own.hideOffscreen();
                    return;
                }
                failPrepare(own, player, generation, code,
                        "Video playback failed",
                        own.prewarming && decoderFailure && (PodVideoCodecGate.isFactoryFailure(error)||own.decoderName!=null));
            }
        });
        try {
            if(prepareGate.cacheOnly())player.setMediaSource(source,Math.max(0,args.optLong("resumePositionMs",0)));
            else player.setMediaSource(source);
            // hideOffscreen keeps a live decoder's TextureView VISIBLE. Calling
            // it before player allocation leaves a new standby GONE forever,
            // so TextureView never creates the surface needed for first frame.
            if (session.prewarming) session.hideOffscreen(); else session.showPresented();
            player.setVideoTextureView(session.videoView);
            player.prepare();
            analytics.stage("dispatched",player,session.videoView);
            analytics.startup("prepare_dispatched",-1);
            if (session.prewarming || session.hasCachedFrame()) player.pause();
            else if (session.autoplay && session.mayPlay()) player.play();
            else player.pause();
            if(!session.prewarming&&session.hasCachedFrame())session.completePrepare();
            if (session.prewarming) {
                if(session.hasCachedFrame()&&!session.keyframeOnly){session.state="prewarmed";session.completePrepare();}
                else session.armPrewarmTimeout(player, generation);
            }
        } catch (RuntimeException error) {
            session.takePrepareCallback(); if(!session.prewarming)mediaGate(false); session.resetPlayer(); session.state = "error"; session.hideOffscreen();
            if (created) { sessions.remove(id); session.close(); }
            throw error;
        }
    }

    /** Media3 may deliver an old prewarm denial after this exact player was
     * promoted. Keep its selected identity, surface, callback and generation.
     * Only retry preparation after the physical gate has a returned permit. */
    private void deferAdmission(Session session,ExoPlayer expected,long generation,Callback observer) {
        session.cancelPrewarmTimeout();session.cancelAdmissionRetry();
        session.admissionBudget.denied();
        if(session.analytics!=null)session.analytics.timedStage("admission_deferred",session.admissionBudget.elapsed());
        session.admissionDeferred=true;session.errorCode=null;
        if(!session.hasCachedFrame())session.revealKeyframe();
        session.state=session.prewarming&&session.hasCachedFrame()?"prewarmed":"preparing";
        if(session.prewarming){session.hideOffscreen();if(session.hasCachedFrame())session.completePrepare();}
        else {session.showPresented();mediaGate(session.mayPlay());}
        try{observer.stateChanged(session.snapshot());}catch(Exception ignored){}
        if(!session.prewarming)PodVideoCodecGate.PROCESS.requestReclaim(session,PodVideoCodecGate.PREWARM);
        session.admissionRetry=new Runnable(){public void run(){
            if(!session.matches(expected,generation)||!session.admissionDeferred)return;
            if(session.admissionBudget.exhausted()){
                if(session.analytics!=null)session.analytics.timedStage("admission_exhausted",session.admissionBudget.elapsed());
                failPrepare(session,expected,generation,"resource_exhausted","Video decoder admission exhausted",false);
                if(!session.prewarming)try{observer.stateChanged(session.snapshot().put("sourceGeneration",generation));}catch(Exception ignored){}
                return;
            }
            if(!session.mayPlay()||PodVideoCodecGate.PROCESS.live()>=(session.keyframeOnly?2:PodVideoCodecGate.MAXIMUM)){
                main.postDelayed(this,PodVideoAdmissionRetry.POLL_MS);return;
            }
            session.admissionRetry=null;session.admissionDeferred=false;
            session.admissionBudget.restarted();
            if(session.analytics!=null)session.analytics.timedStage("admission_retry",session.admissionBudget.elapsed());
            // prepare() retries the same Media3 source after an init refusal.
            // Do not replace the player, seek, publish an error, or write capacity.
            expected.prepare();
            expected.setPlayWhenReady(!session.prewarming&&session.autoplay&&session.mayPlay()&&!session.hasCachedFrame());
            if(session.prewarming&&session.prepareCallback!=null)session.armPrewarmTimeout(expected,generation);
        }};
        main.postDelayed(session.admissionRetry,PodVideoAdmissionRetry.POLL_MS);
    }

    private void admission(JSONObject args,String reason) {
        try{PodBenchmark.begin("decoder_admission",new JSONObject().put("admission_reason",reason)
            .put("slots",lastMemoryLimit).put("decoder_in_use",decoderBudget.inUse())
            .put("system_headroom_bytes",lastSystemHeadroom).put("trim_level",lastTrimLevel)
            .put("keyframe_bytes",keyframes.bytes).put("keyframe_entries",keyframes.size())
            .put("is_prewarm",args.optBoolean("prewarm",false)));}catch(Exception ignored){}
    }

    private void failPrepare(Session session, ExoPlayer player, long generation, String code, String message, boolean downgrade) {
        if (!session.matches(player, generation)) return;
        if(!session.prewarming)mediaGate(false);
        if(code.equals("timeout")&&session.analytics!=null)session.analytics.stage("timeout",player,session.videoView);
        if(code.equals("timeout")||code.equals("resource_exhausted"))PodVideoFailure.record("sessions","prepare",code,null,session.frameSource,decoderBudget.inUse(),lastMemoryLimit,session.videoView==null?"missing":session.videoView.isAttachedToWindow()?"attached":"detached",session.analytics==null?"unknown":session.analytics.contentKind());
        Callback pending = session.takePrepareCallback();
        PodVideoDecoderCapabilities.Profile profile = session.profile;
        if (downgrade && profile != null && profile.known()) {
            int surviving=Math.max(1,decoderBudget.inUse()-1);
            decoderCapabilities.recordRuntimeLimit(profile,surviving);
            decoderBudget.restrictTo(surviving);
            lastProfile = profile;
            PodVideoDecoderCapabilities.Result probed = decoderCapabilities.inspect(profile);
            lastCapability = new PodVideoDecoderCapabilities.Result(profile, probed.decoderName,
                    probed.advertisedInstances, surviving, probed.sizeSupported, true);
        }
        session.state = "error";session.errorCode=code;
        if(session.prewarming){if(sessions.get(session.id)==session)sessions.remove(session.id);session.close();}
        else {session.resetPlayer();session.hideOffscreen();}
        if(pending!=null){
            if(PodVideoShortEdge.ERROR.equals(code))pending.fail(code,message,session.errorMirrorIndex);
            else pending.fail(code,message);
        }
    }

    private void captureFrame(JSONObject args,Callback callback)throws Exception {
        String key=PodVideoFrameCache.key(args),binding=PodVideoFrameCache.binding(args.getJSONObject("source"),mediaPrefixCache);
        Session session=sessions.get(sessionId(args,"sessionId"));
        if(session==null||session.player==null||session.prewarming||!session.hasRenderedFirstFrame||!session.geometryReady||session.generation!=args.optLong("sourceGeneration",-1)){
            callback.complete(new JSONObject().put("available",false));return;
        }
        if(!binding.equals(PodVideoFrameCache.binding(session.frameSource,mediaPrefixCache))){callback.complete(new JSONObject().put("available",false));return;}
        AutoCloseable memory=PodMediaMemoryAccounting.reserveResource(session,session.generation,PodVideoCodecGate.OTHER,PodMediaMemoryBudget.Kind.FRAME_CAPTURE,PodVideoFrameCache.CAPTURE_PROMISE_BYTES);
        if(memory==null){callback.complete(new JSONObject().put("available",false));return;}
        PodVideoFrameCache.captureAndSave(mediaRoot, key, binding, session.generation,
                session.player.getCurrentPosition(), session.videoView, session.direct, args, memory, main, callback::complete);
    }

    private void play(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        if(args.optBoolean("backgroundPlayback",session.backgroundPlayback))PodSessionPlaybackService.checkPermission(context);
        if(session.audioStop!=null)session.audioStop.cancel();
        session.prewarming = false;
        if(args.has("backgroundPlayback"))session.backgroundPlayback=args.optBoolean("backgroundPlayback");
        mediaGate(!lifecyclePaused);
        session.player.setAudioAttributes(mediaAudioAttributes(), true);
        session.audioPolicy.setVolume(1f, false);
        session.autoplay=true;
        session.backgroundService();
        if (session.mayPlay()&&!session.hasCachedFrame()) session.player.play();
        session.updateKeepScreenOn();
        PodBenchmark.swapStage(session.benchmarkAction,"play_command",session.generation);
        session.state = session.mayPlay() ? "playing" : "paused";
        callback.complete(session.snapshot());
    }

    private void retire(JSONObject args, Callback callback) throws Exception {
        String id=sessionId(args,"sessionId");
        Session session=sessions.get(id);
        // Cleanup remains idempotent after speculative/native reclamation.
        // An absent slot provides no decoded-frame or playback metrics proof.
        if(session==null){callback.complete(new JSONObject().put("ok",true).put("sessionId",id).put("state","stopped").put("positionMs",0).put("durationMs",0).put("presented",false).put("hasRenderedFirstFrame",false).put("hasCachedFirstFrame",false));return;}
        if(!session.prewarming)mediaGate(args.optBoolean("handoff",false)&&!lifecyclePaused);
        session.retire();
        callback.complete(session.snapshot());
    }
    private void pause(JSONObject args, Callback callback) throws Exception {
        // Paging may leave a source that error recovery already released.
        // Cleanup is idempotent, and an absent slot supplies no playback proof.
        if(args.optBoolean("handoff",false)&&!sessions.containsKey(sessionId(args,"sessionId"))){retire(args,callback);return;}
        Session session = require(args);
        releaseFrame.emit(session.id,PodBenchmark.videoAction(args));
        if(!session.prewarming)mediaGate(args.optBoolean("handoff",false)&&!lifecyclePaused);
        // Cancellation/retirement can remove the player before a paging pause.
        // Preserve the session state and do not emit playback proof for a no-op.
        session.autoplay=false;
        PodSessionPlaybackService.detach(context,session);
        if(session.player==null){PodVideoAudioStop.absent(args,session.generation);callback.complete(session.snapshot());return;}
        if(session.audioStop!=null)session.audioStop.arm(args,session.generation);
        session.player.pause(); session.state = session.prewarming ? "prewarmed" : "paused";
        session.updateKeepScreenOn();
        PodBenchmark.playerPaused(args,session.player.getPlayWhenReady(),session.generation);
        callback.complete(session.snapshot());
    }

    private void seek(JSONObject args, Callback callback) throws Exception {
        Session session = require(args); session.requirePlayer();
        long position = args.optLong("positionMs", -1);
        if (position < 0) throw new IllegalArgumentException("invalid_position");
        session.clearKeyframe();
        session.player.seekTo(position); callback.complete(session.snapshot());
    }

    private void stop(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        if(!session.prewarming)mediaGate(false);
        Callback pending = session.takePrepareCallback();
        session.resetPlayer(); session.state = "stopped"; session.presented = false; session.hideOffscreen();
        if (pending != null) pending.fail("cancelled", "Video session preparation stopped");
        callback.complete(session.snapshot());
    }

    private void release(JSONObject args, Callback callback) throws Exception {
        String id = sessionId(args, "sessionId");
        Session session = sessions.remove(id);
        if (session != null) { if(session == dragFrom || session == dragTo) endDrag(); if(!session.prewarming)mediaGate(false); session.close(); hideStandbys(); }
        if(sessions.isEmpty()){mediaGate(false);}
        callback.complete(new JSONObject().put("sessionId", id).put("released", session != null));
    }

    private void setBounds(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        Bounds bounds = Bounds.read(args.optJSONObject("bounds"), density, true);
        if(!session.prewarming || session == dragFrom || session == dragTo) endDrag();
        if(!session.prewarming && bounds.visible && bounds.pagerMoving) showPagerBackground(bounds);
        // Only the translated page and its selected neighbour contribute pixels.
        // A TextureView readback is not a composited page: FIT_XY loses the
        // aspect/rotation matrix and leaves a stationary stretched copy behind.
        // Cold neighbours expose the opaque black pager base until decoded.
        session.bounds = bounds; session.applyBounds(bounds);
        if(!session.prewarming)session.presented=bounds.visible;
        if (session.presented) { session.showPresented(); hideStandbys(); } else session.hideOffscreen();
        if(session.presented&&!session.prewarming&&session.hasRenderedFirstFrame&&session.analytics!=null)PodBenchmark.startup(session.analytics.startupSeq,"initial_frame_adopted");
        PodBenchmark.VideoAction action=PodBenchmark.videoAction(args.optJSONObject("bounds"));
        if(action!=null&&session.presented&&session.hasRenderedFirstFrame&&!bounds.pagerMoving){
            long own=session.generation;
            PodBenchmark.present(session.videoView,action.event("snap_back"),()->session.generation==own&&session.presented&&session.geometryReady&&session.hasRenderedFirstFrame&&session.videoView.getTranslationX()==bounds.translateXPx&&session.videoView.getTranslationY()==bounds.translateYPx);
        }
        callback.complete(session.snapshot());
    }

    private void setTransform(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        if(session.readTransform(args)){
            if(session.frameSource!=null)session.frameSource.put("transform",args);
        }
        // The source dimensions or attached views can change between identical guest transforms.
        // fitVideo already avoids applying an unchanged matrix to the same view.
        android.graphics.Matrix previous = session.appliedVideoMatrix;
        session.fitVideo();
        if (session.videoView != null && session.player != null && !session.player.isPlaying()
                && previous != null && previous == session.appliedVideoMatrix) {
            session.videoView.invalidate();
        }
        callback.complete(session.snapshot());
    }

    private void setLoop(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        Object loop=args.opt("loop");
        if(!(loop instanceof Boolean))throw new IllegalArgumentException("loop_must_be_boolean");
        if(session.frameSource!=null)session.frameSource.put("loop",loop);
        if(session.player!=null)session.player.setRepeatMode((Boolean)loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        callback.complete(session.snapshot());
    }

    private void setSpeed(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        float speed=PodVideoSpeed.parse(args);
        if(session.player!=null)session.player.setPlaybackSpeed(speed);
        callback.complete(session.snapshot());
    }

    private void setVolume(JSONObject args, Callback callback) throws Exception {
        Session session = require(args);
        PodVideoSystemAudio.update(context,args);
        if (session.audioPolicy != null) session.audioPolicy.refresh();
        callback.complete(session.snapshot());
    }

    private void promote(JSONObject args, Callback callback) throws Exception {
        if(args.optBoolean("backgroundPlayback",false))PodSessionPlaybackService.checkPermission(context);
        String requestedId=sessionId(args,"sessionId");
        Session candidate=sessions.get(requestedId);
        // A speculative slot may expire before this explicit foreground claim.
        // This closed acknowledgement allows the guest's single cold prepare;
        // it does not report a failed foreground player that never existed.
        if(candidate==null&&args.optBoolean("adoptPreparing",false)){callback.fail("not_ready","video_prewarm_unavailable");return;}
        final Session target=candidate==null?require(args):candidate;
        JSONObject transform=args.optJSONObject("transform");
        if(transform!=null){target.readTransform(transform);if(target.frameSource!=null)target.frameSource.put("transform",new JSONObject(transform.toString()));target.appliedVideoMatrix=null;target.appliedKeyframeMatrix=null;}
        if(prefixFrames!=null&&target.player==null){
            // A decoded cache entry can leave the byte-bounded window after
            // the guest observes readiness. Authorized source metadata still
            // permits this explicit foreground adoption to prepare once.
            if(!target.hasCachedFrame()&&!(args.optBoolean("adoptPreparing",false)&&target.frameSource!=null&&!"error".equals(target.state)&&!"stopped".equals(target.state)))throw new IllegalStateException("video_not_prewarmed");
            Session old=sessions.get(args.optString("replaceSessionId",""));
            boolean rendered=old!=null&&old.hasRenderedFirstFrame,cached=old!=null&&(old.hasCachedFrame()||keyframes.get(old.frameBinding)!=null);
            // A cold selected page exposes black until its own pixels arrive.
            if(old!=null){if(old.audioStop!=null)old.audioStop.arm(args,old.generation);else PodVideoAudioStop.absent(args,old.generation);old.retire();}
            JSONObject live=new JSONObject(target.frameSource.toString()).put("sessionId",target.id).put("prewarm",false).put("keyframeOnly",false)
                .put("backgroundPlayback",args.optBoolean("backgroundPlayback",false)).put("bounds",args.optJSONObject("bounds")).put("autoplay",args.optBoolean("autoplay",true)).put("benchmark",args.optJSONObject("benchmark"));
            if(args.has("loop"))live.put("loop",args.optBoolean("loop"));
            Callback result=new Callback(){public void complete(JSONObject value){try{callback.complete(value.put("replacedHasRenderedFirstFrame",rendered).put("replacedHasCachedFirstFrame",cached));}catch(Exception ignored){}}
                public void fail(String code,String message){callback.fail(code,message);}public void fail(String code,String message,int mirrorIndex){callback.fail(code,message,mirrorIndex);}public void firstFrame(JSONObject value){callback.firstFrame(value);}public void stateChanged(JSONObject value){callback.stateChanged(value);}public void progress(JSONObject value){callback.progress(value);}};
            if(!target.hasCachedFrame()){prepare(live,result);return;}
            // Reveal the retained decoded pixels before releasing a standby or
            // constructing Media3. Those operations can block the UI thread.
            endDrag();target.cancelPrewarmTimeout();
            target.bounds=Bounds.read(args.optJSONObject("bounds"),density,true);
            target.backgroundPlayback=args.optBoolean("backgroundPlayback",false);target.prewarming=false;target.presented=target.bounds.visible;
            target.autoplay=args.optBoolean("autoplay",true);target.state="preparing";
            target.benchmarkAction=PodBenchmark.videoAction(args);target.benchmarkFramePending=false;
            target.frameObserver=result;
            target.applyBounds(target.bounds);
            if(transform!=null)target.fitVideo();
            target.showPresented();hideStandbys();mediaGate(!lifecyclePaused);
            long own=target.generation;
            PodBenchmark.VideoAction action=target.benchmarkAction;
            PodBenchmark.swapStage(action,"surface_promoted",own);
            if(action!=null)PodBenchmark.watchVideo(target.keyframeView,new PodBenchmark.VideoAction(action.seq,action.generation,own,action.delta),
                ()->target.generation==own&&target.hasCachedFrame()&&target.presented&&!target.prewarming,true);
            target.prepareCallback=result;
            target.cancelFrameCompletion();
            final long attempt=++target.frameCompletionRevision;
            final android.widget.ImageView expectedKeyframe=target.keyframeView;
            target.frameCompletionCancel=handoffPaint.await(expectedKeyframe,committed->main.post(()->{
                if(target.disposed||target.generation!=own||target.keyframeView!=expectedKeyframe||target.frameCompletionRevision!=attempt||target.prewarming||sessions.get(target.id)!=target)return;
                target.frameCompletionRevision++;target.frameCompletionCancel=null;
                Callback pending=target.takePrepareCallback();if(pending==null)return;
                try{prepare(live,pending,true);}
                catch(Exception error){
                    String code=errorCode(error);target.state="error";target.errorCode=code;
                    target.resetPlayer();target.hideOffscreen();
                    PodVideoFailure.record("sessions","prepare",code,error,live,decoderBudget.inUse(),lastMemoryLimit,"attached","unknown");
                    pending.fail(code,error.getMessage()==null?"Video player admission failed":error.getMessage());
                }
            }));
            return;
        }
        target.requirePlayer();
        PodBenchmark.swapStage(PodBenchmark.videoAction(args),"promote_enter",target.generation);
        if (!target.hasRenderedFirstFrame && !target.hasCachedFrame() && !(args.optBoolean("adoptPreparing",false)&&
            (target.prewarming&&target.prepareCallback!=null||target.frameSource!=null&&!"error".equals(target.state)&&!"stopped".equals(target.state))))
            throw new IllegalStateException("video_not_prewarmed");
        Bounds requested = args.has("bounds") ? Bounds.read(args.optJSONObject("bounds"), density, true) : target.bounds;
        if (requested == null) throw new IllegalArgumentException("missing_bounds");
        String replacedId = args.optString("replaceSessionId", "");
        if (!replacedId.isEmpty() && replacedId.equals(target.id)) throw new IllegalArgumentException("invalid_replace_session");
        Session replaced = replacedId.isEmpty() ? null : sessions.get(replacedId);
        // The outgoing slot may already have been reclaimed. The selected
        // target still owns its decoder/frame and can be promoted without it.

        if(args.has("loop")&&!(args.opt("loop") instanceof Boolean))throw new IllegalArgumentException("loop_must_be_boolean");
        boolean replacedReady=replaced!=null&&replaced.hasRenderedFirstFrame&&!"error".equals(replaced.state);
        boolean replacedCached=replaced!=null&&(replaced.hasCachedFrame()||keyframes.get(replaced.frameBinding)!=null);
        endDrag();
        if (replaced != null) {if(replaced.audioStop!=null)replaced.audioStop.arm(args,replaced.generation);else PodVideoAudioStop.absent(args,replaced.generation);replaced.retire();}
        if(target.audioStop!=null)target.audioStop.cancel();
        if(args.has("loop"))target.player.setRepeatMode(args.optBoolean("loop")?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        target.bounds = requested;
        if(target.analytics!=null)target.analytics.stage("adopt",target.player,target.videoView);
        target.cancelPrewarmTimeout();
        // A speculative PixelCopy/paint fence cannot own the foreground handoff.
        // Retire its generation before starting the visible texture commit.
        target.cancelFrameCompletion();
        target.backgroundPlayback=args.optBoolean("backgroundPlayback",false);
        target.prewarming = false;
        if(target.player.getPlaybackState()==Player.STATE_READY)target.completePrepare();
        mediaGate(!lifecyclePaused);
        target.presented = requested.visible;
        target.applyBounds(requested);
        if(transform!=null)target.fitVideo();
        // Reveal the retained first-frame surface before hiding the old one.
        target.showPresented();
        hideStandbys();
        PodBenchmark.swapStage(PodBenchmark.videoAction(args),"surface_promoted",target.generation);
        boolean autoplay = args.optBoolean("autoplay", true);
        target.autoplay=autoplay;
        target.backgroundService();
        target.player.setAudioAttributes(mediaAudioAttributes(), true);
        target.audioPolicy.setVolume(1f, false);
        if (autoplay && target.mayPlay() && !target.hasCachedFrame()) target.player.play(); else target.player.pause();
        target.updateKeepScreenOn();
        PodBenchmark.swapStage(PodBenchmark.videoAction(args),"play_command",target.generation);
        target.state=target.player.getPlaybackState()==Player.STATE_BUFFERING?"buffering":target.player.isPlaying()?"playing":"paused";
        PodBenchmark.VideoAction action=PodBenchmark.videoAction(args);
        target.swapTextureLogged=false;target.benchmarkAction=action;target.benchmarkFramePending=action!=null&&(!target.hasRenderedFirstFrame||!target.hasUpdatedFrame);
        if(action!=null&&target.hasRenderedFirstFrame&&target.hasUpdatedFrame){long own=target.generation;PodBenchmark.watchVideo(target.videoView,new PodBenchmark.VideoAction(action.seq,action.generation,target.generation,action.delta),()->target.generation==own&&!target.prewarming&&target.presented&&target.geometryReady&&target.hasRenderedFirstFrame&&target.videoView.getTranslationY()==requested.translateYPx,true);}
        if(action!=null&&target.hasCachedFrame()){
            long own=target.generation;target.benchmarkFramePending=false;
            PodBenchmark.watchVideo(target.keyframeView,new PodBenchmark.VideoAction(action.seq,action.generation,own,action.delta),
                ()->target.generation==own&&target.hasCachedFrame()&&!target.prewarming&&target.presented&&target.keyframeView.getTranslationY()==requested.translateYPx,true);
        }
        if(target.hasCachedFrame()&&target.hasRenderedFirstFrame&&target.hasUpdatedFrame)target.finishDecodedFrame();
        callback.complete(target.snapshot().put("replacedHasRenderedFirstFrame",replacedReady).put("replacedHasCachedFirstFrame",replacedCached));
    }

    // Only the two pager pages may contribute pixels during a drag. Other
    // decoded standbys stay retained, but must not fill the exposed half.
    private Session dragFrom, dragTo;
    private View pagerBackground;
    private Bounds dragBounds;
    private String dragDirection;
    private int dragAxisX, dragAxisY;
    private final PodVideoDragInput dragInput = new PodVideoDragInput();
    private final PodVideoReleaseFrame releaseFrame=new PodVideoReleaseFrame();

    // Motion stays on the UI thread after JS has recognized the pager and
    // selected a decoded neighbour. Delayed guest updates cannot rewind it.
    void pagerInput(android.view.MotionEvent event) {
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)releaseFrame.clear();
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){
            Session from=dragFrom;
            if(from==null)for(Session s:sessions.values())if(!s.prewarming&&s.presented){from=s;break;}
            Session target=dragTo;
            if(from!=null){
                View picture=target==null?null:target.hasCachedFrame()?target.keyframeView:target.videoView;
                boolean decoded=target!=null&&(target.hasCachedFrame()||target.hasRenderedFirstFrame&&target.hasUpdatedFrame&&target.geometryReady);
                android.graphics.Rect rect=new android.graphics.Rect();
                boolean visible=decoded&&picture!=null&&picture.isShown()&&picture.hasWindowFocus()&&picture.getAlpha()>=1f
                    &&picture.getGlobalVisibleRect(rect)&&rect.width()>0&&rect.height()>0;
                boolean ready=target!=null&&target.hasRenderedFirstFrame&&target.player!=null&&target.player.getPlaybackState()==Player.STATE_READY;
                releaseFrame.record(from.id,from.generation,event.getEventTime(),target==null?0:target.generation,
                    target==null||target.analytics==null?0:target.analytics.sourceOrdinal,visible,ready,
                    decoded?(target.hasCachedFrame()?"cached":"live"):"none");
            }
        }
        if (dragInput.accept(event) && dragFrom != null && dragTo != null && dragBounds != null)
            drawDrag();
    }

    private float dragLength() {
        return dragAxisX == 0 ? dragBounds.heightPx : dragBounds.widthPx;
    }

    private void placeDragPage(Session page, float offset) {
        page.setTranslation(dragBounds.translateXPx + offset * dragAxisX,
                dragBounds.translateYPx + offset * dragAxisY);
    }

    private void drawDrag() {
        float length = dragLength();
        float distance = dragInput.distanceX() * dragAxisX + dragInput.distanceY() * dragAxisY;
        float offset = Math.max(-length, Math.min(length, distance));
        placeDragPage(dragFrom, offset);
        boolean up = "up".equals(dragDirection);
        boolean matching = up ? offset <= 0 : offset >= 0;
        dragTo.videoView.setAlpha(matching ? 1f : 0f);
        if(dragTo.keyframeView!=null)dragTo.keyframeView.setAlpha(matching ? 1f : 0f);
        dragTo.background.setVisibility(matching ? View.VISIBLE : View.GONE);
        if (matching) placeDragPage(dragTo, offset + (up ? length : -length));
    }

    private void endDrag() {
        dragInput.disarm(); dragFrom = null; dragTo = null; dragBounds = null; dragDirection = null;
        if(pagerBackground!=null)pagerBackground.setVisibility(View.GONE);
    }

    private void showPagerBackground(Bounds bounds) {
        if(parent==null)return;
        if(pagerBackground==null){pagerBackground=new View(context);pagerBackground.setBackgroundColor(android.graphics.Color.BLACK);}
        if(pagerBackground.getParent()!=parent){
            if(pagerBackground.getParent() instanceof ViewGroup)((ViewGroup)pagerBackground.getParent()).removeView(pagerBackground);
            parent.addView(pagerBackground, podIndex(parent), new FrameLayout.LayoutParams(1,1));
        }
        FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)pagerBackground.getLayoutParams();
        if(old.width!=bounds.widthPx||old.height!=bounds.heightPx||old.leftMargin!=bounds.xPx||old.topMargin!=bounds.yPx){
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(bounds.widthPx,bounds.heightPx);params.leftMargin=bounds.xPx;params.topMargin=bounds.yPx;pagerBackground.setLayoutParams(params);
        }
        pagerBackground.setTranslationX(bounds.routeXPx);
        pagerBackground.setTranslationY(bounds.floorYPx);
        pagerBackground.setVisibility(View.VISIBLE);
        parent.bringChildToFront(pagerBackground);
    }

    private void hideStandbys() {
        for (Session slot : sessions.values()) if (slot.prewarming && slot != dragTo) slot.hideOffscreen();
    }

    private void transition(JSONObject args, Callback callback) throws Exception {
        String fromId = sessionId(args, "fromSessionId");
        String toId = sessionId(args, "toSessionId");
        if (fromId.equals(toId)) throw new IllegalArgumentException("invalid_transition_sessions");
        Session from = sessions.get(fromId), to = sessions.get(toId);
        if (to == null) throw new IllegalArgumentException("unknown_transition_session");
        // Readiness is an observation, not a lease on cached pixels. During a
        // rapid swipe a valid target can lose those pixels before this command
        // reaches main. Compose its black page until decode becomes available.
        if (!args.has("progress")) throw new IllegalArgumentException("invalid_progress");
        float progress = finite(args, "progress", -1, 0, 1);
        String direction = args.optString("direction", "up");
        if (!direction.equals("up") && !direction.equals("down")) throw new IllegalArgumentException("invalid_transition_direction");
        Bounds bounds = args.has("bounds") ? Bounds.read(args.optJSONObject("bounds"), density, true) : from == null ? null : from.bounds;
        if (bounds == null) throw new IllegalArgumentException("missing_bounds");
        // Recovery can reclaim the outgoing page before this gesture reaches
        // main. Keep the authorized incoming decoder for promotion; there is
        // no outgoing surface to compose or acknowledge as a presented frame.
        if(from==null){endDrag();callback.complete(new JSONObject().put("fromSessionId",fromId).put("toSessionId",toId).put("progress",progress).put("direction",direction).put("composited",false).put("nativeTracking",false));return;}
        float angle = finite(args, "rotationDeg", 0, -100000, 100000);
        if (angle % 90 != 0) throw new IllegalArgumentException("invalid_rotationDeg");
        int quarter = Math.floorMod((int) angle / 90, 4);
        dragAxisX = quarter == 1 ? -1 : quarter == 3 ? 1 : 0;
        dragAxisY = quarter == 0 ? 1 : quarter == 2 ? -1 : 0;
        // Cold pages expose black; decoded pages keep their own aspect matrix.
        boolean changedPair = dragFrom != from || dragTo != to;
        dragFrom = from; dragTo = to; dragBounds = bounds; dragDirection = direction;
        if(changedPair) { showPagerBackground(bounds); hideStandbys(); }
        // The strip uses one display transform for every page, including
        // a retained paused texture and a cold target without decoded pixels.
        boolean settleRotation = args.has("rotationDeg") && (from.rotationDeg != angle || from.scaleRotationDeg != angle);
        if (settleRotation) {
            from.rotationDeg = from.scaleRotationDeg = angle;
            from.recordPresentationTransform();
        }
        boolean transformChanged=!to.scaleMode.equals(from.scaleMode)||to.rotationDeg!=from.rotationDeg
                ||to.scaleRotationDeg!=from.scaleRotationDeg||to.panX!=from.panX||to.panY!=from.panY;
        if(transformChanged) {
            to.scaleMode=from.scaleMode;to.rotationDeg=from.rotationDeg;to.scaleRotationDeg=from.scaleRotationDeg;to.panX=from.panX;to.panY=from.panY;
            to.recordPresentationTransform();
        }
        from.bounds = bounds; to.bounds = bounds; from.applyBounds(bounds, false); to.applyBounds(bounds, false);
        if(settleRotation)from.fitVideo();
        to.fitVideo();
        if(changedPair) { to.raisePresented(); from.raisePresented(); }
        from.videoView.setVisibility(View.VISIBLE); to.videoView.setVisibility(View.VISIBLE);
        // Keep the texture drawable so HWUI can latch the first decoded buffer.
        // An empty texture exposes the opaque black page below it.
        from.videoView.setAlpha(1f); to.videoView.setAlpha(1f);
        if(to.keyframeView!=null){to.keyframeView.setVisibility(View.VISIBLE);to.keyframeView.setAlpha(1f);}
        from.background.setVisibility(View.VISIBLE); to.background.setVisibility(View.VISIBLE);
        float[] offsets = transitionOffsets(dragLength(), progress, direction);
        if (args.optBoolean("trackPointer", false) && dragInput.arm()) drawDrag();
        else {
            dragInput.disarm();
            placeDragPage(from, offsets[0]);
            placeDragPage(to, offsets[1]);
        }
        PodBenchmark.VideoAction action=PodBenchmark.videoAction(args);
        if(progress==0&&action!=null){long own=from.generation;PodBenchmark.present(from.videoView,action.event("snap_back"),()->from.generation==own&&from.geometryReady&&from.hasRenderedFirstFrame&&from.videoView.getTranslationX()==bounds.translateXPx&&from.videoView.getTranslationY()==bounds.translateYPx);}
        callback.complete(new JSONObject()
                .put("fromSessionId", fromId).put("toSessionId", toId)
                .put("progress", progress).put("direction", direction)
                .put("fromOffsetXPx", offsets[0] * dragAxisX).put("toOffsetXPx", offsets[1] * dragAxisX)
                .put("fromOffsetYPx", offsets[0] * dragAxisY).put("toOffsetYPx", offsets[1] * dragAxisY)
                .put("composited", true).put("nativeTracking", dragInput.tracking()));
    }

    static float[] transitionOffsets(float heightPx, float progress, String direction) {
        float p = Math.max(0f, Math.min(1f, progress));
        if ("down".equals(direction)) return new float[]{p * heightPx, -(1f - p) * heightPx};
        return new float[]{-p * heightPx, (1f - p) * heightPx};
    }

    private MediaSource mediaSource(JSONObject args,PodVideoAnalytics analytics,PodVideoHttpDataSources.PrepareGate prepareGate) throws Exception {
        String videoUrl = args.optString("videoUrl", "");
        String audioUrl = args.optString("audioUrl", "");
        if(videoUrl.isEmpty()&&!audioUrl.isEmpty())videoUrl=args.optString("url", "");
        String url = videoUrl.isEmpty() || audioUrl.isEmpty() ? source(args, "url") : sourceValue(videoUrl);
        if (!audioUrl.isEmpty()) audioUrl = sourceValue(audioUrl);
        if (!videoUrl.isEmpty()) videoUrl = sourceValue(videoUrl);
        Map<String, String> headers = headers(args.optJSONObject("headers"));
        androidx.media3.datasource.DataSource.Factory sources=PodVideoHttpDataSources.playback(context,mediaPrefixCache,headers,args.optBoolean("bypassCache",false)&&!prepareGate.cacheOnly(),prepareGate);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(()->{androidx.media3.datasource.DataSource data=sources.createDataSource();data.addTransferListener(analytics);return data;});
        if (!videoUrl.isEmpty() && !audioUrl.isEmpty()) {
            return new MergingMediaSource(factory.createMediaSource(item(videoUrl, args.optString("type", ""))), factory.createMediaSource(item(audioUrl, "")));
        }
        return factory.createMediaSource(item(url, args.optString("type", "")));
    }

    private String keyframeBinding(JSONObject args,Bounds bounds) {
        try{
            String binding=PodVideoFrameCache.binding(args,mediaPrefixCache);
            String uri=args.optString("url","");
            if(mediaPrefixCache!=null&&mediaPrefixCache.owns(uri)){
                PodMediaPrefixCache.Entry entry=mediaPrefixCache.target(uri);
                String prefix=PodVideoFrameCache.binding(new JSONObject().put("url",uri).put("audioUrl",entry.frameAudioUrl),mediaPrefixCache);
                android.util.Log.i("PodMediaCache","frame_lookup entry="+entry.ordinal+" source_ordinal="+Math.max(0,args.optLong("sourceOrdinal",0))+" hit="+(keyframes.get(binding)!=null?1:0)+" prefix_hit="+(keyframes.get(prefix)!=null?1:0)+" binding_match="+(binding.equals(prefix)?1:0)+" mono_ms="+android.os.SystemClock.uptimeMillis());
            }
            return binding;
        }
        catch(Exception unavailable){return null;}
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
        if(mediaPrefixCache!=null&&(mediaPrefixCache.owns(value)||mediaPrefixCache.ownsLocal(value)))return value;
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
        if(error instanceof PodVideoShortEdge.Rejected)return PodVideoShortEdge.ERROR;
        if(PodVideoCodecGate.isAdmissionDenied(error))return "decoder_admission_deferred";
        if (error instanceof SecurityException) return "permission_denied";
        if (error instanceof IllegalArgumentException) return "invalid_argument";
        if ("video_foreground_busy".equals(error.getMessage()) || "video_session_limit".equals(error.getMessage()) || "video_decoder_limit".equals(error.getMessage())) return "resource_exhausted";
        if ("video_not_prewarmed".equals(error.getMessage())) return "not_ready";
        return "video_session_error";
    }

    private int podIndex(ViewGroup host) {
        for (int index = 0; index < host.getChildCount(); index++) if (host.getChildAt(index) instanceof PodRuntimeView) return index;
        return host.getChildCount();
    }

    private final class Session implements AutoCloseable {
        void backgroundService(){
            if(backgroundPlayback&&!prewarming&&autoplay&&player!=null)PodSessionPlaybackService.attach(context,this,player,()->{autoplay=false;if(player!=null)player.pause();state="paused";PodSessionPlaybackService.detach(context,this);});
        }
        boolean mayPlay(){return PodVideoBackgroundPolicy.mayPlay(lifecyclePaused,backgroundPlayback,prewarming);}
        final String id;
        TextureView videoView;
        final PodVideoDirectOutput direct = new PodVideoDirectOutput(main);
        View background;
        ExoPlayer player;
        PodMediaAudioPolicy audioPolicy;
        PodVideoAudioStop audioStop;
        Bounds bounds;
        Runnable progressObserver=()->{};
        Callback prepareCallback;
        Runnable prewarmTimeout;
        PodVideoDecoderCapabilities.Profile profile;
        String state = "idle";
        String errorCode;int errorMirrorIndex;
        String scaleMode = "standard";
        float rotationDeg, scaleRotationDeg, panX, panY;
        final PodVideoTransformCache liveGeometry=new PodVideoTransformCache(), keyframeGeometry=new PodVideoTransformCache();
        TextureView transformedVideo;
        android.widget.ImageView transformedKeyframe;
        android.graphics.Matrix appliedVideoMatrix, appliedKeyframeMatrix;
        boolean backgroundPlayback;
        volatile boolean prewarming, keyframeOnly;
        boolean decoderLease, autoplay, presented, hasRenderedFirstFrame, disposed, admissionDeferred;
        Runnable admissionRetry;
        final PodVideoAdmissionRetry.Budget admissionBudget=new PodVideoAdmissionRetry.Budget();
        Callback frameObserver;
        long generation, completedLoops;
        JSONObject frameSource;
        String frameBinding, decoderName;
        android.widget.ImageView keyframeView;
        PodVideoKeyframes.Frame cachedFrame;
        AutoCloseable bufferCharge,surfaceCharge;
        boolean surfaceCommitted;
        Runnable frameCompletionCancel, frameCompletionRetry;
        long frameCompletionRevision;
        PodVideoAnalytics analytics;
        PodBenchmark.VideoAction benchmarkAction;
        boolean swapTextureLogged, hasUpdatedFrame;
        boolean benchmarkFramePending;
        boolean geometryReady, geometryWaitExpired;
        long geometryWaitRevision;
        Runnable geometryCommitCancel, geometryDeadline;

        Session(String id) { this(id,false,false); }
        Session(String id,boolean prewarming,boolean keyframeOnly) {
            this.id=id;
            this.prewarming=prewarming;
            this.keyframeOnly=keyframeOnly;
            attachViews(parent);
            shellCreated++;
        }

        void attachViews(ViewGroup host) {
            if (host == null || disposed) return;
            if (background == null) {
                background = new View(context);
                background.setBackgroundColor(android.graphics.Color.BLACK);
                background.setVisibility(View.GONE);
            }
            if (videoView == null) {
                android.util.DisplayMetrics screen=context.getResources().getDisplayMetrics();
                surfaceCharge=PodMediaMemoryAccounting.reserveResource(this,generation,sessionPriority(this),PodMediaMemoryBudget.Kind.SURFACE,Math.max(1L,(long)screen.widthPixels*screen.heightPixels)*12);
                if(surfaceCharge==null)throw new IllegalStateException("memory_budget_deferred",new PodVideoCodecGate.ByteAdmissionDenied(sessionPriority(this)));
                surfaceCommitted=false;
                // A retained cached page can replace its TextureView without
                // changing source generation. Never inherit the old paint fence.
                cancelFrameCompletion();resetGeometryWait();hasUpdatedFrame=false;
                videoView = new PodObservedTextureView(context, (output,texture) -> {direct.textureUpdated(texture); textureUpdated(output);}) {
                    @Override void outputChanged(){
                        if(videoView==this&&!direct.switchingOutput()){hasUpdatedFrame=false;cancelFrameCompletion();resetGeometryWait();}
                    }
                    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { return false; }
                };
                videoView.setClickable(false); videoView.setFocusable(false); videoView.setVisibility(View.GONE);
            }
            if (background.getParent() != host || videoView.getParent() != host) {
                if (background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
                if (videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
                int index = podIndex(host);
                host.addView(background, index, new FrameLayout.LayoutParams(1, 1));
                host.addView(videoView, index + 1, new FrameLayout.LayoutParams(1, 1));
            }
        }

        private boolean visibleDecodedTexture(){
            return !prewarming&&presented&&hasRenderedFirstFrame&&bounds!=null&&bounds.visible;
        }

        private boolean benchmarkReady(){
            return benchmarkFramePending&&benchmarkAction!=null&&hasUpdatedFrame&&geometryReady&&visibleDecodedTexture()
                &&videoView!=null&&videoView.getAlpha()>0;
        }

        private void watchDecodedBenchmark(){
            if(!benchmarkReady())return;
            final long own=generation;
            final TextureView output=videoView;
            PodObservedTextureView observed=output instanceof PodObservedTextureView?(PodObservedTextureView)output:null;
            final long revision=observed==null?0:observed.outputRevision();
            PodBenchmark.VideoAction action=new PodBenchmark.VideoAction(benchmarkAction.seq,benchmarkAction.generation,own,benchmarkAction.delta);
            benchmarkFramePending=false;
            PodBenchmark.watchVideo(output,action,()->!disposed&&generation==own&&videoView==output&&
                (observed==null||observed.outputRevision()==revision)&&geometryReady&&visibleDecodedTexture()&&output.getTranslationY()==bounds.translateYPx);
        }

        private boolean needsDecodedFrame(){
            if(!hasRenderedFirstFrame||player==null||frameCompletionCancel!=null)return false;
            if(!prewarming)return hasCachedFrame();
            return frameBinding!=null&&(prepareCallback!=null||keyframes.get(frameBinding)==null);
        }

        private void textureUpdated(PodObservedTextureView output){
            if(disposed||videoView!=output)return;
            final long revision=output.outputRevision(),own=generation;
            hasUpdatedFrame=true;
            if(!surfaceCommitted){PodMediaMemoryAccounting.commitResource(surfaceCharge);surfaceCommitted=true;}
            PodMediaStartupPreview preview=mediaPrefixCache==null?null:mediaPrefixCache.startupPreview;
            boolean overlay=preview!=null&&preview.needsTexture(frameSource);
            boolean geometry=!geometryReady&&geometryCommitCancel==null&&player!=null&&hasRenderedFirstFrame;
            boolean benchmark=benchmarkAction!=null&&(!swapTextureLogged||benchmarkReady());
            if(!overlay&&!geometry&&!benchmark&&!needsDecodedFrame())return;
            output.afterUpdate(main,own,()->{
                if(disposed||videoView!=output||generation!=own)return;
                if(preview!=null&&preview.needsTexture(frameSource))preview.handoff(frameSource.optString("url"),output,
                    ()->!disposed&&generation==own&&videoView==output&&output.outputRevision()==revision&&visibleDecodedTexture());
                confirmGeometry();
                if(needsDecodedFrame())finishDecodedFrame();
                if(benchmarkAction!=null&&!swapTextureLogged){swapTextureLogged=true;PodBenchmark.swapStage(benchmarkAction,"texture_updated",generation);}
                watchDecodedBenchmark();
            });
        }

        boolean matches(ExoPlayer candidate, long expectedGeneration) { return !disposed && player == candidate && generation == expectedGeneration; }
        void requirePlayer() { if (player == null) throw new IllegalStateException("video_not_ready"); }
        Callback takePrepareCallback() { Callback callback = prepareCallback; prepareCallback = null; return callback; }
        void completePrepare() {
            cancelPrewarmTimeout();
            Callback callback = takePrepareCallback();
            if (callback == null) return;
            try {
                JSONObject result=snapshot();
                if(keyframeOnly){sessions.remove(id);close();}
                callback.complete(result);
            }
            catch (Exception error) { callback.fail("video_session_error", error.getMessage()); }
        }

        void renderedFrame(Callback callback) {
            PodMediaMemoryAccounting.commitResource(bufferCharge);
            hasRenderedFirstFrame=true;
            admissionBudget.reset();
            confirmGeometry();
            if(!prewarming){PodMediaStartupPreview.mark("videoFirstFrame");android.util.Log.i("PodMediaCache","playback_tracks audio="+(player!=null&&player.getAudioFormat()!=null?1:0)+" video="+(player!=null&&player.getVideoFormat()!=null?1:0)+" local="+(frameSource!=null&&frameSource.optString("url").startsWith("file:")?1:0));}
            if(!prewarming&&mediaPrefixCache!=null)mediaPrefixCache.foregroundBusy(PodVideoSessionServices.this,false);
            PodBenchmark.swapStage(benchmarkAction,"rendered_first_frame",generation);
            if(!prewarming&&mediaPrefixCache!=null)mediaPrefixCache.logPlaybackStats();
            if(!hasCachedFrame())try{callback.firstFrame(snapshot());}catch(Exception ignored){}
            if(prewarming){
                player.pause();player.setPlayWhenReady(false);state="prewarmed";hideOffscreen();
                // MediaCodec has submitted a decoded buffer. A hidden TextureView
                // can be culled until it is exposed, so its update callback must
                // not gate the live decoder's readiness. Presentation stays gated.
                if(!keyframeOnly)completePrepare();
                if(hasUpdatedFrame)finishDecodedFrame();
            }else{if(hasUpdatedFrame&&hasCachedFrame())finishDecodedFrame();completePrepare();}
        }

        // Decoder eviction must preserve the authorized session identity. A
        // promotion receipt can still refer to this retired page while JS
        // publishes the next neighbour. Destroying it breaks replaceSessionId.
        void reclaimDecoder() {
            if(!prewarming||player==null)return;
            Callback pending=takePrepareCallback();
            if(!hasCachedFrame())revealKeyframe();
            if(hasCachedFrame())lendDecoder();else resetPlayer();
            state="prewarmed";hideOffscreen();
            if(pending!=null)pending.fail("cancelled","Speculative decoder reclaimed");
        }

        void lendDecoder() {
            direct.discard(true);
            PodSessionPlaybackService.detach(context,this);
            progressObserver=()->{};
            if(player==null)return;
            if(!hasCachedFrame())revealKeyframe();
            if(!hasCachedFrame())return;
            cancelAdmissionRetry();
            PodVideoCodecGate.PROCESS.unregister(this);
            cancelFrameCompletion();
            if(audioPolicy!=null){audioPolicy.close();audioPolicy=null;}
            ExoPlayer previous=player;player=null;
            try{previous.clearVideoTextureView(videoView);}catch(RuntimeException ignored){}
            try{previous.stop();}catch(RuntimeException ignored){}
            previous.release();
            PodMediaMemoryAccounting.close(bufferCharge);bufferCharge=null;
            if(decoderLease){decoderLease=false;decoderBudget.release();}
            hasRenderedFirstFrame=false;hasUpdatedFrame=false;state="prewarmed";completePrepare();
        }

        void resetPlayer() {
            direct.discard(false);
            PodSessionPlaybackService.detach(context,this);
            cancelAdmissionRetry();
            PodVideoCodecGate.PROCESS.unregister(this);
            progressObserver=()->{};
            if(this == dragFrom || this == dragTo || presented) endDrag();
            cancelFrameCompletion();
            clearKeyframe();
            PodBenchmark.cancelVideo(videoView);
            if(analytics!=null)analytics.stage("release",player,videoView);
            benchmarkFramePending=false;benchmarkAction=null;frameObserver=null;
            cancelPrewarmTimeout();
            generation += 1;
            if (audioPolicy != null) { audioPolicy.close(); audioPolicy = null; }
            ExoPlayer previous = player; player = null; audioStop=null;
            if (previous != null) {
                try { previous.clearVideoTextureView(videoView); } catch (RuntimeException ignored) { }
                try { previous.stop(); } catch (RuntimeException ignored) { }
                previous.release();
                if(analytics!=null)analytics.retireDecoder();
            }
            PodMediaMemoryAccounting.close(bufferCharge);bufferCharge=null;
            if (decoderLease) { decoderLease = false; decoderBudget.release(); }
            hasRenderedFirstFrame = false;hasUpdatedFrame=false;resetGeometryWait();
            if (videoView != null) videoView.setKeepScreenOn(false);
        }

        void cancelAdmissionRetry(){if(admissionRetry!=null){main.removeCallbacks(admissionRetry);admissionRetry=null;}admissionDeferred=false;}

        void armPrewarmTimeout(ExoPlayer expected, long expectedGeneration) {
            cancelPrewarmTimeout();
            final long started=android.os.SystemClock.uptimeMillis();
            prewarmTimeout = new Runnable() {
                @Override public void run() {
                    if (!matches(expected, expectedGeneration) || !prewarming || prepareCallback == null) {prewarmTimeout=null;return;}
                    long now=android.os.SystemClock.uptimeMillis();
                    long progress=analytics==null?started:Math.max(started,analytics.preparationProgressMs());
                    long idleLeft=PREWARM_TIMEOUT_MS-Math.max(0,now-progress);
                    long lifetimeLeft=PREWARM_MAX_LIFETIME_MS-Math.max(0,now-started);
                    // A slow but productive CDN read is not a stalled decoder.
                    // The hard lifetime still bounds speculative resource ownership.
                    if(idleLeft>0&&lifetimeLeft>0){main.postDelayed(this,Math.min(idleLeft,lifetimeLeft));return;}
                    prewarmTimeout=null;
                    failPrepare(Session.this,expected,expectedGeneration,"timeout","video_prewarm_timeout",false);
                }
            };
            main.postDelayed(prewarmTimeout, PREWARM_TIMEOUT_MS);
        }

        void cancelPrewarmTimeout() {
            if (prewarmTimeout == null) return;
            main.removeCallbacks(prewarmTimeout); prewarmTimeout = null;
        }

        private boolean progressVisible;
        void applyBounds(Bounds value) { applyBounds(value, true); }

        void applyBounds(Bounds value, boolean translate) {
            attachViews(parent);
            FrameLayout.LayoutParams current=(FrameLayout.LayoutParams)videoView.getLayoutParams();
            boolean layout=current==null||current.width!=value.widthPx||current.height!=value.heightPx
                    ||current.leftMargin!=value.xPx||current.topMargin!=value.yPx;
            if(layout) {
                direct.discard(true);
                FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(value.widthPx,value.heightPx);
                params.leftMargin=value.xPx;params.topMargin=value.yPx;
                videoView.setLayoutParams(params);
                background.setLayoutParams(new FrameLayout.LayoutParams(params));
                if(keyframeView!=null)keyframeView.setLayoutParams(new FrameLayout.LayoutParams(params));
            }
            if(translate) setTranslation(value.translateXPx, value.translateYPx);
            if(layout)fitVideo();
            if(value.visible!=progressVisible){progressVisible=value.visible;progressObserver.run();}
        }

        private int clipLeft, clipTop, clipRight, clipBottom;
        private final ViewOutlineProvider pagerOutline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(clipLeft, clipTop, clipRight, clipBottom, bounds.clipRadiusPx);
            }
        };

        void setTranslation(float x, float y) {
            videoView.setTranslationX(x); videoView.setTranslationY(y);
            background.setTranslationX(x); background.setTranslationY(y);
            if(keyframeView!=null){keyframeView.setTranslationX(x);keyframeView.setTranslationY(y);}
            if(bounds != null && bounds.clipRadiusPx>0) {
                float cx=bounds.clipCxPx-(x-bounds.routeXPx),cy=bounds.clipCyPx-(y-bounds.routeYPx),r=bounds.clipRadiusPx;
                int left=Math.round(cx-r),top=Math.round(cy-r),right=Math.round(cx+r),bottom=Math.round(cy+r);
                boolean changed=left!=clipLeft||top!=clipTop||right!=clipRight||bottom!=clipBottom||!videoView.getClipToOutline();
                clipLeft=left;clipTop=top;clipRight=right;clipBottom=bottom;
                if(videoView.getOutlineProvider()!=pagerOutline){videoView.setOutlineProvider(pagerOutline);background.setOutlineProvider(pagerOutline);}
                videoView.setClipToOutline(true);background.setClipToOutline(true);
                if(changed){videoView.invalidateOutline();background.invalidateOutline();}
                if(keyframeView!=null){
                    boolean newOutline=keyframeView.getOutlineProvider()!=pagerOutline;
                    if(newOutline)keyframeView.setOutlineProvider(pagerOutline);
                    keyframeView.setClipToOutline(true);
                    if(changed||newOutline)keyframeView.invalidateOutline();
                }
            } else { videoView.setClipToOutline(false);background.setClipToOutline(false);if(keyframeView!=null)keyframeView.setClipToOutline(false); }
            if (direct.hasLayer()) {
                direct.syncPose();
                if (x != 0 || y != 0 || dragFrom != null || dragTo != null) direct.interactive();
            }

        }

        void retire() {
            direct.discard(true);
            if(!prewarming&&analytics!=null&&player!=null)analytics.retired(generation,player.getCurrentPosition(),player.getDuration(),player.getPlaybackState()==Player.STATE_ENDED);
            if(this == dragFrom || this == dragTo || presented) endDrag();
            autoplay=false;
            PodSessionPlaybackService.detach(context,this);
            PodBenchmark.cancelVideo(videoView);
            if(analytics!=null)analytics.stage("retire",player,videoView);
            Callback pending=takePrepareCallback();
            if(pending!=null) { resetPlayer();pending.fail("cancelled","Video session retired"); }
            if(player != null) { if(audioPolicy!=null)audioPolicy.setHeldMuted(true);else player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build()); player.pause(); }
            prewarming=true; presented=false;
            if(player != null) state="prewarmed";
            hideOffscreen();
        }

        private void cancelGeometryAttempt() {
            geometryWaitRevision++;
            if(geometryCommitCancel!=null){geometryCommitCancel.run();geometryCommitCancel=null;}
        }

        private void resetGeometryWait() {
            cancelGeometryAttempt();
            if(geometryDeadline!=null){main.removeCallbacks(geometryDeadline);geometryDeadline=null;}
            geometryReady=false;geometryWaitExpired=false;
        }

        private void revealGeometry() {
            if(this==dragFrom||this==dragTo){dragTo.raisePresented();dragFrom.raisePresented();}
            else if(presented&&!prewarming)showPresented();
        }

        // Bound the black cover separately from proof of a committed transform.
        // Fallback permits display, but never authorizes frame capture/telemetry.
        void confirmGeometry() {
            if(geometryReady||geometryCommitCancel!=null||player==null||!hasRenderedFirstFrame||!hasUpdatedFrame)return;
            androidx.media3.common.VideoSize size=player.getVideoSize();
            if(size.width<=0||size.height<=0)return;
            final TextureView expectedTexture=videoView;
            final long own=generation;
            if(geometryDeadline==null&&!geometryWaitExpired){
                geometryDeadline=()->{
                    geometryDeadline=null;
                    if(disposed||generation!=own||videoView!=expectedTexture||geometryReady)return;
                    geometryWaitExpired=true;cancelGeometryAttempt();revealGeometry();
                };
                main.postDelayed(geometryDeadline,GEOMETRY_WAIT_MAX_MS);
            }
            appliedVideoMatrix=null;fitVideo();
            final long attempt=++geometryWaitRevision;
            // PodAfterPaint can complete synchronously for a hidden/detached
            // view. Post every completion so the returned handle is assigned
            // before it is cleared; fence duplicate or obsolete completions.
            java.util.function.Consumer<Boolean> complete=committed->main.post(()->{
                if(disposed||generation!=own||videoView!=expectedTexture||geometryWaitRevision!=attempt)return;
                geometryWaitRevision++;geometryCommitCancel=null;
                if(!committed){
                    final long completedAttempt=geometryWaitRevision;
                    if(!geometryWaitExpired)main.postDelayed(()->{
                        if(!disposed&&generation==own&&videoView==expectedTexture&&geometryWaitRevision==completedAttempt&&!geometryWaitExpired)confirmGeometry();
                    },16);
                    return;
                }
                geometryReady=true;geometryWaitExpired=false;
                if(geometryDeadline!=null){main.removeCallbacks(geometryDeadline);geometryDeadline=null;}
                revealGeometry();
                // A paused decoder can latch exactly one frame. Its actual
                // geometry commit can be the last condition for observation.
                watchDecodedBenchmark();
            });
            try{geometryCommitCancel=PodAfterPaint.await(expectedTexture,main,1,(frame,time)->{},complete);}
            catch(RuntimeException unavailable){complete.accept(false);}
        }

        void showPresented() {
            if (bounds == null || videoView == null || background == null) return;
            // A hidden foreground reservation still needs its decoded TextureView.
            if(!bounds.visible){hideOffscreen();return;}
            setTranslation(bounds.translateXPx, bounds.translateYPx);
            raisePresented();
            videoView.setAlpha(1f);
            if(keyframeView!=null)keyframeView.setVisibility(View.VISIBLE);
            videoView.setVisibility(bounds.visible ? View.VISIBLE : View.GONE);
            background.setVisibility(bounds.visible ? View.VISIBLE : View.GONE);
            updateKeepScreenOn();
        }

        void updateKeepScreenOn() {
            // A promoted standby is shown while paused, before play(). Recompute
            // on playback changes instead of leaving that paused wake state set.
            if(videoView!=null)videoView.setKeepScreenOn(presented&&!prewarming&&!lifecyclePaused
                &&bounds!=null&&bounds.visible&&player!=null&&player.getPlayWhenReady()
                &&player.getPlaybackState()!=Player.STATE_ENDED);
        }

        // bringChildToFront reorders retained children; it never detaches the
        // TextureView or reconnects MediaCodec's Surface/BufferQueue.
        void raisePresented() {
            if(parent==null)return;
            View runtime=null;
            for(int i=0;i<parent.getChildCount();i++)if(parent.getChildAt(i) instanceof PodRuntimeView)runtime=parent.getChildAt(i);
            parent.bringChildToFront(background);
            parent.bringChildToFront(videoView);
            direct.placeAboveTexture();
            if(!geometryReady&&!geometryWaitExpired)parent.bringChildToFront(background);
            if(keyframeView!=null)parent.bringChildToFront(keyframeView);
            if(runtime!=null)parent.bringChildToFront(runtime);
            if(mediaPrefixCache!=null&&mediaPrefixCache.startupPreview!=null)mediaPrefixCache.startupPreview.raise();
        }

        void hideOffscreen() {
            direct.discard(true);
            if (videoView == null || background == null) return;
            if (dragFrom != null && (this == dragTo||this == dragFrom)) return;
            // During paging, even an asynchronously relatching standby must
            // contribute no pixels outside the selected pair.
            if(pagerBackground!=null&&pagerBackground.getVisibility()==View.VISIBLE){
                videoView.setVisibility(View.INVISIBLE);background.setVisibility(View.GONE);
                if(keyframeView!=null)keyframeView.setVisibility(View.GONE);
                videoView.setKeepScreenOn(false);return;
            }
            if(keyframeView!=null)keyframeView.setVisibility(View.GONE);
            // A decoded buffer submitted offscreen is not yet latched by
            // TextureView. Draw standby underneath the opaque active page so
            // adoption reveals an already-updated texture, not a poster.
            Session foreground=null;
            for(Session candidate:sessions.values())if(candidate!=this&&candidate.presented&&candidate.bounds!=null&&candidate.bounds.visible&&candidate.hasRenderedFirstFrame){foreground=candidate;break;}
            if(player!=null&&bounds!=null&&foreground!=null){
                videoView.setAlpha(1f);videoView.setVisibility(View.VISIBLE);background.setVisibility(View.GONE);
                setTranslation(bounds.translateXPx,bounds.translateYPx);
                // Keep decoding/latching underneath an opaque black pager base.
                // Alpha zero would prevent a cold neighbour becoming ready mid-drag.
                if(pagerBackground!=null&&pagerBackground.getVisibility()==View.VISIBLE){
                    parent.bringChildToFront(pagerBackground);
                    if(dragTo!=null)dragTo.raisePresented();
                }
                foreground.raisePresented();videoView.setKeepScreenOn(false);return;
            }
            int base = Math.max(parent == null ? 0 : parent.getHeight(), bounds == null ? 0 : bounds.heightPx);
            float offscreen = Math.max(base, Math.round(1024 * density)) * 2f;
            videoView.setAlpha(PREWARM_ALPHA);
            videoView.setVisibility(player == null ? View.GONE : View.VISIBLE);
            background.setVisibility(View.GONE);
            setTranslation(0, offscreen);
            videoView.setKeepScreenOn(false);
        }

        private void cancelFrameCompletion() {
            frameCompletionRevision++;
            if(frameCompletionCancel!=null){frameCompletionCancel.run();frameCompletionCancel=null;}
            if(frameCompletionRetry!=null){main.removeCallbacks(frameCompletionRetry);frameCompletionRetry=null;}
        }

        void finishDecodedFrame() {
            if(frameCompletionCancel!=null||!hasRenderedFirstFrame||!hasUpdatedFrame||player==null)return;
            if(prewarming){
                // A live decoded surface is ready independently of PixelCopy.
                // Optional readback must not time out or retire a usable decoder.
                if(!keyframeOnly)completePrepare();
                cacheKeyframe();return;
            }
            if(!hasCachedFrame())return;
            if(frameCompletionRetry!=null){main.removeCallbacks(frameCompletionRetry);frameCompletionRetry=null;}
            final long own=generation, attempt=++frameCompletionRevision;
            final TextureView expectedTexture=videoView;
            // As with geometry confirmation, await can finish before returning
            // its handle when the window is detached. Always clear it later.
            java.util.function.Consumer<Boolean> complete=committed->main.post(()->{
                if(disposed||generation!=own||videoView!=expectedTexture||frameCompletionRevision!=attempt)return;
                final long completedAttempt=++frameCompletionRevision;
                frameCompletionCancel=null;
                if(!committed){
                    frameCompletionRetry=()->{
                        if(disposed||generation!=own||videoView!=expectedTexture||frameCompletionRevision!=completedAttempt)return;
                        frameCompletionRetry=null;finishDecodedFrame();
                    };
                    main.postDelayed(frameCompletionRetry,50);return;
                }
                if(prewarming)cacheKeyframe();
                else if(hasCachedFrame()){
                    clearKeyframe();
                    PodBenchmark.swapStage(benchmarkAction,"live_frame_latched",generation);
                    if(frameObserver!=null)try{frameObserver.firstFrame(snapshot());}catch(Exception ignored){}
                    if(autoplay&&mayPlay())player.play();
                }
            });
            try{frameCompletionCancel=handoffPaint.await(expectedTexture,complete);}
            catch(RuntimeException unavailable){complete.accept(false);}
        }
        boolean hasCachedFrame() { return cachedFrame != null; }
        void cacheKeyframe() {
            if (!prewarming || !hasRenderedFirstFrame || !hasUpdatedFrame || frameBinding == null) return;
            final long own=generation, attempt=++frameCompletionRevision;
            final TextureView expectedTexture=videoView;
            frameCompletionCancel=()->{}; // Fences duplicate texture callbacks during readback.
            androidx.media3.common.VideoSize size=player.getVideoSize();
            keyframes.capture(frameBinding,expectedTexture,size.width,size.height,main,()->!disposed&&generation==own&&videoView==expectedTexture&&frameCompletionRevision==attempt,frame->{
                if(disposed||generation!=own||videoView!=expectedTexture||frameCompletionRevision!=attempt)return;
                frameCompletionRevision++;frameCompletionCancel=null;
                if(frame==null&&keyframeOnly){failPrepare(this,player,own,"prepare_failed","keyframe_copy_failed",false);return;}
                if(frame!=null)frame.pixelAspectRatio=size.pixelWidthHeightRatio;
                completePrepare();
            });
        }
        void revealKeyframe() {
            ViewGroup host=parent;
            // Prefix decoding may finish after a budget denial leaves the
            // slot without views. Wait for an attached view before pinning.
            if(hasCachedFrame()||host==null||videoView==null||videoView.getParent()!=host)return;
            ViewGroup.LayoutParams layout=videoView.getLayoutParams();
            if(!(layout instanceof FrameLayout.LayoutParams))return;
            PodVideoKeyframes.Frame frame=keyframes.acquire(frameBinding);
            if(frame==null)return;
            cachedFrame=frame;
            keyframeView=new android.widget.ImageView(context);
            keyframeView.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
            keyframeView.setImageBitmap(frame.pixels);
            keyframeView.setClickable(false);keyframeView.setFocusable(false);
            host.addView(keyframeView,new FrameLayout.LayoutParams((FrameLayout.LayoutParams)layout));
            // A prefix frame can arrive after native pointer tracking began.
            // Keep the existing page offset instead of snapping both layers to
            // the untransformed bounds while the finger is still down.
            setTranslation(videoView.getTranslationX(),videoView.getTranslationY());
            fitKeyframe();if(prewarming)hideOffscreen();else showPresented();
            if(benchmarkAction!=null){
                long own=generation;
                PodBenchmark.VideoAction action=new PodBenchmark.VideoAction(benchmarkAction.seq,benchmarkAction.generation,own,benchmarkAction.delta);
                benchmarkFramePending=false;
                PodBenchmark.watchVideo(keyframeView,action,()->generation==own&&hasCachedFrame()&&presented&&!prewarming,true);
            }
            // Push the decoded-cache receipt separately from live decoder state.
            try{if(prepareCallback!=null)prepareCallback.firstFrame(snapshot());}catch(Exception ignored){}
        }
        void clearKeyframe() {
            if(keyframeView!=null){
                PodBenchmark.cancelVideo(keyframeView);
                keyframeView.setImageDrawable(null);
                if(keyframeView.getParent() instanceof ViewGroup)((ViewGroup)keyframeView.getParent()).removeView(keyframeView);
                keyframeView=null;
            }
            if(cachedFrame!=null){keyframes.unpin(cachedFrame);cachedFrame=null;}
        }

        void recordPresentationTransform() throws Exception {
            if (frameSource == null) return;
            frameSource.put("transform", new JSONObject().put("scaleMode", scaleMode)
                    .put("rotationDeg", rotationDeg).put("scaleRotationDeg", scaleRotationDeg)
                    .put("panX", panX).put("panY", panY));
        }

        boolean readTransform(JSONObject args) throws Exception {
            if(args==null)return false;
            String mode=args.optString("scaleMode","standard");
            if(!mode.equals("standard")&&!mode.equals("expanded")&&!mode.equals("shrunk"))throw new IllegalArgumentException("invalid_scale_mode");
            float rotation=finite(args,"rotationDeg",0,-100000,100000);
            float x=finite(args,"panX",0,-4096,4096),y=finite(args,"panY",0,-4096,4096);
            float scaleRotation=finite(args,"scaleRotationDeg",rotation,-100000,100000);
            rotation=Math.round(rotation*100f)/100f;scaleRotation=Math.round(scaleRotation*100f)/100f;
            if(mode.equals(scaleMode)&&rotationDeg==rotation&&scaleRotationDeg==scaleRotation&&panX==x&&panY==y)return false;
            scaleMode=mode;rotationDeg=rotation;scaleRotationDeg=scaleRotation;panX=x;panY=y;
            return true;
        }

        void fitKeyframe() {
            if(keyframeView==null||cachedFrame==null||bounds==null)return;
            android.graphics.drawable.Drawable image=keyframeView.getDrawable();
            if(image==null)return;
            android.graphics.Matrix geometry=keyframeGeometry.get(bounds.widthPx,bounds.heightPx,
                    cachedFrame.pixels.getWidth()*cachedFrame.pixelAspectRatio,cachedFrame.pixels.getHeight(),
                    rotationDeg,scaleRotationDeg,scaleMode,Math.round(panX*density),Math.round(panY*density));
            if(transformedKeyframe==keyframeView&&appliedKeyframeMatrix==geometry)return;
            transformedKeyframe=keyframeView;appliedKeyframeMatrix=geometry;
            android.graphics.Matrix transform=new android.graphics.Matrix(geometry);
            transform.preScale(bounds.widthPx/(float)image.getIntrinsicWidth(),bounds.heightPx/(float)image.getIntrinsicHeight());
            keyframeView.setImageMatrix(transform);
        }
        void updateOutput() {
            boolean steady = !disposed && !lifecyclePaused && visibleDecodedTexture() && geometryReady
                    && !hasCachedFrame() && dragFrom == null && dragTo == null && !bounds.pagerMoving;
            direct.update(player, videoView, appliedVideoMatrix, steady, generation);
        }

        void fitVideo() {
            fitKeyframe();
            if (player == null || videoView == null || bounds == null) return;
            androidx.media3.common.VideoSize size = player.getVideoSize();
            if (size.width <= 0 || size.height <= 0 || bounds.widthPx <= 0 || bounds.heightPx <= 0) return;
            float width = size.width * size.pixelWidthHeightRatio, height = size.height;
            android.graphics.Matrix matrix=liveGeometry.get(bounds.widthPx,bounds.heightPx,width,height,rotationDeg,scaleRotationDeg,scaleMode,Math.round(panX*density),Math.round(panY*density));
            if(transformedVideo==videoView&&appliedVideoMatrix==matrix)return;
            transformedVideo=videoView;appliedVideoMatrix=matrix;
            videoView.setTransform(matrix);
            if (direct.hasLayer()) updateOutput();
            // setTransform only invalidates the parent. A paused retained page
            // also needs its own display list rebuilt before it enters a drag.
            videoView.invalidate();
        }

        JSONObject snapshot() throws Exception {
            // The guest may read ENDED and replace a slot before its listener
            // receipt is observed. Record the same real native state here.
            if(!prewarming&&analytics!=null&&player!=null&&player.getPlaybackState()==Player.STATE_ENDED)analytics.completed(generation,player.getCurrentPosition(),player.getDuration());
            return PodVideoSystemAudio.snapshot(context,audioPolicy,new JSONObject()
                    .put("appliedVolume",player==null?null:player.getVolume()).put("audioTrackSelected",player!=null&&player.getAudioFormat()!=null).put("metrics",analytics==null?null:analytics.snapshot()).put("ok", true).put("playWhenReady",player!=null&&player.getPlayWhenReady()).put("nativePlaybackState",player==null?0:player.getPlaybackState()).put("playbackSuppressionReason",player==null?0:player.getPlaybackSuppressionReason()).put("loading",player!=null&&player.isLoading()).put("isBuffering",player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING).put("sessionId", id).put("decoderAdmissionDeferred",admissionDeferred).put("state", state).put("errorCode", "error".equals(state) ? errorCode : null).put("mirrorIndex", "error".equals(state)&&PodVideoShortEdge.ERROR.equals(errorCode)?errorMirrorIndex:null)
                    .put("positionMs", player == null ? 0 : player.getCurrentPosition())
                    .put("bufferedMs",player==null?0:Math.max(0,player.getBufferedPosition()-player.getCurrentPosition()))
                    .put("networkStalled",analytics!=null&&analytics.networkStalled())
                    .put("durationMs", player == null ? 0 : Math.max(0, player.getDuration()))
                    .put("width", player == null ? 0 : player.getVideoSize().width)
                    .put("height", player == null ? 0 : player.getVideoSize().height)

                    .put("prewarming", prewarming)
                    .put("presented", presented)
                    .put("hasCachedFirstFrame",hasCachedFrame()).put("keyframeAvailable",frameBinding!=null&&keyframes.get(frameBinding)!=null)
                    .put("geometryReady",geometryReady).put("geometryWaitExpired",geometryWaitExpired)
                    .put("frameHandoffPending",!prewarming&&hasCachedFrame()&&(frameCompletionCancel!=null||frameCompletionRetry!=null))
                    .put("hasRenderedFirstFrame", hasRenderedFirstFrame)
                    .put("sourceGeneration",generation).put("completedLoops", completedLoops).put("loop", player!=null && player.getRepeatMode()==Player.REPEAT_MODE_ONE).put("speed", player==null?1:player.getPlaybackParameters().speed)
                    .put("targetBufferBytes", keyframeOnly?256*1024:targetBufferBytes()));
        }

        @Override public void close() {
            if (disposed) return;
            if(!prewarming&&analytics!=null&&player!=null){
                if(player.getPlaybackState()==Player.STATE_ENDED)analytics.completed(generation,player.getCurrentPosition(),player.getDuration());
                analytics.retired(generation,player.getCurrentPosition(),player.getDuration(),player.getPlaybackState()==Player.STATE_ENDED);
            }
            if(!prewarming&&presented)mediaGate(false);
            disposed = true;
            shellReleased++;
            Callback pending = takePrepareCallback();
            resetPlayer();
            if (pending != null) pending.fail("cancelled", "Video session released");
            if (videoView != null && videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
            if (background != null && background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
            videoView = null; background = null; bounds = null; profile = null;
            PodMediaMemoryAccounting.close(surfaceCharge);surfaceCharge=null;
        }
    }

    private static final class Bounds {
        final int xPx, yPx, widthPx, heightPx;
        final float translateXPx, translateYPx, routeXPx, routeYPx, floorYPx;
        final float clipCxPx, clipCyPx, clipRadiusPx;
        final boolean visible, pagerMoving;

        private Bounds(int xPx, int yPx, int widthPx, int heightPx, float translateXPx, float translateYPx,
                       float clipCxPx, float clipCyPx, float clipRadiusPx, boolean visible,
                       boolean hasPagerOffset, float pagerXPx, float pagerYPx) {
            this.xPx = xPx; this.yPx = yPx; this.widthPx = widthPx; this.heightPx = heightPx;
            this.translateXPx = translateXPx; this.translateYPx = translateYPx;
            routeXPx = translateXPx - pagerXPx;
            routeYPx = translateYPx - pagerYPx;
            floorYPx = hasPagerOffset ? routeYPx : 0;
            pagerMoving = hasPagerOffset ? pagerXPx != 0 || pagerYPx != 0 : translateYPx != 0;
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
            JSONObject offset = object.optJSONObject("pagerOffset");
            float pagerX = offset == null ? 0 : bounded(offset, "x", 0, -4096, 4096);
            float pagerY = offset == null ? 0 : bounded(offset, "y", 0, -4096, 4096);
            return new Bounds(Math.round(x * density), Math.round(y * density), Math.round(width * density), Math.round(height * density),
                    tx * density, ty * density, cx * density, cy * density, radius * density, object.optBoolean("visible", true),
                    offset != null, pagerX * density, pagerY * density);
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
        unsubscribeMemory.run();
        context.unregisterComponentCallbacks(this);
            mediaGate(false);
        main.post(() -> {
            endDrag();
            if(pagerBackground!=null&&pagerBackground.getParent() instanceof ViewGroup)((ViewGroup)pagerBackground.getParent()).removeView(pagerBackground);
            for (Session session : sessions.values()) session.close();
            sessions.clear();PodBenchmark.videoReleaseSource(null);if(prefixFrames!=null)prefixFrames.close();keyframes.close(); parent = null;
        });
    }
}
