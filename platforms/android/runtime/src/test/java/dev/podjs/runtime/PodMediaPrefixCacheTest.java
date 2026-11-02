package dev.podjs.runtime;
import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaPrefixCacheTest {
 static final int M=1024*1024;
 static class Remote implements DataSource.Factory {
  final byte[] body;final List<DataSpec> opened=Collections.synchronizedList(new ArrayList<>());volatile boolean block;volatile CountDownLatch readEntered=new CountDownLatch(1);
  Remote(int length){body=new byte[length];for(int i=0;i<length;i++)body[i]=(byte)(i*31+7);}
  public DataSource createDataSource(){return new DataSource(){int p,end;DataSpec spec;volatile boolean closed;
   public void addTransferListener(TransferListener l){}public long open(DataSpec s){spec=s;opened.add(s);p=(int)s.position;end=s.length<0?body.length:Math.min(body.length,p+(int)s.length);return end-p;}
   public int read(byte[] b,int offset,int length)throws IOException {readEntered.countDown();while(block&&!closed)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("cancelled");}if(closed)throw new IOException("closed");if(p>=end)return -1;int n=Math.min(length,end-p);System.arraycopy(body,p,b,offset,n);p+=n;return n;}
   public Uri getUri(){return spec==null?null:spec.uri;}public Map<String,List<String>> getResponseHeaders(){return Map.of("Content-Range",List.of("bytes "+(spec==null?0:spec.position)+"-"+(body.length-1)+"/"+body.length));}public void close(){closed=true;}
  };}
 }
 static File root()throws Exception {Context c=ApplicationProvider.getApplicationContext();return java.nio.file.Files.createTempDirectory(c.getCacheDir().toPath(),"prefix-test-").toFile();}
 static String register(PodMediaPrefixCache c,String key)throws Exception{return c.register(key,"https://example.test/"+key,Map.of("Referer","https://example.test/","Cookie","fixture","User-Agent","fixture"));}
 static JSONObject window(String uri,int bytes)throws Exception{return new JSONObject().put("items",new JSONArray().put(new JSONObject().put("uri",uri).put("prefetchBytes",bytes).put("captureBytes",4*M)));}
 static byte[] read(DataSource d,int limit)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while(out.size()<limit&&(n=d.read(b,0,Math.min(b.length,limit-out.size())))!=-1)out.write(b,0,n);return out.toByteArray();}
 static void idle(PodMediaPrefixCache c)throws Exception {long end=System.currentTimeMillis()+5000;while(c.stats().getInt("activePrefetches")>0&&System.currentTimeMillis()<end)Thread.sleep(10);assertEquals(0,c.stats().getInt("activePrefetches"));}
 @Test public void prefixThenRangeAndTailAreByteIdenticalWithAllHeaders()throws Exception {
  Remote r=new Remote(5*M);try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String uri=register(c,"one");c.execute("video.cache.window",window(uri,M));idle(c);assertEquals(M,c.stats().getLong("bytes"));assertEquals(M,c.stats().getLong("downloadedBytes"));
   DataSource d=c.factory(r).createDataSource();assertEquals(r.body.length,d.open(new DataSpec(Uri.parse(uri))));assertArrayEquals(r.body,read(d,r.body.length));d.close();assertEquals(M,r.opened.get(1).position);assertEquals("fixture",r.opened.get(1).httpRequestHeaders.get("Cookie"));assertEquals(4*M,c.stats().getLong("bytes"));assertEquals(M,c.stats().getLong("cacheReadBytes"));
   d.open(new DataSpec.Builder().setUri(uri).setPosition(5*M-64).setLength(64).build());assertArrayEquals(Arrays.copyOfRange(r.body,5*M-64,5*M),read(d,64));d.close();assertEquals(5*M-64,r.opened.get(2).position);assertEquals(64,r.opened.get(2).length);
   // Seek backwards into the prefix needs no new upstream.
   d.open(new DataSpec.Builder().setUri(uri).setPosition(17).setLength(111).build());assertArrayEquals(Arrays.copyOfRange(r.body,17,128),read(d,111));d.close();assertEquals(3,r.opened.size());
  }
 }
 @Test public void emptyPrefixMissNeverWaitsForAnInFlightPrefetch()throws Exception {
  Remote r=new Remote(2*M);r.block=true;try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String uri=register(c,"blocked");c.execute("video.cache.window",window(uri,M));assertTrue(r.readEntered.await(2,TimeUnit.SECONDS));DataSource d=c.factory(r).createDataSource();d.open(new DataSpec(Uri.parse(uri)));assertEquals(2,r.opened.size());d.close();c.execute("video.cache.window",new JSONObject().put("items",new JSONArray()));idle(c);assertEquals(0,c.stats().getLong("bytes"));
  }
 }
 @Test public void clearCancelsWritersAndNoLatePrefixIsRepublished()throws Exception {
  Remote r=new Remote(4*M);r.block=true;try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String uri=register(c,"clear");c.execute("video.cache.window",window(uri,2*M));assertTrue(r.readEntered.await(2,TimeUnit.SECONDS));c.execute("video.cache.clear",new JSONObject());r.block=false;Thread.sleep(150);assertEquals(0,c.stats().getLong("bytes"));assertFalse(c.owns(uri));assertEquals(0,c.stats().getInt("activePrefetches"));
  }
 }
 @Test public void totalBudgetEntryLimitAndNoCredentialsOnDisk()throws Exception {
  Remote r=new Remote(2*M);File root=root();try(PodMediaPrefixCache c=new PodMediaPrefixCache(root,r,6*M)){
   for(int i=0;i<20;i++){String uri=register(c,"bounded"+i);c.execute("video.cache.window",window(uri,M));idle(c);assertTrue(c.stats().getLong("bytes")<=6*M);assertTrue(c.stats().getInt("files")<=16);}
   for(File f:root.listFiles()){assertTrue(f.getName().matches("[a-f0-9]{64}\\.(prefix|length)"));assertTrue(f.length()<=4*M);}
   assertTrue(c.stats().getInt("files")<=6);
  }
 }
 @Test public void changedSourceOrHeadersGetsNewCapabilityAndOriginalHttpStillWorks()throws Exception {
  Remote r=new Remote(M);try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String a=register(c,"identity"),b=c.register("identity","https://example.test/identity",Map.of("Cookie","different"));assertNotEquals(a,b);assertEquals(a,register(c,"identity"));
   DataSource d=c.factory(r).createDataSource();d.open(new DataSpec(Uri.parse("https://example.test/direct")));assertArrayEquals(r.body,read(d,M));d.close();assertEquals(0,c.stats().getLong("bytes"));
  }
 }
 @Test public void productionDefaultTransportActuallyOpensRangeAndHeaders()throws Exception {
  byte[] body=new byte[65536];new Random(7).nextBytes(body);
  try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache c=new PodMediaPrefixCache(root())){
   FutureTask<String> served=new FutureTask<>(()->{try(java.net.Socket socket=server.accept()){socket.setSoTimeout(3000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));StringBuilder request=new StringBuilder();String line;while((line=input.readLine())!=null&&!line.isEmpty())request.append(line).append("\n");socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(body);return request.toString();}});new Thread(served).start();
   String uri=c.register("default","http://127.0.0.1:"+server.getLocalPort()+"/video",Map.of("Cookie","fixture"));DataSource d=c.factory(new Remote(1)).createDataSource();d.open(new DataSpec(Uri.parse(uri)));assertArrayEquals(body,read(d,body.length));d.close();assertTrue(served.get(3,TimeUnit.SECONDS).contains("Cookie: fixture"));assertEquals(body.length,c.stats().getLong("bytes"));
  }
 }
 @Test public void persistedShortCompletePrefixKnowsEofWithoutExtraNetwork()throws Exception {
  Remote r=new Remote(4096);File root=root();String uri;try(PodMediaPrefixCache c=new PodMediaPrefixCache(root,r,6*M)){uri=register(c,"small");c.execute("video.cache.window",window(uri,M));idle(c);}
  r.opened.clear();try(PodMediaPrefixCache c=new PodMediaPrefixCache(root,r,6*M)){assertEquals(uri,register(c,"small"));DataSource d=c.factory(r).createDataSource();assertEquals(4096,d.open(new DataSpec(Uri.parse(uri))));assertArrayEquals(r.body,read(d,8192));d.close();assertEquals(0,r.opened.size());}
 }
 @Test public void lateWindowCannotRestartPrefetchAfterStopOrClear()throws Exception {
  Remote r=new Remote(4*M);r.block=true;try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String uri=register(c,"revision");c.execute("video.cache.window",window(uri,M).put("revision",2));assertTrue(r.readEntered.await(2,TimeUnit.SECONDS));c.execute("video.cache.window",new JSONObject().put("items",new JSONArray()).put("revision",3));c.execute("video.cache.window",window(uri,M).put("revision",2));idle(c);assertEquals(1,r.opened.size());c.execute("video.cache.clear",new JSONObject().put("revision",4));c.execute("video.cache.window",window(uri,M).put("revision",3));assertEquals(0,c.stats().getInt("activePrefetches"));
  }
 }
 @Test public void lowBandwidthSmallerThanOneReadChunkStillMakesBoundedProgress()throws Exception {
  Remote r=new Remote(16384);ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();clock.scheduleAtFixedRate(()->org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(250)),250,250,TimeUnit.MILLISECONDS);try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   java.lang.reflect.Field field=PodMediaPrefixCache.class.getDeclaredField("totalBandwidth");field.setAccessible(true);field.setLong(c,24576);String uri=register(c,"low-rate");long start=System.nanoTime();c.execute("video.cache.window",window(uri,M).put("foregroundBitrate",100000));idle(c);long elapsed=(System.nanoTime()-start)/1000000;assertTrue("rate is bounded: "+elapsed,elapsed>=1700);assertEquals(r.body.length,c.stats().getLong("bytes"));
  }finally{clock.shutdownNow();}
 }
 @Test public void rejectUnregisteredAndMalformedSourcesOrOversizedWindows()throws Exception {
  Remote r=new Remote(M);try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   for(String url:new String[]{"file:///etc/passwd","http://user:pass@example.test/x","http:///no-host"})try{c.register("k",url,Map.of());fail(url);}catch(IllegalArgumentException expected){}
   try{c.register("k","https://example.test/x",Map.of("Cookie","x\r\ny"));fail();}catch(IllegalArgumentException expected){}
   try{c.target("podjs-media-cache://play/"+"a".repeat(64)+".mp4");fail();}catch(IOException expected){}
   String uri=register(c,"limits");try{c.execute("video.cache.window",window(uri,5*M));fail();}catch(IllegalArgumentException expected){}
  }
 }
 @Test public void prefetchEventsBindOpaqueTokenGenerationAndClassify403WithoutSecrets()throws Exception {
  java.util.List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();
  try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache c=new PodMediaPrefixCache(root())){
   FutureTask<Void> served=new FutureTask<>(()->{try(java.net.Socket socket=server.accept()){BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());}return null;});new Thread(served).start();
   c.setResultSink(events::add);String uri=c.register("private-item","http://127.0.0.1:"+server.getLocalPort()+"/private",Map.of("Cookie","fixture-secret"));JSONObject window=window(uri,M);window.getJSONArray("items").getJSONObject(0).put("itemToken","opaque_7").put("generation",42);c.execute("video.cache.window",window);idle(c);served.get(3,TimeUnit.SECONDS);
   assertEquals(1,events.size());JSONObject value=events.get(0).getJSONObject("value");assertEquals("opaque_7",value.getString("itemToken"));assertEquals(42,value.getLong("generation"));assertEquals(403,value.getInt("httpStatus"));assertEquals("http_403",value.getString("category"));assertFalse(events.toString().contains("fixture-secret"));assertFalse(events.toString().contains("127.0.0.1"));
  }
 }
 @Test public void cancelledOldPrefetchCannotPublishLateFailure()throws Exception {
  Remote r=new Remote(2*M);r.block=true;java.util.List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   c.setResultSink(events::add);String uri=register(c,"old");JSONObject window=window(uri,M);window.getJSONArray("items").getJSONObject(0).put("itemToken","old").put("generation",1);c.execute("video.cache.window",window);assertTrue(r.readEntered.await(2,TimeUnit.SECONDS));c.execute("video.cache.window",new JSONObject().put("items",new JSONArray()));r.block=false;Thread.sleep(50);assertTrue(events.isEmpty());
  }
 }
 @Test public void persistentContentReRegistersAcrossRestartWithoutNetworkAndIgnoresRotatingAuth()throws Exception {
  Remote r=new Remote(4096);File directory=root();String uri;
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(directory,r,6*M)){
   uri=c.register("stable-rendition","https://example.test/old?expires=1",Map.of("Cookie","old"),true);
   c.execute("video.cache.window",window(uri,M));idle(c);
  }
  r.opened.clear();
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(directory,r,6*M)){
   String restored=c.register("stable-rendition","https://new.test/new?expires=2",Map.of("Cookie","new"),true);assertEquals(uri,restored);
   DataSource d=c.factory(r).createDataSource();assertEquals(4096,d.open(new DataSpec(Uri.parse(restored))));assertArrayEquals(r.body,read(d,8192));d.close();
   assertEquals(0,r.opened.size());assertEquals(4096,c.stats().getLong("cacheReadBytes"));
  }
 }
 @Test public void persistentTailAndPrefixShareBudgetAndServeSeeksOffline()throws Exception {
  Remote r=new Remote(5*M);File directory=root();String uri;
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(directory,r,6*M)){
   uri=c.register("tail-rendition","https://example.test/old",Map.of(),true);
   c.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",4*M).put("frameKey","fixture"));
   assertEquals(4*M,c.stats().getLong("bytes"));
  }
  r.opened.clear();try(PodMediaPrefixCache c=new PodMediaPrefixCache(directory,r,6*M)){
   String restored=c.register("tail-rendition","https://example.test/fresh",Map.of(),true);DataSource d=c.factory(r).createDataSource();
   d.open(new DataSpec.Builder().setUri(restored).setLength(1024).build());assertArrayEquals(Arrays.copyOf(r.body,1024),read(d,1024));d.close();
   d.open(new DataSpec.Builder().setUri(restored).setPosition(5*M-1024).setLength(1024).build());assertArrayEquals(Arrays.copyOfRange(r.body,5*M-1024,5*M),read(d,1024));d.close();assertEquals(0,r.opened.size());
  }
 }
 @Test public void backgroundPreparationOpensNoNetworkDuringForegroundAndCancelsWithoutPublishing()throws Exception {
  Remote r=new Remote(M);Object foreground=new Object();HostWorkPriority.DEVICE.foregroundMedia(foreground,true);
  ExecutorService worker=Executors.newSingleThreadExecutor();try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   String uri=c.register("priority","https://example.test/video",Map.of(),true);
   Future<?> pending=worker.submit(()->{try{c.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",M).put("frameKey","fixture"));}catch(Exception expected){}});
   long end=System.currentTimeMillis()+2000;while(c.stats().getInt("activePrefetches")==0&&System.currentTimeMillis()<end)Thread.sleep(10);
   assertEquals(1,c.stats().getInt("activePrefetches"));assertEquals(0,r.opened.size());
   c.execute("video.cache.window",new JSONObject().put("items",new JSONArray()));
   pending.get(2,TimeUnit.SECONDS);HostWorkPriority.DEVICE.foregroundMedia(foreground,false);assertEquals(0,r.opened.size());assertEquals(0,c.stats().getLong("bytes"));
  }finally{HostWorkPriority.DEVICE.foregroundMedia(foreground,false);worker.shutdownNow();}
 }

 @Test public void persistentPreparationsEvictPrefixAndTailTogetherWithinBudget()throws Exception {
  Remote r=new Remote(5*M);try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),r,6*M)){
   for(int i=0;i<4;i++){
    String uri=c.register("stock"+i,"https://example.test/"+i,Map.of(),true);
    c.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","frame"+i));
    assertTrue(c.stats().getLong("bytes")<=6*M);
   }
   assertEquals(3,c.stats().getInt("files"));assertEquals(6*M,c.stats().getLong("bytes"));
  }
 }

 @Test public void playbackCounterDiagnosticContainsOnlyFixedNonnegativeNumbers()throws Exception {
  Remote remote=new Remote(M);try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root(),remote,6*M)){
   register(cache,"private-fixture");org.robolectric.shadows.ShadowLog.clear();cache.logPlaybackStats();
   java.util.List<org.robolectric.shadows.ShadowLog.LogItem> rows=org.robolectric.shadows.ShadowLog.getLogsForTag("PodMediaCache");assertEquals(1,rows.size());
   assertTrue(rows.get(0).msg.matches("cache_stats mono_ms=[0-9]+ files=[0-9]+ bytes=[0-9]+ downloadedBytes=[0-9]+ cacheReadBytes=[0-9]+ networkReadBytes=[0-9]+ maxBytes=[0-9]+"));
   assertFalse(rows.get(0).msg.contains("private-fixture"));assertFalse(rows.get(0).msg.contains("Cookie"));
  }
 }

 @Test public void idlePreparationIsNotArtificiallyRateLimitedByAHistoricalBandwidthSample()throws Exception {
  Remote remote=new Remote(M);try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root(),remote,6*M)){
   java.lang.reflect.Field field=PodMediaPrefixCache.class.getDeclaredField("totalBandwidth");field.setAccessible(true);field.setLong(cache,1024);
   String uri=register(cache,"idle-spare-bandwidth");cache.execute("video.cache.window",window(uri,M));
   long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
   while(cache.stats().getInt("activePrefetches")>0&&System.nanoTime()<end)Thread.sleep(10);
   assertEquals("available idle bytes must not wait for a speculative foreground reserve",M,cache.stats().getLong("bytes"));
  }
 }
 @Test public void presentedFirstCardCountersCarryTheNativeSeqAndOnlyFixedNumbers()throws Exception {
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root(),new Remote(M),6*M)){
   org.robolectric.shadows.ShadowLog.clear();cache.logFirstCardStats(42);
   String line=org.robolectric.shadows.ShadowLog.getLogsForTag("PodMediaCache").get(0).msg;
   assertTrue(line.matches("cache_first_card seq=42 mono_ms=[0-9]+ files=[0-9]+ bytes=[0-9]+ downloadedBytes=[0-9]+ cacheReadBytes=[0-9]+ networkReadBytes=[0-9]+ maxBytes=[0-9]+"));
  }
 }
}
