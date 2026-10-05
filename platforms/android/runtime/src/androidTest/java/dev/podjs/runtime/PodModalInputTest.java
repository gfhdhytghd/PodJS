package dev.podjs.runtime;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
/** Actual EditText/bridge snapshot and rebinding. Activity/process timing needs device acceptance. */
public class PodModalInputTest {
    private static Object field(PodServices services,String key)throws Exception {java.lang.reflect.Field f=PodServices.class.getDeclaredField(key);f.setAccessible(true);return f.get(services);}
    private static JSONObject request(int id,String method,JSONObject args)throws Exception{return new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method).put("args",args);}
    @Test public void actualUnconfirmedDraftAndSelectionRebindToNewRequestOnly() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Activity activity=instrumentation.startActivitySync(new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<PodServices> old=new AtomicReference<>(),next=new AtomicReference<>();List<String> replies=new ArrayList<>();AtomicReference<String> snapshot=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        JSONObject args=new JSONObject().put("title","search").put("value","seed").put("restorationKey","route:r1:search");
        try {
            instrumentation.runOnMainSync(()->{try{old.set(new PodServices(activity,line->{}));old.get().dispatch(request(1,"input.text",args));}catch(Throwable e){failure.set(e);}});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try {
                EditText input=(EditText)field(old.get(),"modalInput");input.setText("未确认🙂");input.setSelection(1,4);
                snapshot.set(old.get().retainedModalState());assertNotNull(snapshot.get());assertNull(old.get().savedModalState());old.get().close();old.set(null);
                next.set(new PodServices(activity,replies::add));next.get().restoreModalState(null,snapshot.get());next.get().dispatch(request(2,"input.text",args));
            }catch(Throwable e){failure.set(e);}});
            instrumentation.waitForIdleSync();
            if(failure.get()!=null)throw new AssertionError(failure.get());
            instrumentation.runOnMainSync(()->{try {
                EditText input=(EditText)field(next.get(),"modalInput");assertEquals("未确认🙂",input.getText().toString());assertEquals(1,input.getSelectionStart());assertEquals(4,input.getSelectionEnd());
                ((AlertDialog)field(next.get(),"modalDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();

            }catch(Throwable e){failure.set(e);}});
            if(failure.get()!=null)throw new AssertionError(failure.get());
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{assertNull(next.get().retainedModalState());assertEquals(1,replies.size());JSONObject reply=new JSONObject(replies.get(0));assertEquals(2,reply.getInt("id"));assertEquals("未确认🙂",reply.getString("value"));}catch(Throwable e){failure.set(e);}});
            if(failure.get()!=null)throw new AssertionError(failure.get());
        } finally {instrumentation.runOnMainSync(()->{if(old.get()!=null)old.get().close();if(next.get()!=null)next.get().close();activity.finish();});}
    }
}
