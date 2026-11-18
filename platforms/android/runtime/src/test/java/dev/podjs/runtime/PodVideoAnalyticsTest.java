package dev.podjs.runtime;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PodVideoAnalyticsTest {
 @Test public void onlyNetworkPayloadCountsAndNoPrivateSourceLeavesSnapshot() throws Exception {
  PodVideoAnalytics a=new PodVideoAnalytics();
  DataSpec source=new DataSpec(Uri.parse("https://fixture.invalid/private?signature=synthetic-secret"));
  a.onBytesTransferred(null,source,false,1000);assertEquals(0,a.snapshot().getLong("bytesLoaded"));assertFalse(a.snapshot().has("firstByteMs"));
  a.onBytesTransferred(null,source,true,123);a.onBytesTransferred(null,source,true,456);
  a.onBandwidthEstimate(null,100,579,46320);a.onDroppedVideoFrames(null,2,100);
  JSONObject result=a.snapshot();assertEquals(579,result.getLong("bytesLoaded"));assertEquals(46320,result.getLong("throughputBps"));assertEquals(2,result.getLong("droppedFrames"));assertTrue(result.getLong("firstByteMs")>=0);
  assertFalse(result.toString().contains("fixture"));assertFalse(result.toString().contains("signature"));
 }
 @Test public void stallRequiresAnOutstandingNetworkTransferAndStopsOnProgressOrEnd() {
  PodVideoAnalytics a=new PodVideoAnalytics();DataSpec source=new DataSpec(Uri.parse("https://fixture.invalid/video"));
  assertFalse(a.networkStalled());a.onTransferStart(null,source,true);
  org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000));assertFalse(a.networkStalled());
  org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));assertTrue(a.networkStalled());
  a.onBytesTransferred(null,source,true,64);assertFalse(a.networkStalled());
  org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001));assertTrue(a.networkStalled());
  a.onTransferEnd(null,source,true);assertFalse(a.networkStalled());
 }
 @Test public void generationsHaveIndependentFirstFrameBytesAndDropCounters() throws Exception {
  PodVideoAnalytics old=new PodVideoAnalytics(),next=new PodVideoAnalytics();
  old.onRenderedFirstFrame(null,new Object(),SystemClock.elapsedRealtime());old.onDroppedVideoFrames(null,3,100);
  assertTrue(old.snapshot().has("firstFrameMs"));assertFalse(next.snapshot().has("firstFrameMs"));assertEquals(0,next.snapshot().getInt("droppedFrames"));
 }
 @Test public void concreteDecoderAndNetworkCausesHaveSafeFixedCategories() {
  assertEquals("decode_error",PodVideoAnalytics.errorCode(new PlaybackException("private",null,PlaybackException.ERROR_CODE_DECODING_FAILED)));
  assertEquals("network_error",PodVideoAnalytics.errorCode(new PlaybackException("private",new java.net.UnknownHostException("private"),PlaybackException.ERROR_CODE_IO_UNSPECIFIED)));
 }
 @Test public void httpRestrictionsExpiryAndCdnFailureAreDistinct() {
  for(int status:new int[]{401,403,412,429,404,410,503}){
   androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException cause=new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(status,"private",null,java.util.Collections.emptyMap(),new DataSpec(Uri.parse("https://fixture.invalid/?signature=private")),new byte[0]);
   String code=PodVideoAnalytics.errorCode(new PlaybackException("private",cause,PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
   assertEquals(status==401||status==412||status==429?"restricted":status==403||status==404||status==410?"source_expired":"cdn_error",code);
  }
 }

 @Test public void nativeCacheExpiryHasAnExpiredSourceCodeWithoutLeakingItsMessage(){
  assertEquals("source_expired",PodVideoAnalytics.errorCode(new PlaybackException("private",new java.io.IOException("media_source_expired"),PlaybackException.ERROR_CODE_IO_UNSPECIFIED)));
  assertEquals("prepare_failed",PodVideoAnalytics.errorCode(new PlaybackException("private",new java.io.IOException("unrelated"),PlaybackException.ERROR_CODE_IO_UNSPECIFIED)));
 }

 @Test public void outgoingPlaybackCountersUseNumericSourceIdentityWithoutPrivateFields()throws Exception {
  org.robolectric.shadows.ShadowLog.clear();PodVideoAnalytics a=new PodVideoAnalytics();a.sourceOrdinal=7;
  a.onBytesTransferred(null,new DataSpec(Uri.parse("https://fixture.invalid/PRIVATE?token=SECRET")),true,128);a.onDroppedVideoFrames(null,3,10);a.retired(12,9000);
  String line=org.robolectric.shadows.ShadowLog.getLogsForTag("PodVideoStats").get(0).msg;
  assertTrue(line.contains("source_ordinal=7 source_generation=12 position_ms=9000"));assertTrue(line.contains("dropped_frames=3"));assertFalse(line.contains("PRIVATE"));assertFalse(line.contains("SECRET"));assertEquals(7,a.snapshot().getLong("sourceOrdinal"));
 }

 @Test public void nativeCompletionReceiptIsOncePerPreparationAndCarriesTheEndedDuration(){
  org.robolectric.shadows.ShadowLog.clear();PodVideoAnalytics a=new PodVideoAnalytics();a.sourceOrdinal=4;a.completed(2,10003,10000);a.completed(2,10003,10000);
  java.util.List<org.robolectric.shadows.ShadowLog.LogItem> lines=org.robolectric.shadows.ShadowLog.getLogsForTag("PodVideoStats");assertEquals(1,lines.size());assertTrue(lines.get(0).msg.startsWith("completed source_ordinal=4 source_generation=2"));assertTrue(lines.get(0).msg.endsWith("duration_ms=10000 ended=1"));
 }

}
