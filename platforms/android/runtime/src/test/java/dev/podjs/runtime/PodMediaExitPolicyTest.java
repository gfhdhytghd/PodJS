package dev.podjs.runtime;

import androidx.media3.datasource.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaExitPolicyTest {
 static void denied(DataSource source,DataSpec spec,int status)throws Exception {
  try{source.open(spec);fail("request must remain blocked");}catch(IOException error){assertEquals(status,PodMediaPrefixCache.httpStatus(error));}finally{source.close();}
 }
 @Test public void resourceRetryBlocksDirectHttpAndAllowsLocalFileUntilReady()throws Exception {
  try(PodMediaHttpTest.Server server=new PodMediaHttpTest.Server(0,0,206,(byte)1)){
   android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
   PodVideoHttpDataSources.PrepareGate gate=new PodVideoHttpDataSources.PrepareGate(true);
   DataSource.Factory factory=PodVideoHttpDataSources.playback(context,null,Map.of(),false,gate);
   for(String path:List.of("video","audio")){
    DataSource remote=factory.createDataSource();
    try{assertThrows(PodVideoHttpDataSources.RetryCacheMiss.class,()->remote.open(PodMediaHttpTest.spec(server.url()+"/"+path)));}
    finally{remote.close();}
   }
   assertEquals(0,server.connections.get());
   File file=File.createTempFile("retry-source",".mp4",context.getCacheDir());
   try{
    Files.write(file.toPath(),new byte[]{1,2,3});
    DataSource local=factory.createDataSource();
    try{assertEquals(3,local.open(new DataSpec(android.net.Uri.fromFile(file))));assertArrayEquals(new byte[]{1,2,3},PodMediaHttpTest.read(local,3));}
    finally{local.close();}
   }finally{assertTrue(file.delete());}
   gate.ready();
   DataSource remote=factory.createDataSource();
   try{remote.open(PodMediaHttpTest.spec(server.url()));assertEquals(1,server.requests.get());}
   finally{remote.close();}
  }
 }
 @Test public void cachedRetryAfterGuardsDirectBypassAudioAndFallbackUntilOriginalDeadline()throws Exception {
  AtomicLong clock=new AtomicLong();try(PodMediaHttpTest.Server server=new PodMediaHttpTest.Server(0,0,429,(byte)1);PodMediaHttp http=new PodMediaHttp(clock::get);
      PodMediaPrefixCache cache=new PodMediaPrefixCache(Files.createTempDirectory("media-exits").toFile(),http,1024*1024)){
   server.retryAfter="120";denied(http.createDataSource(),PodMediaHttpTest.spec(server.url()),429);assertEquals(1,server.requests.get());http.recover();
   DataSource.Factory direct=PodVideoHttpDataSources.create(Collections.emptyMap(),null);
   for(String path:List.of("main","audio","bypass"))denied(direct.createDataSource(),PodMediaHttpTest.spec(server.url()+"/"+path),429);
   denied(cache.factory(direct).createDataSource(),PodMediaHttpTest.spec(server.url()),429);assertEquals(1,server.requests.get());
   clock.set(119_999);denied(direct.createDataSource(),PodMediaHttpTest.spec(server.url()),429);assertEquals(1,server.requests.get());
   clock.set(120_001);denied(direct.createDataSource(),PodMediaHttpTest.spec(server.url()),429);assertEquals(2,server.requests.get());
  }
 }
 @Test public void completeStore403CannotStopUnrelatedForegroundOrigin()throws Exception {
  try(PodMediaHttpTest.Server bad=new PodMediaHttpTest.Server(0,0,403,(byte)1);PodMediaHttpTest.Server good=new PodMediaHttpTest.Server(0,0,206,(byte)2);
      PodMediaPrefixCache cache=new PodMediaPrefixCache(Files.createTempDirectory("stock-scope").toFile())){
   cache.execute("video.cache.local.configure",new JSONObject().put("minFreeBytes",0).put("minBattery",0));
   String badUri=cache.register("stock",bad.url(),Collections.emptyMap()),goodUri=cache.register("foreground",good.url(),Collections.emptyMap());
   try{cache.execute("video.cache.local.prepare",new JSONObject().put("key","stock").put("uri",badUri).put("metadata",new JSONObject()));fail();}catch(IOException error){assertEquals(403,PodMediaPrefixCache.httpStatus(error));}
   assertFalse(cache.trafficStopped());DataSource foreground=cache.factory(PodVideoHttpDataSources.create(Collections.emptyMap(),null)).createDataSource();
   try{foreground.open(PodMediaHttpTest.spec(goodUri));assertEquals(64,PodMediaHttpTest.read(foreground,64).length);}finally{foreground.close();}
   assertEquals(1,bad.requests.get());assertEquals(1,good.requests.get());
  }
 }
 @Test public void directHttpCredentialsAreRejectedBeforeConnecting()throws Exception {
  try(PodMediaHttpTest.Server server=new PodMediaHttpTest.Server(0,0,206,(byte)1)){
   DataSource source=PodVideoHttpDataSources.create(Map.of("cOoKiE","offline-fixture"),null).createDataSource();
   try{source.open(PodMediaHttpTest.spec(server.url()));fail();}catch(IOException error){assertEquals("media_credentials_origin_rejected",error.getMessage());}finally{source.close();}
   assertEquals(0,server.requests.get());assertEquals(0,server.connections.get());
  }
 }
 @Test public void directRedirectCannotReachAnOriginInCooldown()throws Exception {
  try(PodMediaHttpTest.Server limited=new PodMediaHttpTest.Server(0,0,429,(byte)1);PodMediaHttpTest.Server alias=new PodMediaHttpTest.Server(0,0,302,(byte)2);PodMediaHttp http=new PodMediaHttp()){
   limited.retryAfter="120";alias.location=limited.url();denied(http.createDataSource(),PodMediaHttpTest.spec(limited.url()),429);
   denied(PodVideoHttpDataSources.create(Collections.emptyMap(),null).createDataSource(),PodMediaHttpTest.spec(alias.url()),429);
   assertEquals(1,limited.requests.get());assertEquals(1,alias.requests.get());
  }
 }
 @Test public void cancelledQueuedPrepareHasNoNativeDownload()throws Exception {
  try(PodMediaHttpTest.Server server=new PodMediaHttpTest.Server(0,0,206,(byte)1);PodMediaPrefixCache cache=new PodMediaPrefixCache(Files.createTempDirectory("queued-owner").toFile())){
   String uri=cache.register("old",server.url(),Collections.emptyMap());AtomicInteger checks=new AtomicInteger();
   try{cache.execute("video.cache.local.prepare",new JSONObject().put("key","old").put("uri",uri).put("metadata",new JSONObject()),()->checks.getAndIncrement()==0);fail();}
   catch(IOException error){assertEquals("media_request_cancelled",error.getMessage());}
   assertEquals(0,server.requests.get());
  }
 }
 @Test public void directSourceReopensForSeekWithoutLosingHeadersOrPolicy()throws Exception {
  try(PodMediaHttpTest.Server server=new PodMediaHttpTest.Server(0,0,206,(byte)3)){
   DataSource source=PodVideoHttpDataSources.create(Map.of("X-Playback","fixture"),null).createDataSource();
   for(int n=0;n<3;n++)try{source.open(PodMediaHttpTest.spec(server.url()).buildUpon().setPosition(n*64).build());assertEquals(64,PodMediaHttpTest.read(source,64).length);}finally{source.close();}
   assertEquals(3,server.requests.get());for(String headers:server.headers)assertTrue(headers.toLowerCase(Locale.ROOT).contains("x-playback: fixture"));
  }
 }

}
