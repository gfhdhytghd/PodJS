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
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.annotation.*;
/** Baseline/candidate use identical real host commands and a synthetic main-reply backlog. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPagerLatencyTest {
 static Field f(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 @Test public void retainedPromotionLatencyUnder800RestoreReplies()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(2));s.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(s);
  ExoPlayer old=new ExoPlayer.Builder(c).build(),next=new ExoPlayer.Builder(c).build();JSONObject b=new JSONObject().put("width",233).put("height",233);
  PodVideoSessionServices.Callback ignore=new PodVideoSessionServices.Callback(){public void complete(JSONObject v){}public void fail(String code,String message){org.junit.Assert.fail(code);}};
  for(String id:new String[]{"old","next"}){Object slot=ctor.newInstance(s,id);slots.put(id,slot);f(t,"player").set(slot,id.equals("old")?old:next);ExoPlayer output=id.equals("old")?old:next;PodMediaAudioPolicy policy=new PodMediaAudioPolicy(c,output::setVolume);f(t,"audioPolicy").set(slot,policy);policy.setVolume(1f,id.equals("next"));f(t,"hasRenderedFirstFrame").set(slot,true);f(t,"prewarming").set(slot,id.equals("next"));s.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",b),ignore);}
  Shadows.shadowOf(Looper.getMainLooper()).idle();old.play();next.pause();Handler main=new Handler(Looper.getMainLooper());for(int i=0;i<800;i++)main.post(()->ShadowSystemClock.advanceBy(Duration.ofMillis(4)));
  long began=SystemClock.uptimeMillis();long[] elapsed={-1};s.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",b),new PodVideoSessionServices.Callback(){public void complete(JSONObject v){elapsed[0]=SystemClock.uptimeMillis()-began;assertFalse(old.getPlayWhenReady());assertEquals(0,old.getVolume(),.001f);assertTrue(next.getPlayWhenReady());assertTrue(v.optBoolean("hasRenderedFirstFrame"));}public void fail(String code,String message){org.junit.Assert.fail(code);}});
  Shadows.shadowOf(Looper.getMainLooper()).idle();
  boolean baseline=Boolean.getBoolean("swipefeed.baseline");assertEquals(baseline?3200:0,elapsed[0]);System.out.println("SWIPEFEED_LATENCY baseline="+baseline+" backlogUnits=800 unitMs=4 settleToOldPausedMs="+elapsed[0]+" settleToRetainedFrameMs="+elapsed[0]+" nextPlayCommandMs="+elapsed[0]);s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
}
