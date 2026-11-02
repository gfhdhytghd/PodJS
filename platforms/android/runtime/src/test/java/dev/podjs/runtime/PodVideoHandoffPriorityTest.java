package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** Paging owns foreground admission until promotion, failure, lifecycle exit or release. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoHandoffPriorityTest {
 static Field field(Class<?> c,String n)throws Exception {Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback,PodVideoServices.Callback {
  String error;public void complete(JSONObject v){}public void fail(String c,String m){error=c;}
 }
 @Test public void sessionHandoffKeepsAdmissionAndLastReleaseClearsIt()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));s.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> constructor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
  Object active=constructor.newInstance(s,"active");ExoPlayer p=new ExoPlayer.Builder(c).build();field(t,"player").set(active,p);field(t,"audioPolicy").set(active,new PodMediaAudioPolicy(c,p));
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(s);slots.put("active",active);
  try {
   Reply r=new Reply();s.dispatch("video.session.play",new JSONObject().put("sessionId","active"),r);assertNull(r.error);assertTrue(HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.session.pause",new JSONObject().put("sessionId","active").put("handoff",true),r);assertNull(r.error);assertFalse(p.getPlayWhenReady());assertTrue("paging pause admitted restore",HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.session.pause",new JSONObject().put("sessionId","active"),r);assertFalse("ordinary pause retained admission",HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.session.play",new JSONObject().put("sessionId","active"),r);
   s.dispatch("video.session.retire",new JSONObject().put("sessionId","active").put("handoff",true),r);assertNull(r.error);assertFalse(p.getPlayWhenReady());assertTrue("cold handoff admitted restore",HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.session.release",new JSONObject().put("sessionId","active"),r);assertFalse("last retired slot leaked admission",HostWorkPriority.DEVICE.mediaActive());
  } finally {s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void legacyHandoffKeepsAdmissionButStopClearsIt()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoServices s=new PodVideoServices(c);ExoPlayer p=new ExoPlayer.Builder(c).build();field(PodVideoServices.class,"player").set(s,p);field(PodVideoServices.class,"audioPolicy").set(s,new PodMediaAudioPolicy(c,p));
  try {
   Reply r=new Reply();s.dispatch("video.play",new JSONObject(),r);assertNull(r.error);
   s.dispatch("video.pause",new JSONObject().put("handoff",true),r);assertFalse(p.getPlayWhenReady());assertTrue("legacy paging pause admitted restore",HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.pause",new JSONObject(),r);assertFalse(HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.play",new JSONObject(),r);s.dispatch("video.retire",new JSONObject().put("handoff",true),r);assertFalse(p.getPlayWhenReady());assertTrue("legacy cold handoff admitted restore",HostWorkPriority.DEVICE.mediaActive());
   s.dispatch("video.stop",new JSONObject(),r);assertFalse(HostWorkPriority.DEVICE.mediaActive());
  } finally {s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
