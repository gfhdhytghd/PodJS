package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=34)
public class PodVideoCodecAdaptersTest {
 private MediaCodecAdapter.Configuration config(String mime){
  MediaFormat media=new MediaFormat();media.setString(MediaFormat.KEY_MIME,mime);
  return MediaCodecAdapter.Configuration.createForVideoDecoding(null,media,new Format.Builder().setSampleMimeType(mime).build(),null,null);
 }
 private MediaCodecAdapter fake(Runnable release){return (MediaCodecAdapter)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{MediaCodecAdapter.class},(p,m,a)->{if(m.getName().equals("release"))release.run();return null;});}
 @Test public void releaseKeepsPermitUntilUnderlyingCodecReturns()throws Exception {
  PodVideoCodecGate gate=PodVideoCodecGate.PROCESS;int baseline=gate.live();
  MediaCodecAdapter adapter=new PodVideoCodecAdapters(c->fake(()->assertEquals(baseline+1,gate.live())),"test",()->1).createAdapter(config("video/avc"));
  assertEquals(baseline+1,gate.live());adapter.release();assertEquals(baseline,gate.live());
 }
 @Test public void factoryFailureReturnsReservation()throws Exception {
  int baseline=PodVideoCodecGate.PROCESS.live();
  try{new PodVideoCodecAdapters(c->{throw new java.io.IOException("fixture");},"test",()->1).createAdapter(config("video/hevc"));fail();}catch(java.io.IOException expected){assertEquals("fixture",expected.getMessage());}
  assertEquals(baseline,PodVideoCodecGate.PROCESS.live());
 }
 @Test public void audioAdapterDoesNotConsumeVideoQuota()throws Exception {
  int baseline=PodVideoCodecGate.PROCESS.live();MediaCodecAdapter adapter=new PodVideoCodecAdapters(c->fake(()->{}),"test",()->1).createAdapter(config("audio/mp4a-latm"));
  assertEquals(baseline,PodVideoCodecGate.PROCESS.live());adapter.release();assertEquals(baseline,PodVideoCodecGate.PROCESS.live());
 }
}
