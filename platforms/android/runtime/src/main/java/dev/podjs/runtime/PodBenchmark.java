package dev.podjs.runtime;

import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Closed, unconditional release observations. No service queue or arbitrary text sink.
 * All times are event-time uptime, not elapsedRealtime or a caller supplied clock. */
final class PodBenchmark {
    private static final long MAX=9_007_199_254_740_991L;
    private static long sequence;
    private static WeakReference<View> surface=new WeakReference<>(null);
    private static final Set<String> CODES=new HashSet<>(Arrays.asList(("swap_stage media_variant prepare_stage startup_stage home_first_frame douyin_request entry_start home_entry first_card refresh_start refresh_done player_visible gesture_down gesture_release player_paused previous_audio_stopped incoming_first_frame spinner_shown snap_back fling_done mute_changed player_audio_applied mixer_match login_entry login_tap qr_ready scan_accepted auth_persisted logout_start logout_feedback auth_cleared auth_state needs_login refreshing restore_state renderer_process prewarm_requested prewarm_ready prewarm_evicted prewarm_policy background_gate error_page error").split(" ")));
    private static final Set<String> NUMBERS=new HashSet<>(Arrays.asList("seq","attempt","delta","generation","source_generation","pid","active_tracks","buffered_ms","slots","http_status","retry_after_ms"));
    private static final Set<String> AUTH=new HashSet<>(Arrays.asList("login_entry","login_tap","qr_ready","scan_accepted","auth_persisted","renderer_process"));
    private static final Set<String> PRESENTED=new HashSet<>(Arrays.asList("home_first_frame","first_card","refresh_done","qr_ready","logout_feedback","needs_login","refreshing","spinner_shown"));
    private static final Set<String> PROOF=new HashSet<>(Arrays.asList("incoming_first_frame","snap_back","previous_audio_stopped","player_paused","player_audio_applied","mixer_match","fling_done"));
    private static final LinkedHashMap<Long,Set<String>> actions=new LinkedHashMap<>();
    private static java.util.function.LongConsumer firstCardStats=seq->{};
    private static long startupSequence;
    static void attach(View view){attach(view,seq->{});}
    static void attach(View view,java.util.function.LongConsumer stats){firstCardStats=stats;surface=new WeakReference<>(view);if(view!=null&&startupSequence==0)try{startupSequence=begin("startup_stage",new JSONObject().put("startup_stage","process_start"),android.os.Process.getStartUptimeMillis());}catch(Exception ignored){}}
    static void startup(long seq,String stage){if(seq>0)try{mark("startup_stage",seq,new JSONObject().put("startup_stage",stage));}catch(Exception ignored){}}

    static long begin(String code,JSONObject fields){return begin(code,fields,SystemClock.uptimeMillis());}
    /** Native observers can preserve an uptime captured before applying policy. Guest cannot set it. */
    static synchronized long begin(String code,JSONObject fields,long now){
        if(now<0||now>SystemClock.uptimeMillis())return 0;sequence=Math.max(sequence+1,now*1024);
        if(sequence<1||sequence>MAX)return 0;
        try {JSONObject args=new JSONObject(fields==null?"{}":fields.toString()).put("code",code).put("seq",sequence);actions.put(sequence,new HashSet<>());while(actions.size()>1024){Long old=actions.keySet().iterator().next();actions.remove(old);presentationVersions.keySet().removeIf(key->key.startsWith(old+":"));videos.remove(old);confirmedVideo.remove(old);confirmedVideoTime.remove(old);}return emit(args,now)?sequence:0;}catch(Exception ignored){return 0;}
    }
    static long mark(String code,long seq,JSONObject fields){try{return emit(new JSONObject(fields==null?"{}":fields.toString()).put("code",code).put("seq",seq),SystemClock.uptimeMillis())?seq:0;}catch(Exception ignored){return 0;}}
    private static final Map<String,Long> presentationVersions=new HashMap<>();
    /** Called synchronously from QuickJS through JNI; no asynchronous log request. */
    public static long dispatch(String line){
        if(line==null||line.length()>1024)return 0;
        try {
            JSONObject args=new JSONObject(line);
            if(args.has("video_frame")){if(args.length()!=2)return 0;long seq=integer(args,"video_frame"),generation=integer(args,"generation");synchronized(PodBenchmark.class){if(!known(seq))return 0;confirmedVideo.put(seq,generation);confirmedVideoTime.put(seq,SystemClock.uptimeMillis());VideoObservation observed=videos.get(seq);if(observed!=null)swapStage(observed.action,"ui_confirm",observed.action.sourceGeneration,confirmedVideoTime.get(seq));tryVideo(seq);}return seq;}
            String code=args.optString("code","");
            if(PROOF.contains(code)||code.equals("swap_stage")||code.equals("prepare_stage"))return 0; // Mixer/target-surface proof cannot originate in guest state.
            if(args.optBoolean("cancel_presented",false)){args.remove("cancel_presented");if(args.length()!=2||!CODES.contains(code))return 0;long seq=integer(args,"seq");synchronized(PodBenchmark.class){String key=seq+":"+code;presentationVersions.put(key,presentationVersions.getOrDefault(key,0L)+1);}return seq;}
            boolean presented=args.optBoolean("presented",false);args.remove("presented");
            if(presented&&!PRESENTED.contains(code))return 0;
            long seq;
            if(args.has("seq")){seq=integer(args,"seq");if(!known(seq))return 0;}
            else {if(presented)return 0;args.remove("code");return begin(code,args);}
            if(presented){View view=surface.get();String key=seq+":"+code;long version; synchronized(PodBenchmark.class){version=presentationVersions.getOrDefault(key,0L)+1;presentationVersions.put(key,version);}return present(view,args,()->presentationVersions.getOrDefault(key,0L)==version)?seq:0;}
            return emit(args,SystemClock.uptimeMillis())?seq:0;
        }catch(Exception ignored){return 0;}
    }
    static synchronized boolean known(long seq){return actions.containsKey(seq);}
    static long integer(JSONObject args,String key)throws Exception {
        Object value=args.get(key);if(!(value instanceof Number))throw new IllegalArgumentException();
        double n=((Number)value).doubleValue();long whole=((Number)value).longValue();
        if(!Double.isFinite(n)||n!=whole||whole<0||whole>MAX)throw new IllegalArgumentException();return whole;
    }
    static synchronized boolean emit(JSONObject args,long now){
        try{
            String code=args.getString("code");if(!CODES.contains(code)||now<0)return false;
            long seq=integer(args,"seq");if(!known(seq))return false;
            if(AUTH.contains(code)&&(!args.has("attempt")||integer(args,"attempt")==0))return false;
            if(code.equals("douyin_request")){
                for(String key:Arrays.asList("category","http_status","douyin_status_code","challenge","signing_ok","cookie_present","retry_after_ms","error_code"))if(!args.has(key))return false;
                if(args.length()!=10||integer(args,"http_status")>599)return false;
            }
            if(code.equals("player_visible")&&args.optString("state").equals("present")){
                View view=surface.get();
                if(view==null||!view.isShown())args.put("state","absent").put("visibility_reason","native_hidden");
                else if(!view.hasWindowFocus())args.put("state","absent").put("visibility_reason","native_unfocused");
            }
            StringBuilder line=new StringBuilder("code=").append(code).append(" seq=").append(seq).append(" mono_ms=").append(now);
            TreeSet<String> keys=new TreeSet<>();args.keys().forEachRemaining(keys::add);
            for(String key:keys){
                if(key.equals("code")||key.equals("seq"))continue;
                Object value=args.get(key);
                if(NUMBERS.contains(key)){long n=integer(args,key);if(code.equals("startup_stage")&&key.equals("slots")&&(!args.optString("startup_stage").equals("stock_saved")||n>3))return false;if((key.equals("delta")&&n>2)||(key.equals("pid")&&n>4_194_304)||(key.equals("active_tracks")&&n>4096))return false;}
                else if(Arrays.asList("variant_index","variant_count","width","height","bitrate","gear").contains(key)){if(!code.equals("media_variant")||integer(args,key)>MAX)return false;}
                else if(key.equals("variant_outlet")){if(!code.equals("media_variant")||!Arrays.asList("feed","detail","foreground").contains(value))return false;}
                else if(key.equals("codec")){if(!code.equals("media_variant")||!Arrays.asList("h264","h265","unknown").contains(value))return false;}
                else if(key.equals("selected")){if(!code.equals("media_variant")||!(value instanceof Boolean))return false;}
                else if(key.equals("swap_stage")){if(!code.equals("swap_stage")||!Arrays.asList("promote_enter","surface_promoted","play_command","texture_updated","rendered_first_frame","ui_confirm","observer_armed","window_commit_1","window_commit_2").contains(value))return false;}
                else if(key.equals("prepare_stage")){if(!code.equals("prepare_stage")||!Arrays.asList("start","dispatched","network_open","network_start","network_first_byte","network_end","decoder_configured","format_ready","first_frame","timeout","cancel","adopt","retire","release").contains(value))return false;}
                else if((key.equals("network_bytes")||key.equals("position_bytes")||key.equals("cache_network_bytes")||key.equals("cache_range_requests")||key.equals("cache_bytes")||key.equals("cache_entry")||key.equals("range_requests")||key.equals("ttfb_ms")||key.equals("steady_ms"))){if(!code.equals("prepare_stage")||integer(args,key)>MAX)return false;}
                else if(key.equals("surface_ready")){if(!code.equals("prepare_stage")||!(value instanceof Boolean))return false;}
                else if(key.equals("playback_state")){if(!code.equals("prepare_stage")||integer(args,key)>4)return false;}
                else if(key.equals("startup_stage")){if(!code.equals("startup_stage")||!Arrays.asList("stock_saved","process_start","home_mounted","home_tap","entry_mounted","play_tap","headers_ready","preview_start","preview_ready","source_ready","persist_start","persist_done","backdrop_start","backdrop_done","player_mounted","prepare_start","prepare_admitted","player_constructed","prepare_dispatched","video_format_ready","decoder_configured","decoded_first_frame","initial_frame_adopted").contains(value))return false;}
                else if(key.equals("duration_ms")){if(!(code.equals("startup_stage")||code.equals("prepare_stage"))||integer(args,key)>600000)return false;}
                else if(key.equals("visibility_reason")){if(!code.equals("player_visible")||!Arrays.asList("ready","inactive","route_offset","lifecycle","player_error","network_prompt","native_hidden","native_unfocused").contains(value))return false;}
                else if(key.equals("player_state")){if(!code.equals("player_visible")||!Arrays.asList("idle","preparing","buffering","ready","playing","paused","ended","stopped","error","unknown").contains(value))return false;}
                else if(key.equals("system_index")||key.equals("system_max")){if(!code.equals("player_visible")||integer(args,key)>1024)return false;}
                else if(key.equals("system_muted")){if(!code.equals("player_visible")||!(value instanceof Boolean))return false;}
                else if(key.equals("audio_gain_milli")){if(!code.equals("player_visible")||integer(args,key)>1000)return false;}
                else if(key.equals("audio_track")){if(!code.equals("player_visible")||!(value instanceof Boolean))return false;}
                else if(key.equals("has_frame")){if(!code.equals("player_visible")||!(value instanceof Boolean))return false;}
                else if(key.equals("offset_abs_milli")){if(!code.equals("player_visible")||integer(args,key)>1000000000L)return false;}
                else if(PodVideoFailure.field(key)){if(!(code.equals("error")||code.equals("error_page")||code.equals("prepare_stage"))||!PodVideoFailure.accept(key,value))return false;}
                else if(key.equals("douyin_status_code")){if(!code.equals("douyin_request")||!(value instanceof Number))return false;double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=((Number)value).longValue()||n < -1||n>Integer.MAX_VALUE)return false;}
                else if(key.equals("category")){if(!code.equals("douyin_request")||!Arrays.asList("feed","detail","auth_probe","media").contains(value))return false;}
                else if(key.equals("error_code")){if(!code.equals("douyin_request")||!Arrays.asList("success","forbidden","http","signing","challenge","empty","blocked","api_status","timeout","cancelled","network","cooldown","client_limit","decode").contains(value))return false;}
                else if(key.equals("challenge")||key.equals("signing_ok")||key.equals("cookie_present")){if(!code.equals("douyin_request")||!(value instanceof Boolean))return false;}
                else if(key.equals("muted")||key.equals("prewarmed")||key.equals("network_stalled")){if(!(value instanceof Boolean))return false;}
                else if(key.equals("direction")){if(!Arrays.asList("next","prev","cancel","fling").contains(value))return false;}
                else if(key.equals("state")){if(!Arrays.asList("present","absent","running","idle","unknown","paused").contains(value))return false;}
                else if(key.equals("kind")){if(!Arrays.asList("on","off").contains(value))return false;}
                else if(key.equals("reason")){if(!Arrays.asList("stale","capacity","release","failure","bidirectional","single_neighbour","foreground_media","media_stopped","timeout","candidate_retry","exhausted","hidden","network_stalled","boundary","network_prompt","threshold","cancelled").contains(value))return false;}
                else if(key.equals("backend")){if(!Arrays.asList("mixer","player").contains(value))return false;}
                else return false;
                if(code.equals("player_visible")&&key.equals("state")&&value.equals("present")){
                    View view=surface.get();if(view==null||!view.isShown()||!view.hasWindowFocus())value="absent";
                }
                line.append(' ').append(key).append('=').append(value);
            }
            if((code.equals("incoming_first_frame")||code.equals("snap_back"))&&!args.has("generation"))return false;
            Set<String> seen=actions.get(seq);
            if(PROOF.contains(code)||PRESENTED.contains(code)||code.equals("gesture_release"))if(!seen.add(code))return false;
            Log.i("DYBENCH",line.toString());return true;
        }catch(Exception ignored){return false;}
    }
    /** A hardware frame commit of a visible bound target, never a hidden prewarm callback. */
    static boolean present(View view,JSONObject event,BooleanSupplier guard){
        if(view==null||!view.isAttachedToWindow()||!view.isHardwareAccelerated()||!view.getViewTreeObserver().isAlive())return false;
        view.getViewTreeObserver().registerFrameCommitCallback(()->{
            if(view.isAttachedToWindow()&&view.isShown()&&view.hasWindowFocus()&&view.getAlpha()>0&&guard.getAsBoolean()){
                if(event.optString("code").equals("first_card")&&surface.get()==view)try{firstCardStats.accept(event.optLong("seq",0));}catch(Exception ignored){}
                emit(event,SystemClock.uptimeMillis());
            }
        });
        view.invalidate();return true;
    }
    private static final Map<Long,Long> confirmedVideo=new HashMap<>();
    private static final Map<Long,Long> confirmedVideoTime=new HashMap<>();
    private static final Map<Long,VideoObservation> videos=new HashMap<>();
    private static final Map<Long,VideoObservation> committingVideos=new HashMap<>();
    private static final class VideoObservation {
        final View view;
        final VideoAction action;
        final BooleanSupplier guard;
        final boolean retainedFrame;
        Runnable cancelCommit;
        VideoObservation(View view,VideoAction action,BooleanSupplier guard,boolean retainedFrame){this.view=view;this.action=action;this.guard=guard;this.retainedFrame=retainedFrame;}
    }
    /** A missing guest acknowledgement cannot outlive the surface generation. */
    static synchronized void cancelVideo(View view){
        if(view==null)return;
        Iterator<Map.Entry<Long,VideoObservation>> pending=videos.entrySet().iterator();
        while(pending.hasNext()){
            Map.Entry<Long,VideoObservation> entry=pending.next();
            if(entry.getValue().view==view){confirmedVideo.remove(entry.getKey());confirmedVideoTime.remove(entry.getKey());pending.remove();}
        }
        Iterator<Map.Entry<Long,VideoObservation>> committing=committingVideos.entrySet().iterator();
        while(committing.hasNext()){
            VideoObservation observation=committing.next().getValue();
            if(observation.view==view){committing.remove();if(observation.cancelCommit!=null)observation.cancelCommit.run();}
        }
    }
    static void watchVideo(View view,VideoAction action,BooleanSupplier guard){ watchVideo(view,action,guard,false); }
    static synchronized void watchVideo(View view,VideoAction action,BooleanSupplier guard,boolean promoted){
        // Each surface owns only its latest observation. A newer generation
        // invalidates the old guard even if the guest never acknowledged it.
        Long confirmation=confirmedVideo.get(action.seq),confirmationTime=confirmedVideoTime.get(action.seq);
        cancelVideo(view);
        if(confirmation!=null){confirmedVideo.put(action.seq,confirmation);if(confirmationTime!=null){confirmedVideoTime.put(action.seq,confirmationTime);swapStage(action,"ui_confirm",action.sourceGeneration,confirmationTime);}}
        videos.put(action.seq,new VideoObservation(view,action,guard,promoted));
        tryVideo(action.seq);
    }
    private static synchronized void tryVideo(long seq){
        VideoObservation ready=videos.get(seq);
        if(ready!=null&&Objects.equals(confirmedVideo.get(seq),ready.action.generation)){
            videos.remove(seq);confirmedVideo.remove(seq);confirmedVideoTime.remove(seq);
            View view=ready.view;VideoAction action=ready.action;BooleanSupplier guard=ready.guard;
            // A retained, already-latched decoded texture becomes visible in its
            // first native window commit. Fresh surfaces still wait for two
            // UI commits after cover removal. A timeout is never success.
            swapStage(action,"observer_armed",action.sourceGeneration);
            committingVideos.put(seq,ready);
            final long[] commitAt={-1};
            ready.cancelCommit=PodAfterPaint.await(ready.retainedFrame||surface.get()==null?view:surface.get(),new Handler(Looper.getMainLooper()),ready.retainedFrame?1:2,(frame,at)->{commitAt[0]=at;swapStage(action,frame==1?"window_commit_1":"window_commit_2",action.sourceGeneration,at);},committed->{
                synchronized(PodBenchmark.class){if(committingVideos.get(seq)!=ready)return;committingVideos.remove(seq);}
                if(committed&&view.isAttachedToWindow()&&view.isShown()&&view.hasWindowFocus()&&view.getAlpha()>0&&guard.getAsBoolean()){emit(action.event("incoming_first_frame"),commitAt[0]);if(action.delta==2)try{emit(action.event("fling_done").put("delta",2),commitAt[0]);}catch(Exception ignored){}}
                else emit(action.event("error"),SystemClock.uptimeMillis());
            });
        }
    }
    /** Player-state diagnostic only; never mixer/PCM silence proof. */
    static void playerPaused(JSONObject args,boolean playWhenReady,long sourceGeneration){
        VideoAction action=videoAction(args);
        if(action==null||playWhenReady||action.sourceGeneration!=sourceGeneration)return;
        try{emit(action.event("player_paused").put("backend","player"),SystemClock.uptimeMillis());}catch(Exception ignored){}
    }
    static void swapStage(VideoAction action,String stage,long sourceGeneration){swapStage(action,stage,sourceGeneration,SystemClock.uptimeMillis());}
    static void swapStage(VideoAction action,String stage,long sourceGeneration,long at){if(action!=null&&at<=SystemClock.uptimeMillis())try{emit(action.event("swap_stage").put("swap_stage",stage).put("source_generation",sourceGeneration),at);}catch(Exception ignored){}}
    static final class VideoAction {
        final long seq,generation,sourceGeneration;
        final int delta;
        VideoAction(long seq,long generation,long sourceGeneration){this(seq,generation,sourceGeneration,1);}
        VideoAction(long seq,long generation,long sourceGeneration,int delta){this.seq=seq;this.generation=generation;this.sourceGeneration=sourceGeneration;this.delta=delta;}
        JSONObject event(String code){try{return new JSONObject().put("code",code).put("seq",seq).put("generation",generation).put("source_generation",sourceGeneration);}catch(Exception ignored){return new JSONObject();}}
    }
    static VideoAction videoAction(JSONObject args){
        try{JSONObject value=args.optJSONObject("benchmark");if(value==null)return null;long seq=integer(value,"seq"),generation=integer(value,"generation"),source=integer(value,"source_generation");long delta=value.has("delta")?integer(value,"delta"):1;return known(seq)&&(delta==1||delta==2)?new VideoAction(seq,generation,source,(int)delta):null;}catch(Exception ignored){return null;}
    }
    // No previous_audio_stopped/mixer_match producer: an audited mixer/PCM adapter is required.
}
