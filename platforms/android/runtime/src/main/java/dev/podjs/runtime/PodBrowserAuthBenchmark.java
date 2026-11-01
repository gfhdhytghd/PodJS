package dev.podjs.runtime;

import android.os.SystemClock;
import android.view.View;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Auth wiring only. Shared PodBenchmark owns the sink, clock, validation and seq. */
final class PodBrowserAuthBenchmark {
    final long seq, attempt;
    private boolean failed, accepted;
    private PodBrowserAuthBenchmark(long seq,long attempt){this.seq=seq;this.attempt=attempt;}
    static PodBrowserAuthBenchmark read(JSONObject args){
        try {
            JSONObject value=args.optJSONObject("benchmark");if(value==null||value.length()!=2)return null;
            long seq=PodBenchmark.integer(value,"seq"),attempt=PodBenchmark.integer(value,"attempt");
            return seq>0&&attempt>0&&PodBenchmark.known(seq)?new PodBrowserAuthBenchmark(seq,attempt):null;
        } catch(Exception rejected){return null;}
    }
    private JSONObject event(String code){
        try{return new JSONObject().put("code",code).put("seq",seq).put("attempt",attempt);}catch(Exception rejected){return new JSONObject();}
    }
    void accepted(){if(!accepted){accepted=true;PodBenchmark.emit(event("scan_accepted"),SystemClock.uptimeMillis());}}
    void failed(){if(!failed){failed=true;PodBenchmark.emit(event("error"),SystemClock.uptimeMillis());}}
    void qrPresented(View image,BooleanSupplier current){
        try{PodBenchmark.present(image,event("qr_ready"),()->image.hasWindowFocus()&&current.getAsBoolean());}
        catch(RuntimeException unavailable){/* Missing frame receipt is not an auth failure. */}
    }
    /** Called inside the native credential delete after its atomic mutation succeeds. */
    static void cleared(JSONObject args){
        try {
            JSONObject value=args.optJSONObject("benchmark");if(value==null||value.length()<1||value.length()>2)return;
            long seq=PodBenchmark.integer(value,"seq");if(seq==0||!PodBenchmark.known(seq))return;
            JSONObject event=new JSONObject().put("code","auth_cleared").put("seq",seq);
            if(value.has("attempt")){long attempt=PodBenchmark.integer(value,"attempt");if(attempt==0)return;event.put("attempt",attempt);}
            java.util.Iterator<String> keys=value.keys();while(keys.hasNext())if(!java.util.Arrays.asList("seq","attempt").contains(keys.next()))return;
            PodBenchmark.emit(event,SystemClock.uptimeMillis());
        }catch(Exception rejected){/* Telemetry cannot change credential operations. */}
    }
}
