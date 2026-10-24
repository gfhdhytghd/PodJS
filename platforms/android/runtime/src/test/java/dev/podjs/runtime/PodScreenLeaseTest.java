package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.WindowManager;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodScreenLeaseTest {
    static JSONObject lease(String owner,boolean active)throws Exception{return new JSONObject().put("owner",owner).put("active",active);}
    static boolean awake(Activity activity){return (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;}
    @Test public void readerLeaseIsIndependentAndNativeBackgroundAlwaysClearsWindowFlag()throws Exception {
        try(org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            try(PodScreenServices screen=new PodScreenServices(activity,true,value->{})){
                screen.execute("display.keepAwake",lease("reader",true));assertTrue(awake(activity));
                screen.execute("display.keepAwake",lease("transfer",true));
                screen.execute("display.keepAwake",lease("reader",false));assertTrue(awake(activity));
                screen.lifecycle(false);assertFalse(awake(activity));
                screen.execute("display.keepAwake",lease("transfer",false));
                screen.lifecycle(true);assertFalse(awake(activity));
                screen.execute("display.keepAwake",lease("reader",true));assertTrue(awake(activity));
            }
            assertFalse(awake(activity));
        }
    }
}
