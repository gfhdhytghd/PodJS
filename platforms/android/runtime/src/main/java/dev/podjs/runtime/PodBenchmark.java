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
    private static final Set<String> CODES=new HashSet<>(Arrays.asList(("entry_start home_entry first_card refresh_start refresh_done player_visible gesture_down gesture_release previous_audio_stopped incoming_first_frame spinner_shown snap_back fling_done mute_changed player_audio_applied mixer_match login_entry login_tap qr_ready scan_accepted auth_persisted logout_start logout_feedback auth_cleared auth_state needs_login refreshing restore_state renderer_process prewarm_requested prewarm_ready prewarm_evicted prewarm_policy background_gate error_page error").split(" ")));
    private static final Set<String> NUMBERS=new HashSet<>(Arrays.asList("seq","attempt","delta","generation","source_generation","pid","active_tracks","buffered_ms","slots"));
    private static final Set<String> AUTH=new HashSet<>(Arrays.asList("login_entry","login_tap","qr_ready","scan_accepted","auth_persisted","renderer_process"));
    private static final Set<String> PRESENTED=new HashSet<>(Arrays.asList("first_card","refresh_done","qr_ready","logout_feedback","needs_login","refreshing","spinner_shown"));
    private static final Set<String> PROOF=new HashSet<>(Arrays.asList("incoming_first_frame","snap_back","previous_audio_stopped","player_audio_applied","mixer_match","fling_done"));
    private static final LinkedHashMap<Long,Set<String>> actions=new LinkedHashMap<>();
    static void attach(View view){surface=new WeakReference<>(view);}
    static long begin(String code,JSONObject fields){return begin(code,fields,SystemClock.uptimeMillis());}
    /** Native observers can preserve an uptime captured before applying policy. Guest cannot set it. */
    static synchronized long begin(String code,JSONObject fields,long now){
        if(now<0||now>SystemClock.uptimeMillis())return 0;sequence=Math.max(sequence+1,now*1024);
        if(sequence<1||sequence>MAX)return 0;
        try {JSONObject args=new JSONObject(fields==null?"{}":fields.toString()).put("code",code).put("seq",sequence);actions.put(sequence,new HashSet<>());while(actions.size()>1024){Long old=actions.keySet().iterator().next();actions.remove(old);presentationVersions.keySet().removeIf(key->key.startsWith(old+":"));videos.remove(old);confirmedVideo.remove(old);}return emit(args,now)?sequence:0;}catch(Exception ignored){return 0;}
    }
    static long mark(String code,long seq,JSONObject fields){try{return emit(new JSONObject(fields==null?"{}":fields.toString()).put("code",code).put("seq",seq),SystemClock.uptimeMillis())?seq:0;}catch(Exception ignored){return 0;}}
    private static final Map<String,Long> presentationVersions=new HashMap<>();
    /** Called synchronously from QuickJS through JNI; no asynchronous log request. */
    public static long dispatch(String line){
        if(line==null||line.length()>1024)return 0;
        try {
            JSONObject args=new JSONObject(line);
            if(args.has("video_frame")){if(args.length()!=2)return 0;long seq=integer(args,"video_frame"),generation=integer(args,"generation");if(!known(seq))return 0;confirmedVideo.put(seq,generation);tryVideo(seq);return seq;}
            String code=args.optString("code","");
            if(PROOF.contains(code))return 0; // Mixer/target-surface proof cannot originate in guest state.
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
            StringBuilder line=new StringBuilder("code=").append(code).append(" seq=").append(seq).append(" mono_ms=").append(now);
            TreeSet<String> keys=new TreeSet<>();args.keys().forEachRemaining(keys::add);
            for(String key:keys){
                if(key.equals("code")||key.equals("seq"))continue;
                Object value=args.get(key);
                if(NUMBERS.contains(key)){long n=integer(args,key);if((key.equals("delta")&&n>2)||(key.equals("pid")&&n>4_194_304)||(key.equals("active_tracks")&&n>4096))return false;}
                else if(key.equals("muted")||key.equals("prewarmed")){if(!(value instanceof Boolean))return false;}
                else if(key.equals("direction")){if(!Arrays.asList("next","prev","cancel","fling").contains(value))return false;}
                else if(key.equals("state")){if(!Arrays.asList("present","absent","running","idle","unknown","paused").contains(value))return false;}
                else if(key.equals("kind")){if(!Arrays.asList("on","off").contains(value))return false;}
                else if(key.equals("reason")){if(!Arrays.asList("stale","capacity","release","failure","bidirectional","single_neighbour","foreground_media","media_stopped","timeout","candidate_retry","exhausted","hidden").contains(value))return false;}
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
            if(view.isAttachedToWindow()&&view.isShown()&&view.hasWindowFocus()&&view.getAlpha()>0&&guard.getAsBoolean())emit(event,SystemClock.uptimeMillis());
        });
        view.invalidate();return true;
    }
    private static final Map<Long,Long> confirmedVideo=new HashMap<>();
    private static final Map<Long,Runnable> videos=new HashMap<>();
    static void watchVideo(View view,VideoAction action,BooleanSupplier guard){ watchVideo(view,action,guard,false); }
    static void watchVideo(View view,VideoAction action,BooleanSupplier guard,boolean promoted){
        videos.put(action.seq,()->{
            if(!Objects.equals(confirmedVideo.get(action.seq),action.generation))return;
            // JS confirms its cover is gone; native proves the bound decoded
            // target survives two window commits. A timeout is never success.
            PodAfterPaint.await(surface.get()==null?view:surface.get(),new Handler(Looper.getMainLooper()),committed->{
                if(committed&&view.isAttachedToWindow()&&view.isShown()&&view.hasWindowFocus()&&view.getAlpha()>0&&guard.getAsBoolean()){emit(action.event("incoming_first_frame"),SystemClock.uptimeMillis());if(promoted)try{emit(action.event("fling_done").put("delta",1),SystemClock.uptimeMillis());}catch(Exception ignored){}}
                else emit(action.event("error"),SystemClock.uptimeMillis());
            });
        });
        tryVideo(action.seq);
    }
    private static void tryVideo(long seq){Runnable ready=videos.get(seq);if(ready!=null&&confirmedVideo.containsKey(seq)){videos.remove(seq);ready.run();}}
    static final class VideoAction {
        final long seq,generation,sourceGeneration;
        VideoAction(long seq,long generation,long sourceGeneration){this.seq=seq;this.generation=generation;this.sourceGeneration=sourceGeneration;}
        JSONObject event(String code){try{return new JSONObject().put("code",code).put("seq",seq).put("generation",generation).put("source_generation",sourceGeneration);}catch(Exception ignored){return new JSONObject();}}
    }
    static VideoAction videoAction(JSONObject args){
        try{JSONObject value=args.optJSONObject("benchmark");if(value==null)return null;long seq=integer(value,"seq"),generation=integer(value,"generation"),source=integer(value,"source_generation");return known(seq)?new VideoAction(seq,generation,source):null;}catch(Exception ignored){return null;}
    }
    // No previous_audio_stopped/mixer_match producer: an audited mixer/PCM adapter is required.
}
