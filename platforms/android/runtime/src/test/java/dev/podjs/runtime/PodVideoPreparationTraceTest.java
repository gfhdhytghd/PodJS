package dev.podjs.runtime;
import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
/** Correlation/privacy regression. Real network and decoder timings need a device. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoPreparationTraceTest {
 @Test public void prewarmUsesRequestedSequenceAndNativeGenerationAcrossNetworkAndDecode() throws Exception {
  long seq=PodBenchmark.begin("prewarm_requested",new JSONObject().put("generation",7));ShadowLog.clear();
  PodVideoAnalytics a=new PodVideoAnalytics();a.preparation(seq,12,true);
  a.onBytesTransferred(null,null,true,8192);a.onVideoDecoderInitialized(null,"PRIVATE_DECODER_NAME",0,20);
  a.onRenderedFirstFrame(null,null,0);a.stage("timeout");
  List<ShadowLog.LogItem> rows=ShadowLog.getLogsForTag("DYBENCH");assertEquals(5,rows.size());
  for(ShadowLog.LogItem row:rows){assertTrue(row.msg.contains("code=prepare_stage seq="+seq+" "));assertTrue(row.msg.contains("source_generation=12"));assertTrue(row.msg.contains("is_prewarm=true"));assertFalse(row.msg.contains("PRIVATE"));}
  assertTrue(rows.get(0).msg.contains("prepare_stage=start"));assertTrue(rows.get(1).msg.contains("prepare_stage=network_first_byte"));
  assertTrue(rows.get(2).msg.contains("prepare_stage=decoder_configured"));assertTrue(rows.get(3).msg.contains("prepare_stage=first_frame"));assertTrue(rows.get(4).msg.contains("network_bytes=8192"));
 }
 @Test public void guestCannotForgeNativeStagesAndPrivateFieldsAreRejected() throws Exception {
  assertEquals(0,PodBenchmark.dispatch("{\"code\":\"prepare_stage\",\"prepare_stage\":\"first_frame\"}"));
  long seq=PodBenchmark.begin("prewarm_requested",new JSONObject());
  assertEquals(0,PodBenchmark.mark("prepare_stage",seq,new JSONObject().put("prepare_stage","PRIVATE_URL")));
  assertEquals(0,PodBenchmark.mark("prepare_stage",seq,new JSONObject().put("prepare_stage","start").put("url","PRIVATE")));
  assertEquals(0,PodBenchmark.mark("prepare_stage",seq,new JSONObject().put("prepare_stage","start").put("network_bytes",-1)));
  assertEquals(0,PodBenchmark.mark("prepare_stage",seq,new JSONObject().put("prepare_stage","start").put("playback_state",5)));
 }
 @Test public void selectedSourceMeasuresInputAndCodecFactoryOnTheSameGeneration() throws Exception {
  long seq=PodBenchmark.begin("startup_stage",new JSONObject().put("startup_stage","candidate_start"));
  ShadowLog.clear();
  PodVideoAnalytics a=new PodVideoAnalytics();a.startupSeq=seq;a.clientGeneration=8;a.preparation(seq,42,false);
  a.onTransferInitializing(null,new androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://fixture.invalid/private")),true);
  org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(152));
  a.onBytesTransferred(null,null,true,4096);
  a.timedStage("admission_retry",100);
  android.media.MediaFormat format=android.media.MediaFormat.createVideoFormat("video/avc",240,240);
  androidx.media3.exoplayer.mediacodec.MediaCodecAdapter codec=(androidx.media3.exoplayer.mediacodec.MediaCodecAdapter)
      java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.class},(proxy,method,args)->null);
  PodVideoCodecAdapters wrapped=new PodVideoCodecAdapters(config->{
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(27));return codec;
  },"fixture",this,()->42,()->PodVideoCodecGate.CURRENT,a);
  wrapped.createAdapter(androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration.createForVideoDecoding(null,format,
      new androidx.media3.common.Format.Builder().setSampleMimeType("video/avc").build(),null,null)).release();
  a.onVideoDecoderInitialized(null,"private-decoder",0,27);
  List<ShadowLog.LogItem> rows=ShadowLog.getLogsForTag("DYBENCH");
  assertTrue(rows.stream().anyMatch(row->row.msg.contains("prepare_stage=network_first_byte")&&row.msg.contains("ttfb_ms=152")));
  assertTrue(rows.stream().anyMatch(row->row.msg.contains("prepare_stage=admission_retry")&&row.msg.contains("duration_ms=100")));
  assertTrue(rows.stream().anyMatch(row->row.msg.contains("prepare_stage=codec_admission_wait")&&row.msg.contains("duration_ms=0")));
  assertTrue(rows.stream().anyMatch(row->row.msg.contains("prepare_stage=codec_factory")&&row.msg.contains("duration_ms=27")));
  for(ShadowLog.LogItem row:rows){assertTrue(row.msg.contains("seq="+seq+" "));assertTrue(row.msg.contains("source_generation=42"));assertTrue(row.msg.contains("generation=8"));assertFalse(row.msg.contains("private"));}
  assertEquals(0,PodVideoCodecGate.PROCESS.live());
 }
}
