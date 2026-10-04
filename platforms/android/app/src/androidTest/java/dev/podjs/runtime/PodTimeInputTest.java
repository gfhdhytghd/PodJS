package dev.podjs.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Dialog callbacks are exercised in the app window; this is not physical input acceptance. */
public class PodTimeInputTest {
    @Test public void confirmsBothDayBoundariesAndDismissesWithoutAValue() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Activity activity=instrumentation.startActivitySync(new Intent().setClassName(instrumentation.getTargetContext().getPackageName(),"dev.podjs.androidwatch.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        LinkedBlockingQueue<String> events=new LinkedBlockingQueue<>();
        PodServices services=new PodServices(activity,events::offer);
        try {
            for(int id=1;id<=3;id++) {
                final int requestId=id;
                instrumentation.runOnMainSync(()->dispatch(services,requestId,"input.time",minutes(420)));
                instrumentation.waitForIdleSync();
                TimePickerDialog dialog=dialog(services,id);
                assertTrue(dialog.isShowing());
                instrumentation.runOnMainSync(()->{
                    if(requestId==3)dialog.cancel();
                    else {dialog.updateTime(requestId==1?0:23,requestId==1?0:59);dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();}
                });
                JSONObject result=result(events);
                assertEquals(id,result.getInt("id"));assertTrue(result.getBoolean("ok"));
                if(id==3)assertTrue(result.isNull("value"));else assertEquals(id==1?0:1439,result.getInt("value"));
                instrumentation.waitForIdleSync();assertTrue(events.isEmpty());
            }
        } finally {instrumentation.runOnMainSync(()->{services.close();activity.finish();});}
    }
    @Test public void rejectsInvalidAndConcurrentRequestsAndCancelsTheDialog() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Activity activity=instrumentation.startActivitySync(new Intent().setClassName(instrumentation.getTargetContext().getPackageName(),"dev.podjs.androidwatch.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        LinkedBlockingQueue<String> events=new LinkedBlockingQueue<>();
        PodServices services=new PodServices(activity,events::offer);
        try {
            for(Object value:new Object[]{-1,1440,1.5,"420",JSONObject.NULL}) {
                instrumentation.runOnMainSync(()->dispatch(services,1,"input.time",minutes(value)));
                assertEquals("invalid_argument",result(events).getString("code"));
            }
            instrumentation.runOnMainSync(()->dispatch(services,2,"input.time",minutes(0)));
            instrumentation.waitForIdleSync();TimePickerDialog dialog=dialog(services,2);
            instrumentation.runOnMainSync(()->dispatch(services,3,"input.time",minutes(1439)));
            assertEquals("busy",result(events).getString("code"));
            instrumentation.runOnMainSync(()->{
                try {services.dispatch(new JSONObject().put("t","service.cancel").put("version",1).put("id",2));}
                catch(Exception error){throw new RuntimeException(error);}
            });
            instrumentation.waitForIdleSync();assertFalse(dialog.isShowing());
            SystemClock.sleep(100);assertTrue(events.isEmpty());
        } finally {instrumentation.runOnMainSync(()->{services.close();activity.finish();});}
    }
    private static JSONObject minutes(Object value) {
        try {return new JSONObject().put("minutes",value);}catch(Exception error){throw new RuntimeException(error);}
    }
    private static JSONObject result(LinkedBlockingQueue<String> events) throws Exception {
        String value=events.poll(5,TimeUnit.SECONDS);assertNotNull("Host did not complete time input",value);return new JSONObject(value);
    }
    private static void dispatch(PodServices services,int id,String method,JSONObject args) {
        try {services.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method).put("args",args));}
        catch(Exception error){throw new RuntimeException(error);}
    }
    private static TimePickerDialog dialog(PodServices services,int id) throws Exception {
        Field requests=PodServices.class.getDeclaredField("requests");requests.setAccessible(true);
        Object request=((Map<?,?>)requests.get(services)).get(id);assertNotNull(request);
        Field dialog=request.getClass().getDeclaredField("dialog");dialog.setAccessible(true);return (TimePickerDialog)dialog.get(request);
    }
}
