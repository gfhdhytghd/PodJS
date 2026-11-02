package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.*;
import android.widget.FrameLayout;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPendingAdoptionTest {
 static Field f(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String message){error=c;}}
 @Test public void requestedForegroundKeepsPendingPlayerGenerationAndCancelsOnlySpeculativeDeadline()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));host.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(host);
  Reply pending=new Reply();ExoPlayer player=new ExoPlayer.Builder(c).build();
  try{
   Object slot=ctor.newInstance(host,"incoming");slots.put("incoming",slot);f(type,"player").set(slot,player);f(type,"generation").setLong(slot,12);f(type,"prewarming").setBoolean(slot,true);f(type,"prepareCallback").set(slot,pending);f(type,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));
   Method arm=type.getDeclaredMethod("armPrewarmTimeout",ExoPlayer.class,long.class);arm.setAccessible(true);arm.invoke(slot,player,12L);
   JSONObject args=new JSONObject().put("sessionId","incoming").put("autoplay",false).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233));
   Reply ordinary=new Reply();host.dispatch("video.session.promote",args,ordinary);assertNotNull("ordinary promotion still requires a decoded frame",ordinary.error);
   Reply foreground=new Reply();host.dispatch("video.session.promote",new JSONObject(args.toString()).put("adoptPreparing",true),foreground);
   assertNull(foreground.error);assertNotNull(foreground.value);assertFalse(foreground.value.getBoolean("hasRenderedFirstFrame"));
   assertSame(player,f(type,"player").get(slot));assertEquals(12,f(type,"generation").getLong(slot));assertFalse(f(type,"prewarming").getBoolean(slot));assertNull(f(type,"prewarmTimeout").get(slot));
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));assertNull(pending.error);assertSame(player,f(type,"player").get(slot));
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
