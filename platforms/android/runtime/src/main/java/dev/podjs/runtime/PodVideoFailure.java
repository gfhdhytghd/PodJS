package dev.podjs.runtime;

import androidx.media3.common.PlaybackException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Closed player failure metadata. Never emit Throwable messages, source IDs or URIs. */
final class PodVideoFailure {
    private static final Map<String,String> ENUMS=new HashMap<>();
    private static final Map<String,Integer> NUMBERS=new HashMap<>();
    static {
        ENUMS.put("failure_code","resource_exhausted decode_error decoder_init format_error surface_error invalid_argument permission_denied cancelled stale not_ready session_missing timeout network_error cdn_error restricted source_expired prepare_failed video_session_error video_error service_closed host_error unsupported recovery_exhausted cache_error storage_error unknown");
        ENUMS.put("failure_stage","capabilities prepare play state bounds transform loop volume pause seek stop release retire promote transition recovery entry playback capture source persist backdrop unknown");
        ENUMS.put("exception_kind","media3 decoder_init codec surface oom io illegal_state illegal_argument security host_service error unknown");
        ENUMS.put("failure_origin","guest sessions legacy");
        ENUMS.put("codec_kind","avc hevc vp9 av1 unknown");
        ENUMS.put("content_kind","mp4 webm hls dash octet html unknown");
        ENUMS.put("source_kind","cache network local missing");
        ENUMS.put("surface_state","attached missing detached unknown");
        NUMBERS.put("media3_code",1000000);
        NUMBERS.put("video_width",16384);
        NUMBERS.put("video_height",16384);
        NUMBERS.put("decoder_count",8);
        NUMBERS.put("slot_limit",8);
    }
    static boolean field(String key){return key.equals("is_prewarm")||ENUMS.containsKey(key)||NUMBERS.containsKey(key);}
    static boolean accept(String key,Object value){
        if(key.equals("is_prewarm"))return value instanceof Boolean;
        if(ENUMS.containsKey(key))return value instanceof String&&Arrays.asList(ENUMS.get(key).split(" ")).contains(value);
        if(NUMBERS.containsKey(key)&&value instanceof Number){double n=((Number)value).doubleValue();return Double.isFinite(n)&&n>=0&&n<=NUMBERS.get(key)&&n==((Number)value).longValue();}
        return false;
    }
    static String kind(Throwable error){
        for(int i=0;error!=null&&i<12;i++,error=error.getCause()){
            String name=error.getClass().getSimpleName();
            if(name.contains("DecoderInitialization"))return "decoder_init";
            if(name.contains("CodecException")||name.contains("DecoderException"))return "codec";
            if(name.contains("OutOfResources"))return "surface";
            if(error instanceof OutOfMemoryError)return "oom";
            if(error instanceof SecurityException)return "security";
            if(error instanceof IllegalArgumentException)return "illegal_argument";
            if(error instanceof IllegalStateException)return "illegal_state";
            if(error instanceof java.io.IOException)return "io";
        }
        return "unknown";
    }
    static String fault(String code,Throwable error){
        String text=error==null?"":String.valueOf(error.getMessage());
        if(text.contains("unknown_video_session"))return "session_missing";
        if(text.contains("video_not_ready")||text.contains("video_not_prewarmed"))return "not_ready";
        if(text.contains("video_surface")||kind(error).equals("surface"))return "surface_error";
        if(text.contains("video_decoder_limit")||text.contains("video_session_limit")||kind(error).equals("oom"))return "resource_exhausted";
        if(kind(error).equals("decoder_init"))return "decoder_init";
        return accept("failure_code",code)?code:"unknown";
    }
    static String stage(String method){
        String name=method.substring(method.lastIndexOf('.')+1);
        if(name.equals("captureFrame"))name="capture";
        if(name.equals("setBounds"))name="bounds";
        if(name.equals("setTransform"))name="transform";
        if(name.equals("setLoop"))name="loop";
        if(name.equals("setVolume"))name="volume";
        return accept("failure_stage",name)?name:"unknown";
    }
    static String codec(String mime){return "video/avc".equals(mime)?"avc":"video/hevc".equals(mime)?"hevc":"video/x-vnd.on2.vp9".equals(mime)?"vp9":"video/av01".equals(mime)?"av1":"unknown";}
    static String source(JSONObject args){String url=args==null?"":args.optString("url",args.optString("videoUrl",""));return url.isEmpty()?"missing":url.startsWith("podjs-media-cache:")?"cache":url.startsWith("http:")||url.startsWith("https:")?"network":"local";}
    static void record(String origin,String stage,String code,Throwable error,JSONObject args,int inUse,int limit,String surface,String content){
        try{
            int media3=error instanceof PlaybackException?((PlaybackException)error).errorCode:0;
            String kind=kind(error);if(kind.equals("unknown")&&error instanceof PlaybackException)kind="media3";
            JSONObject row=new JSONObject().put("reason","failure").put("failure_origin",origin).put("failure_stage",stage)
                .put("failure_code",fault(code,error)).put("exception_kind",kind).put("media3_code",Math.max(0,media3))
                .put("decoder_count",Math.max(0,Math.min(8,inUse))).put("slot_limit",Math.max(0,Math.min(8,limit)))
                .put("source_kind",source(args)).put("surface_state",surface).put("content_kind",content)
                .put("is_prewarm",args!=null&&args.optBoolean("prewarm",false)).put("codec_kind",codec(args==null?"":args.optString("videoMime","")))
                .put("video_width",args==null?0:Math.max(0,Math.min(16384,args.optInt("videoWidth",0))))
                .put("video_height",args==null?0:Math.max(0,Math.min(16384,args.optInt("videoHeight",0))));
            PodBenchmark.begin("error_page",row);
        }catch(Exception ignored){}
    }
}
