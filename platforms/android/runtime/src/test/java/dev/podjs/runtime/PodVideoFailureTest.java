package dev.podjs.runtime;
import static org.junit.Assert.*;
import androidx.media3.common.PlaybackException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoFailureTest {
 @Test public void nativeFailureKeepsClassCodeAndClosedSourceShapeWithoutPrivateText()throws Exception{
  ShadowLog.clear();JSONObject args=new JSONObject().put("url","https://fixture.invalid/PRIVATE?token=SECRET").put("videoMime","video/avc").put("videoWidth",540).put("videoHeight",960);
  PodVideoFailure.record("sessions","prepare","invalid_argument",new IllegalArgumentException("unknown_video_session PRIVATE"),args,2,3,"attached","mp4");
  String log=ShadowLog.getLogsForTag("DYBENCH").get(0).msg;assertTrue(log.contains("failure_code=session_missing"));assertTrue(log.contains("exception_kind=illegal_argument"));assertTrue(log.contains("codec_kind=avc"));assertTrue(log.contains("content_kind=mp4"));assertFalse(log.contains("PRIVATE"));assertFalse(log.contains("SECRET"));assertFalse(log.contains("https"));assertFalse(log.contains("token"));
 }
 @Test public void media3NumberAndResourceLimitsAreRetainedButArbitraryFieldsAreRejected()throws Exception{
  ShadowLog.clear();PodVideoFailure.record("sessions","prepare","decode_error",new PlaybackException("PRIVATE",null,PlaybackException.ERROR_CODE_DECODING_FAILED),new JSONObject(),1,1,"attached","octet");
  String log=ShadowLog.getLogsForTag("DYBENCH").get(0).msg;assertTrue(log.contains("media3_code="+PlaybackException.ERROR_CODE_DECODING_FAILED));assertTrue(log.contains("exception_kind=media3"));
  JSONObject row=new JSONObject().put("code","error_page").put("failure_code","resource_exhausted").put("failure_stage","prepare").put("exception_kind","illegal_state").put("slot_limit",1).put("decoder_count",1);
  assertTrue(PodBenchmark.dispatch(row.toString())>0);
  for(String key:new String[]{"failure_code","failure_stage","exception_kind","slot_limit","decoder_count","content_kind","source_kind","surface_state"})assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put(key,"PRIVATE").toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("slot_limit",9).toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("media3_code",-1).toString()));
  assertEquals(0,PodBenchmark.dispatch(new JSONObject(row.toString()).put("code","entry_start").toString()));
  assertFalse(ShadowLog.getLogsForTag("DYBENCH").toString().contains("PRIVATE"));
 }
 @Test public void resourceSurfaceAndReadinessCausesAreDistinct(){
  assertEquals("resource_exhausted",PodVideoFailure.fault("video_session_error",new IllegalStateException("video_decoder_limit")));
  assertEquals("not_ready",PodVideoFailure.fault("video_session_error",new IllegalStateException("video_not_ready")));
  assertEquals("surface_error",PodVideoFailure.fault("video_session_error",new IllegalStateException("video_surface_unavailable")));
 }
}
