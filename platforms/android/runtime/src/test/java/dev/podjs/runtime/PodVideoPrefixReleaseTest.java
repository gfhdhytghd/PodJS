package dev.podjs.runtime;
import android.content.Context;
import android.media.*;
import android.os.*;
import android.view.Surface;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPrefixReleaseTest {
 static Field f(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 @Implements(MediaExtractor.class) public static class Extractor {
  @Implementation protected void __constructor__(){}
  @Implementation protected void setDataSource(MediaDataSource data){}
  @Implementation protected int getTrackCount(){return 1;}
  @Implementation protected MediaFormat getTrackFormat(int i){return MediaFormat.createVideoFormat("video/avc",240,240);}
  @Implementation protected void selectTrack(int i){}
  @Implementation protected void release(){}
 }
 @Implements(MediaCodec.class) public static class Codec {
  static int created,released;
  @Implementation protected static MediaCodec createByCodecName(String name){created++;return Shadow.newInstanceOf(MediaCodec.class);}
  @Implementation protected void configure(MediaFormat f,Surface s,MediaCrypto c,int flags){throw new IllegalArgumentException("probe_configure_failure");}
  @Implementation protected void stop(){throw new IllegalStateException("probe_stop_before_configured");}
  @Implementation protected void release(){released++;}
 }
 @Test @Config(shadows={Extractor.class,Codec.class}) public void stopFailureStillReturnsPermitAfterSuccessfulRelease()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();
  MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",240,240);
  MediaCodecInfo.CodecCapabilities caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(fmt).setColorFormats(new int[]{MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible}).build();
  ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("c2.android.avc.decoder").setIsSoftwareOnly(true).setCapabilities(caps).build());
  PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"review-prefix"));
  PodVideoPrefixFrames frames=new PodVideoPrefixFrames(cache,new Handler(Looper.getMainLooper()),new PodVideoKeyframes(),s->{});
  String uri=cache.register("probe","http://127.0.0.1:9/not-requested.mp4",Collections.emptyMap());
  PodMediaPrefixCache.Entry entry=cache.target(uri);
  ((Map)f(PodVideoPrefixFrames.class,"wanted").get(frames)).put(entry,0L);
  Method decode=PodVideoPrefixFrames.class.getDeclaredMethod("decode",PodMediaPrefixCache.Entry.class,boolean.class);decode.setAccessible(true);
  try{
   for(int i=0;i<2;i++)try{decode.invoke(frames,entry,true);fail("expected configure/stop failure");}catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
   assertEquals(2,Codec.created);assertEquals(2,Codec.released);assertEquals(0,PodVideoCodecGate.PROCESS.live());
   PodVideoCodecGate.Permit next=PodVideoCodecGate.PROCESS.acquire("next_prefix",0);assertNotNull(next);next.close();assertEquals(0,PodVideoCodecGate.PROCESS.live());
  }finally{frames.close();cache.close();}
 }

}
