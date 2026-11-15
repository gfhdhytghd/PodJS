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
 @Test public void asynchronousTeardownCannotReturnItsPermitBeforeCodecRelease()throws Exception {
  PodVideoCodecGate gate=PodVideoCodecGate.PROCESS;
  java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),finish=new java.util.concurrent.CountDownLatch(1);
  MediaCodecAdapter adapter=new PodVideoCodecAdapters(c->fake(()->{entered.countDown();try{finish.await();}catch(InterruptedException e){throw new RuntimeException(e);}}),"retired",()->1).createAdapter(config("video/avc"));
  Thread worker=new Thread(adapter::release);worker.start();
  PodVideoCodecGate.Permit current=null,next=null;
  try{
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,gate.live());
   current=gate.acquire("current",2);next=gate.acquire("next",1);assertNotNull(current);assertNotNull(next);
   assertEquals(3,gate.live());assertNull(gate.acquire("fourth",2));
   finish.countDown();worker.join(2000);assertFalse(worker.isAlive());assertEquals(2,gate.live());
  }finally{finish.countDown();worker.join(2000);if(current!=null)current.close();if(next!=null)next.close();}
  assertEquals(0,gate.live());
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
