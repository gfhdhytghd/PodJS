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
   java.lang.reflect.Field field=PodMediaPrefixCache.class.getDeclaredField("totalBandwidth");field.setAccessible(true);field.setLong(c,8192);String uri=register(c,"low-rate");long start=System.nanoTime();c.execute("video.cache.window",window(uri,M));idle(c);long elapsed=(System.nanoTime()-start)/1000000;assertTrue("rate is bounded: "+elapsed,elapsed>=1700);assertEquals(r.body.length,c.stats().getLong("bytes"));
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
}
