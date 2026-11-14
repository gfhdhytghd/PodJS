package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Speed changes mutate the retained Media3 player in place and are reported in state. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoSpeedTest {
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void legacyAppliesSpeedAndRejectsOutOfRange()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoServices services=new PodVideoServices(context);
        ExoPlayer player=new ExoPlayer.Builder(context).build();
        field(PodVideoServices.class,"player").set(services,player);
        try {
            for(double speed:new double[]{1.5,.5,2,1}){
                PodVideoLoopTest.Result result=new PodVideoLoopTest.Result();services.dispatch("video.setSpeed",new JSONObject().put("speed",speed),result);idle();result.ok();
                assertSame(player,field(PodVideoServices.class,"player").get(services));
                assertEquals(speed,player.getPlaybackParameters().speed,1e-6);assertEquals(speed,result.value.getDouble("speed"),1e-6);
            }
            for(Object bad:new Object[]{.25,2.5,"1.5"}){
                PodVideoLoopTest.Result invalid=new PodVideoLoopTest.Result();services.dispatch("video.setSpeed",new JSONObject().put("speed",bad),invalid);idle();
                assertNotNull(invalid.error);assertEquals(1,player.getPlaybackParameters().speed,1e-6);
            }
        } finally {services.close();idle();}
    }
    @Test public void sessionAppliesSpeedToTheNamedSession()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoSessionServices services=new PodVideoSessionServices(context,new PodVideoDecoderBudget(2));
        Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(services);
        try {
            Object session=constructor.newInstance(services,"active");
            ExoPlayer player=new ExoPlayer.Builder(context).build();
            field(type,"player").set(session,player);sessions.put("active",session);
            PodVideoLoopTest.Result result=new PodVideoLoopTest.Result();services.dispatch("video.session.setSpeed",new JSONObject().put("sessionId","active").put("speed",1.25),result);idle();result.ok();
            assertEquals(1.25,player.getPlaybackParameters().speed,1e-6);assertEquals(1.25,result.value.getDouble("speed"),1e-6);
        } finally {services.close();idle();}
    }
}
