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
 static class Reply implements PodVideoSessionServices.Callback {String error,message;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String message){error=c;this.message=message;}}
 @Test public void pagingPauseAfterReclamationIsIdempotentWithoutFrameMetricsOrAudioProof()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  try{
   org.robolectric.shadows.ShadowLog.clear();Reply reply=new Reply();host.dispatch("video.session.pause",new JSONObject().put("sessionId","already-released").put("handoff",true),reply);
   assertNull(reply.error);assertEquals("stopped",reply.value.getString("state"));assertFalse(reply.value.getBoolean("hasRenderedFirstFrame"));assertFalse(reply.value.has("metrics"));assertFalse(reply.value.has("sourceGeneration"));
   assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").isEmpty());
   Reply ordinary=new Reply();host.dispatch("video.session.pause",new JSONObject().put("sessionId","unknown"),ordinary);assertEquals("invalid_argument",ordinary.error);
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void absentOutgoingTransitionPreservesTheIncomingSlotWithoutInventingComposition()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));host.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(host);
  try{
   Object incoming=ctor.newInstance(host,"incoming");slots.put("incoming",incoming);f(type,"generation").setLong(incoming,9);f(type,"prewarming").setBoolean(incoming,true);
   JSONObject args=new JSONObject().put("fromSessionId","already-released").put("toSessionId","incoming").put("progress",.5).put("direction","up").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233));
   org.robolectric.shadows.ShadowLog.clear();Reply reply=new Reply();host.dispatch("video.session.transition",args,reply);
   assertNull(reply.error);assertFalse(reply.value.getBoolean("composited"));assertFalse(reply.value.getBoolean("nativeTracking"));assertFalse(reply.value.has("fromOffsetYPx"));assertSame(incoming,slots.get("incoming"));assertEquals(9,f(type,"generation").getLong(incoming));assertTrue(f(type,"prewarming").getBoolean(incoming));assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").isEmpty());
   Reply invalid=new Reply();host.dispatch("video.session.transition",new JSONObject(args.toString()).put("progress",2),invalid);assertEquals("invalid_argument",invalid.error);
   Reply absentTarget=new Reply();host.dispatch("video.session.transition",new JSONObject(args.toString()).put("toSessionId","unknown"),absentTarget);assertEquals("invalid_argument",absentTarget.error);
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
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
   int[] cancelled={0};f(type,"frameCompletionCancel").set(slot,(Runnable)()->cancelled[0]++);
   Reply foreground=new Reply();host.dispatch("video.session.promote",new JSONObject(args.toString()).put("adoptPreparing",true).put("transform",new JSONObject().put("rotationDeg",90)),foreground);
   assertNull(foreground.error);assertNotNull(foreground.value);assertNull("promotion owns its frame handoff",f(type,"frameCompletionCancel").get(slot));assertEquals(1,cancelled[0]);assertFalse(foreground.value.getBoolean("hasRenderedFirstFrame"));assertEquals(90f,f(type,"rotationDeg").getFloat(slot),0f);
   assertSame(player,f(type,"player").get(slot));assertEquals(12,f(type,"generation").getLong(slot));assertFalse(f(type,"prewarming").getBoolean(slot));assertNull(f(type,"prewarmTimeout").get(slot));
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));assertNull(pending.error);assertSame(player,f(type,"player").get(slot));
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void expiredOptionalAdoptionHasClosedUnavailableAcknowledgementWithoutCreatingAPlayer()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));host.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  try{
   JSONObject args=new JSONObject().put("sessionId","expired").put("adoptPreparing",true);
   Reply reply=new Reply();host.dispatch("video.session.promote",args,reply);
   assertEquals("not_ready",reply.error);assertEquals("video_prewarm_unavailable",reply.message);
   assertTrue(((Map<?,?>)f(PodVideoSessionServices.class,"sessions").get(host)).isEmpty());
   Reply ordinary=new Reply();host.dispatch("video.session.promote",new JSONObject().put("sessionId","expired"),ordinary);assertEquals("invalid_argument",ordinary.error);
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void retiringAnAlreadyReclaimedOutgoingSlotIsIdempotentAndDoesNotInventPlaybackProof()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  try{
   Reply reply=new Reply();host.dispatch("video.session.retire",new JSONObject().put("sessionId","reclaimed").put("handoff",true),reply);
   assertNull(reply.error);assertEquals("stopped",reply.value.getString("state"));assertFalse(reply.value.getBoolean("hasRenderedFirstFrame"));assertFalse(reply.value.has("metrics"));
   assertTrue(((Map<?,?>)f(PodVideoSessionServices.class,"sessions").get(host)).isEmpty());
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
