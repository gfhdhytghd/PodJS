package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** A decoded callback is available while HWUI has culled the hidden texture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoReadinessTest {
 static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {
  int completions;JSONObject value;public void complete(JSONObject v){completions++;value=v;}public void fail(String c,String m){throw new AssertionError(c);}
 }
 @Test public void hiddenDecodedLiveSlotCompletesOnceButPixelWorkerWaitsForReadback()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();
  for(boolean pixelWorker:new boolean[]{false,true}){
   PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
   Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);Object session=ctor.newInstance(service,"hidden");
   @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);slots.put("hidden",session);
   ExoPlayer player=new ExoPlayer.Builder(context).build();field(type,"player").set(session,player);field(type,"prewarming").setBoolean(session,true);field(type,"keyframeOnly").setBoolean(session,pixelWorker);
   Reply reply=new Reply();field(type,"prepareCallback").set(session,reply);
   try{
    org.robolectric.shadows.ShadowLog.clear();Method rendered=type.getDeclaredMethod("renderedFrame",PodVideoSessionServices.Callback.class);rendered.setAccessible(true);rendered.invoke(session,reply);rendered.invoke(session,reply);
    assertFalse(field(type,"hasUpdatedFrame").getBoolean(session));
    assertEquals(pixelWorker?0:1,reply.completions);
    if(!pixelWorker)assertTrue(reply.value.getBoolean("hasRenderedFirstFrame"));
    assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().noneMatch(x->x.msg.contains("code=incoming_first_frame")));
   }finally{field(type,"prepareCallback").set(session,null);service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
  }
 }
}
