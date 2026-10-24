package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import androidx.media3.common.Player;
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

/** Exercises real Media3 repeat mutation via each public service dispatcher.
 * No source, network or decoder is needed to verify retained player identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoLoopTest {
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static final class Result implements PodVideoServices.Callback,PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
        void ok(){assertNull(error);assertNotNull(value);}
    }
    @Test public void legacySwitchesRepeatImmediatelyWithoutReplacingOrSeekingPlayer()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoServices services=new PodVideoServices(context);
        ExoPlayer player=new ExoPlayer.Builder(context).build();
        field(PodVideoServices.class,"player").set(services,player);
        player.seekTo(1234);
        try {
            for(boolean loop:new boolean[]{true,false,true}){
                Result result=new Result();services.dispatch("video.setLoop",new JSONObject().put("loop",loop),result);
                Shadows.shadowOf(Looper.getMainLooper()).idle();result.ok();
                assertSame(player,field(PodVideoServices.class,"player").get(services));
                assertEquals(loop?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF,player.getRepeatMode());
                assertEquals(1234,player.getCurrentPosition());assertEquals(loop,result.value.getBoolean("loop"));
                assertEquals(0,result.value.getLong("completedLoops"));
            }
            Result invalid=new Result();services.dispatch("video.setLoop",new JSONObject().put("loop","true"),invalid);
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(invalid.error);
        } finally {services.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void sessionSwitchesActiveAndHiddenPlayersInPlace()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoSessionServices services=new PodVideoSessionServices(context,new PodVideoDecoderBudget(2));
        Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(services);
        try {
            for(String id:new String[]{"active","standby"}){
                Object session=constructor.newInstance(services,id);
                ExoPlayer player=new ExoPlayer.Builder(context).build();player.seekTo(4321);
                field(type,"player").set(session,player);field(type,"prewarming").set(session,id.equals("standby"));sessions.put(id,session);
                for(boolean loop:new boolean[]{true,false}){
                    Result result=new Result();services.dispatch("video.session.setLoop",new JSONObject().put("sessionId",id).put("loop",loop),result);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();result.ok();
                    assertSame(player,field(type,"player").get(session));
                    assertEquals(loop?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF,player.getRepeatMode());
                    assertEquals(4321,player.getCurrentPosition());assertEquals(loop,result.value.getBoolean("loop"));
                    assertEquals(id.equals("standby"),result.value.getBoolean("prewarming"));
                }
            }
        } finally {services.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
