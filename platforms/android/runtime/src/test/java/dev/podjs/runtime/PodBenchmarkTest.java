package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.os.SystemClock;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodBenchmarkTest {
 @org.junit.Test public void coldPrepareStagesStayClosedAndNativeAdoptionDoesNotInventADecode() throws Exception {
  long seq=PodBenchmark.begin("entry_start",new org.json.JSONObject());
  for(String stage:new String[]{"prepare_admitted","player_constructed","prepare_dispatched","video_format_ready","initial_frame_adopted"})
   org.junit.Assert.assertEquals(seq,PodBenchmark.mark("startup_stage",seq,new org.json.JSONObject().put("startup_stage",stage)));
  org.junit.Assert.assertEquals(0,PodBenchmark.mark("startup_stage",seq,new org.json.JSONObject().put("startup_stage","arbitrary_private_text")));
 }

 @Test public void requestDiagnosticsRequireExactClosedFields()throws Exception {
  ShadowLog.clear();JSONObject row=new JSONObject().put("code","douyin_request").put("category","detail").put("http_status",403).put("douyin_status_code",-1).put("challenge",false).put("signing_ok",true).put("cookie_present",true).put("retry_after_ms",30000).put("error_code","forbidden");
  assertTrue(PodBenchmark.dispatch(row.toString())>0);
  for(String key:new String[]{"url","id","cookie","token","text"})assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put(key,"PRIVATE").toString()));
  for(String key:new String[]{"category","http_status","douyin_status_code","challenge","signing_ok","cookie_present","retry_after_ms","error_code"}){JSONObject missing=new JSONObject(row.toString());missing.remove(key);assertEquals(0,PodBenchmark.dispatch(missing.toString()));assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put(key,"PRIVATE").toString()));}
  for(Object value:new Object[]{-2,1.5,2147483648L})assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("douyin_status_code",value).toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("http_status",600).toString()));
  assertEquals(1,ShadowLog.getLogsForTag("DYBENCH").size());assertFalse(ShadowLog.getLogsForTag("DYBENCH").get(0).msg.contains("PRIVATE"));
 }
 @Test public void closedSchemaOwnsClockAndRejectsPrivateOrForgedProof()throws Exception {
  ShadowLog.clear();long seq=PodBenchmark.dispatch("{\"code\":\"gesture_down\",\"direction\":\"next\"}");assertTrue(seq>0);
  long now=SystemClock.uptimeMillis();assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("code","gesture_release").put("seq",seq).put("direction","next").put("prewarmed",true).put("generation",3).put("source_generation",2).toString()));
  List<ShadowLog.LogItem> lines=ShadowLog.getLogsForTag("DYBENCH");assertEquals(2,lines.size());assertEquals(android.util.Log.INFO,lines.get(1).type);assertTrue(lines.get(1).msg.contains("mono_ms="+now));
  for(String bad:new String[]{"{\"code\":\"entry_start\",\"url\":\"PRIVATE\"}","{\"code\":\"entry_start\",\"mono_ms\":1}","{\"code\":\"login_tap\"}","{\"code\":\"entry_start\",\"generation\":-1}","{\"code\":\"entry_start\",\"generation\":1.5}","{\"code\":\"PRIVATE\"}","{\"code\":\"mixer_match\"}","{\"code\":\"previous_audio_stopped\"}","{\"code\":\"incoming_first_frame\"}"})assertEquals(0,PodBenchmark.dispatch(bad));
  assertEquals(2,ShadowLog.getLogsForTag("DYBENCH").size());assertEquals(0,PodBenchmark.dispatch(new JSONObject().put("code","gesture_release").put("seq",seq).toString()));
 }
 @Test public void videoActionsCarryOnlyOneOrTwoItemMovement()throws Exception {
  long seq=PodBenchmark.begin("gesture_down",new JSONObject());JSONObject fields=new JSONObject().put("seq",seq).put("generation",2).put("source_generation",1);
  assertEquals(1,PodBenchmark.videoAction(new JSONObject().put("benchmark",fields)).delta);
  assertEquals(2,PodBenchmark.videoAction(new JSONObject().put("benchmark",fields.put("delta",2))).delta);
  assertNull(PodBenchmark.videoAction(new JSONObject().put("benchmark",fields.put("delta",3))));
  assertNull(PodBenchmark.videoAction(new JSONObject().put("benchmark",fields.put("delta",4294967298L))));
 }
 @Test public void networkStallIsClosedAndPreserved()throws Exception {
  ShadowLog.clear();long seq=PodBenchmark.begin("gesture_down",new JSONObject());
  assertEquals(seq,PodBenchmark.mark("spinner_shown",seq,new JSONObject().put("network_stalled",true).put("reason","network_stalled").put("buffered_ms",0)));
  assertTrue(ShadowLog.getLogsForTag("DYBENCH").get(1).msg.contains("network_stalled=true"));
  assertEquals(0,PodBenchmark.mark("error",seq,new JSONObject().put("network_stalled","PRIVATE")));
  assertEquals(0,PodBenchmark.mark("error",seq,new JSONObject().put("reason","PRIVATE")));
  assertEquals(2,ShadowLog.getLogsForTag("DYBENCH").size());
 }
 @Test public void authCorrelationAndUptimeAreNativeAndNoSurfaceCannotProvePresentation()throws Exception{
  ShadowLog.clear();long seq=PodBenchmark.dispatch("{\"code\":\"login_entry\",\"attempt\":2}");assertTrue(seq>0);
  assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("code","scan_accepted").put("seq",seq).put("attempt",2).toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject().put("code","qr_ready").put("seq",seq).put("attempt",2).put("presented",true).toString()));
  assertEquals(2,ShadowLog.getLogsForTag("DYBENCH").size());long second=PodBenchmark.dispatch("{\"code\":\"entry_start\"}");assertTrue(second>seq);
  int count=ShadowLog.getLogsForTag("DYBENCH").size();assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("video_frame",seq).put("generation",3).toString()));assertEquals(count,ShadowLog.getLogsForTag("DYBENCH").size());
  long at=SystemClock.uptimeMillis();long mute=PodBenchmark.begin("mute_changed",new JSONObject().put("kind","on").put("muted",true),at);assertTrue(mute>second);assertTrue(ShadowLog.getLogsForTag("DYBENCH").get(count).msg.contains("mono_ms="+at));
 }

 @Test public void visibilityExplainsNativeFocusGuardWithoutForgingVideoProof()throws Exception{
  ShadowLog.clear();PodBenchmark.attach(null);
  JSONObject row=new JSONObject().put("code","player_visible").put("state","present").put("visibility_reason","ready").put("player_state","playing").put("has_frame",true).put("offset_abs_milli",0);
  assertTrue(PodBenchmark.dispatch(row.toString())>0);String log=ShadowLog.getLogsForTag("DYBENCH").get(0).msg;assertTrue(log.contains("visibility_reason=native_hidden"));assertTrue(log.contains("state=absent"));assertTrue(log.contains("has_frame=true"));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("player_state","PRIVATE").toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("offset_abs_milli",-1).toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("has_frame","PRIVATE").toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("code","error").toString()));
 }
 @org.junit.Test public void startupStageKeepsNativeUptimeAndClosedFields()throws Exception {
  long seq=PodBenchmark.begin("entry_start",new org.json.JSONObject());
  org.robolectric.shadows.ShadowLog.clear();
  assertEquals(seq,PodBenchmark.mark("startup_stage",seq,new org.json.JSONObject().put("startup_stage","decoder_configured").put("duration_ms",128)));
  assertEquals(0,PodBenchmark.mark("startup_stage",seq,new org.json.JSONObject().put("startup_stage","arbitrary_secret")));
  assertEquals(0,PodBenchmark.mark("entry_start",seq,new org.json.JSONObject().put("startup_stage","play_tap")));
  assertEquals(1,org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").size());
  assertEquals(seq,PodBenchmark.mark("startup_stage",seq,new JSONObject().put("startup_stage","stock_saved").put("slots",3)));
  assertEquals(0,PodBenchmark.mark("startup_stage",seq,new JSONObject().put("startup_stage","stock_saved").put("slots",4)));
 }
}
