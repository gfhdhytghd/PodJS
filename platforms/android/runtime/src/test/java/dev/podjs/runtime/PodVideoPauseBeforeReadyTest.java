package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** A paging release can race with cancellation before a replacement player exists. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPauseBeforeReadyTest {
 static class Reply implements PodVideoSessionServices.Callback,PodVideoServices.Callback {
  String error;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}
 }
 @Test public void existingSessionWithoutPlayerPausesAsNoOp()throws Exception {
  Context c=androidx.test.core.app.ApplicationProvider.getApplicationContext();PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);Object session=ctor.newInstance(s,"active");
  Field slots=PodVideoSessionServices.class.getDeclaredField("sessions");slots.setAccessible(true);((Map<String,Object>)slots.get(s)).put("active",session);
  try {
   org.robolectric.shadows.ShadowLog.clear();
   for(boolean handoff:new boolean[]{true,false,true}){Reply r=new Reply();s.dispatch("video.session.pause",new JSONObject().put("sessionId","active").put("handoff",handoff),r);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(r.error);assertNotNull(r.value);assertEquals("idle",r.value.getString("state"));}
   assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().noneMatch(x->x.msg.contains("code=error_page")||x.msg.contains("code=player_paused")));
   Reply missing=new Reply();s.dispatch("video.session.pause",new JSONObject().put("sessionId","missing"),missing);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(missing.error);
  }finally{s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void legacyWithoutPlayerPausesAsNoOpAfterRetireAndStop()throws Exception {
  Context c=androidx.test.core.app.ApplicationProvider.getApplicationContext();PodVideoServices s=new PodVideoServices(c);
  try{
   for(String setup:new String[]{"video.state","video.retire","video.stop"}){
    s.dispatch(setup,new JSONObject(),new Reply());Shadows.shadowOf(Looper.getMainLooper()).idle();
    Reply before=new Reply();s.dispatch("video.state",new JSONObject(),before);Shadows.shadowOf(Looper.getMainLooper()).idle();
    Reply r=new Reply();s.dispatch("video.pause",new JSONObject().put("handoff",true),r);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(r.error);assertNotNull(r.value);assertEquals(before.value.getString("state"),r.value.getString("state"));
   }
  }finally{s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void releaseReasonsAreClosedAndGuestCannotForgeSnapProof()throws Exception {
  for(String reason:new String[]{"boundary","network_prompt","threshold","cancelled","release"}){
   long seq=PodBenchmark.begin("gesture_down",new JSONObject());
   assertTrue(PodBenchmark.mark("gesture_release",seq,new JSONObject().put("direction","cancel").put("delta",0).put("reason",reason))>0);
  }
  long seq=PodBenchmark.begin("gesture_down",new JSONObject());
  assertEquals(0,PodBenchmark.mark("gesture_release",seq,new JSONObject().put("reason","PRIVATE")));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject().put("code","snap_back").put("seq",seq).put("generation",1).toString()));
 }

}
