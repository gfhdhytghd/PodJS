package dev.podjs.runtime;

import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

/** Real production service dispatch; no restored account, network or device. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class PodForegroundMediaGateTest {
    private static void drain(int millis)throws Exception {
        for(int i=0;i<millis;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}
    }
    private static void query(PodServices host,int id)throws Exception {
        host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method","uri.parse").put("args",new JSONObject().put("value","https://fixture.invalid/video")));
    }
    @Test public void foregroundRequestsPassWhileHeadlessRequestsWaitAndResumeWithoutReplay()throws Exception {
        Object owner=new Object();List<String> bg=Collections.synchronizedList(new ArrayList<>()),fg=Collections.synchronizedList(new ArrayList<>());
        try(PodServices background=new PodServices(ApplicationProvider.getApplicationContext(),bg::add);PodServices foreground=new PodServices(ApplicationProvider.getApplicationContext(),fg::add)){
            background.headlessWork();HostWorkPriority.DEVICE.foregroundMedia(owner,true);
            query(background,1);query(foreground,2);drain(100);
            assertEquals(0,bg.size());assertEquals(1,fg.size());assertEquals(2,new JSONObject(fg.get(0)).getInt("id"));assertTrue(new JSONObject(fg.get(0)).getBoolean("ok"));
            HostWorkPriority.DEVICE.foregroundMedia(owner,false);drain(100);
            assertEquals(1,bg.size());assertEquals(1,new JSONObject(bg.get(0)).getInt("id"));assertTrue(new JSONObject(bg.get(0)).getBoolean("ok"));
            drain(20);assertEquals(1,bg.size());
        }finally{HostWorkPriority.DEVICE.foregroundMedia(owner,false);shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void cancellationDuringMediaWaitCannotWriteALateReply()throws Exception {
        Object owner=new Object();List<String> replies=Collections.synchronizedList(new ArrayList<>());
        try(PodServices background=new PodServices(ApplicationProvider.getApplicationContext(),replies::add)){
            background.headlessWork();HostWorkPriority.DEVICE.foregroundMedia(owner,true);query(background,3);drain(20);
            background.dispatch(new JSONObject().put("t","service.cancel").put("id",3));HostWorkPriority.DEVICE.foregroundMedia(owner,false);drain(100);assertTrue(replies.isEmpty());
        }finally{HostWorkPriority.DEVICE.foregroundMedia(owner,false);shadowOf(Looper.getMainLooper()).idle();}
    }
}
