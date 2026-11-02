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
public class PodVideoPrepareDeadlineTest {
 static Field f(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;public void complete(JSONObject v){}public void fail(String c,String message){error=c;}}
 void verify(boolean hardDeadline)throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));host.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(host);
  Reply pending=new Reply();ExoPlayer player=new ExoPlayer.Builder(c).build();
  try{
   Object slot=ctor.newInstance(host,"warm");slots.put("warm",slot);f(type,"player").set(slot,player);f(type,"generation").setLong(slot,12);f(type,"prewarming").setBoolean(slot,true);f(type,"prepareCallback").set(slot,pending);
   PodVideoAnalytics analytics=new PodVideoAnalytics();f(type,"analytics").set(slot,analytics);
   Method arm=type.getDeclaredMethod("armPrewarmTimeout",ExoPlayer.class,long.class);arm.setAccessible(true);arm.invoke(slot,player,12L);
   for(int i=0;i<(hardDeadline?4:1);i++){
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(i==1?1400:2000));analytics.onBytesTransferred(null,null,true,8192);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(i==0?600:0));
    assertNull("productive prewarm was cancelled by a fixed wall-clock timeout",pending.error);
   }
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(hardDeadline?2000:2500));
   assertEquals("timeout",pending.error);assertFalse(slots.containsKey("warm"));
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void bytesPastTheOldDeadlineKeepPreparationAliveButARealStallTimesOut()throws Exception{verify(false);}
 @Test public void continuousProgressStillCannotRetainASpeculativePlayerBeyondTenSeconds()throws Exception{verify(true);}
}
