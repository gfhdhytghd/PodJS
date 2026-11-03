package dev.podjs.runtime;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.json.JSONObject;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoReleaseFrameTest {
 @Test public void releasePixelsAndPlaybackReadinessAreIndependentAndNativeOnly()throws Exception {
  ShadowLog.clear();long seq=PodBenchmark.begin("gesture_down",new JSONObject());
  PodVideoReleaseFrame frame=new PodVideoReleaseFrame();long now=android.os.SystemClock.uptimeMillis();
  frame.record("old",3,now,8,22,true,false,"cached");
  assertFalse(frame.emit("old",new PodBenchmark.VideoAction(seq,2,4,1)));
  assertTrue(frame.emit("old",new PodBenchmark.VideoAction(seq,2,3,1)));
  assertFalse(frame.emit("old",new PodBenchmark.VideoAction(seq,2,3,1)));
  String line=ShadowLog.getLogsForTag("DYBENCH").get(1).msg;
  assertTrue(line.contains("mono_ms="+now));assertTrue(line.contains("decoded_visible=true"));
  assertTrue(line.contains("playback_ready=false"));assertTrue(line.contains("frame_kind=cached"));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject().put("code","release_frame").put("seq",seq).toString()));
 }
 @Test public void newPointerAndTwoItemFlingCannotReuseOneItemPixels()throws Exception {
  ShadowLog.clear();long seq=PodBenchmark.begin("gesture_down",new JSONObject());
  PodVideoReleaseFrame frame=new PodVideoReleaseFrame();
  frame.record("old",1,android.os.SystemClock.uptimeMillis(),2,4,true,true,"live");frame.clear();
  assertFalse(frame.emit("old",new PodBenchmark.VideoAction(seq,1,1,1)));
  frame.record("old",1,android.os.SystemClock.uptimeMillis(),2,4,true,true,"live");
  assertTrue(frame.emit("old",new PodBenchmark.VideoAction(seq,1,1,2)));
  String line=ShadowLog.getLogsForTag("DYBENCH").get(1).msg;
  assertTrue(line.contains("decoded_visible=false"));assertTrue(line.contains("playback_ready=false"));assertTrue(line.contains("frame_kind=none"));
 }
 @Test public void nativeReleaseBindsActualGenerationWhenGuestStateIsStillEmpty()throws Exception {
  ShadowLog.clear();long seq=PodBenchmark.begin("gesture_down",new JSONObject());
  PodVideoReleaseFrame frame=new PodVideoReleaseFrame();frame.record("active",7,android.os.SystemClock.uptimeMillis(),9,2,true,false,"cached");
  PodBenchmark.videoReleaseSource(frame::bind);
  try{
   JSONObject release=new JSONObject().put("code","gesture_release").put("seq",seq).put("generation",4).put("source_generation",0);
   assertEquals(seq,PodBenchmark.dispatch(release.toString()));
   JSONObject command=new JSONObject().put("benchmark",new JSONObject().put("seq",seq).put("generation",4).put("source_generation",0));
   PodBenchmark.VideoAction action=PodBenchmark.videoAction(command);assertEquals(7,action.sourceGeneration);
   frame.record("next",9,android.os.SystemClock.uptimeMillis(),10,3,false,false,"none");
   assertTrue(frame.emit("active",action));assertFalse(frame.emit("active",action));
   assertTrue(ShadowLog.getLogsForTag("DYBENCH").get(1).msg.contains("source_generation=7"));
  }finally{PodBenchmark.videoReleaseSource(null);}
 }
}
