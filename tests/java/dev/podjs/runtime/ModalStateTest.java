package dev.podjs.runtime;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
/** Offline host-state boundary tests, not Android UI/process acceptance. */
public final class ModalStateTest {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static JSONObject state(String key,String text,int start,int end)throws Exception {
        return new JSONObject().put("v",1).put("input",new JSONObject().put("key",key).put("title","search").put("text",text).put("start",start).put("end",end));
    }
    static void rejected(JSONObject state)throws Exception {
        PodModalState next=new PodModalState();next.restore(state.toString());check(next.retained()==null,"invalid recovery must be empty");
    }
    public static void main(String[] args)throws Exception {
        PodModalState original=new PodModalState();original.update("route:r1:search","search","未确认🙂",1,4);
        String snapshot=original.retained();check(snapshot!=null,"draft snapshot");
        original.clear("route:r2:search");check(original.retained()!=null,"other route does not cancel owner");
        PodModalState config=new PodModalState();config.restore(snapshot);
        check(config.pending("route:r1:search").getString("text").equals("未确认🙂"),"real draft");
        check(config.pending("route:r1:search").getInt("end")==4,"selection");
        check(config.pending("route:r2:search")==null,"identity isolation");
        config.pending("route:r1:search").put("text","mutated");
        check(config.pending("route:r1:search").getString("text").equals("未确认🙂"),"snapshot isolation");
        config.clear("route:r1:search");check(config.retained()==null,"confirm/cancel removes recovery");
        PodModalState process=new PodModalState();process.restore(null);check(process.retained()==null,"process has no retained draft");
        original.clear("route:r1:search");check(snapshot.contains("未确认"),"old immutable snapshot independent of destruction");
        rejected(state("route:r1","x",-1,0));rejected(state("route:r1","x",0,2));
        rejected(state("route:r1","x",0,0).put("v",2));rejected(state("route:r1","x",0,0).put("v","1"));
        rejected(state("route:r1","x",0,0).put("cookie","secret"));
        JSONObject draft=state("route:r1","x",0,0);draft.getJSONObject("input").put("start",.5);rejected(draft);
        draft=state("route:r1","x",0,0);draft.getJSONObject("input").put("token","secret");rejected(draft);
        rejected(state("route/r1","x",0,0));
        for(String raw:new String[]{"", "[]", "bad", "{\"v\":1}"}){config.restore(raw);check(config.retained()==null,"bad JSON/schema");}
        int overhead=state("k","",0,0).toString().getBytes(StandardCharsets.UTF_8).length;
        String exact="x".repeat(PodModalState.MAX_BYTES-overhead);
        config.restore(state("k",exact,0,0).toString());check(config.retained()!=null,"exact UTF8 limit");
        rejected(state("k",exact+"x",0,0));
        config.update("k","search","中".repeat(PodModalState.MAX_BYTES),0,0);check(config.retained()==null,"oversize live draft safely omitted");

        JSONObject page=new JSONObject().put("v",1).put("options",new JSONObject().put("url","https://example.org/page").put("mode","inApp").put("restoreActivity",true))
            .put("url","https://example.org/page#anchor").put("scale","expanded").put("x",8).put("y",500).put("zoom",1.25);
        JSONObject restoredPage=PodBrowserPageState.decode(page.toString());
        check(restoredPage!=null && restoredPage.getInt("y")==500 && restoredPage.getDouble("zoom")==1.25,"browser saved roundtrip");
        for(JSONObject invalid:new JSONObject[]{new JSONObject(page.toString()).put("v",2),new JSONObject(page.toString()).put("scale","huge"),new JSONObject(page.toString()).put("x",.5),new JSONObject(page.toString()).put("y",-1),new JSONObject(page.toString()).put("zoom",0),new JSONObject(page.toString()).put("zoom","1"),new JSONObject(page.toString()).put("dom","secret"),new JSONObject(page.toString()).put("url",123)})check(PodBrowserPageState.decode(invalid.toString())==null,"invalid browser state");
        JSONObject noOptIn=new JSONObject(page.toString());noOptIn.getJSONObject("options").remove("restoreActivity");check(PodBrowserPageState.decode(noOptIn.toString())==null,"browser explicit opt-in");
        System.out.println("PASS: browser saved position/scale/zoom roundtrip, numeric/schema/version rejection and explicit opt-in");
        System.out.println("PASS: retained-only modal draft, route/selection isolation, confirm/cancel cleanup, immutable snapshot, malformed/version/extra-field rejection, exact UTF8 limit");
    }
}
