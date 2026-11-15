package dev.podjs.runtime;

import androidx.media3.datasource.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaCooldownConcurrencyTest {
 static final class Server implements AutoCloseable {
  final ServerSocket socket=new ServerSocket(0,50,InetAddress.getLoopbackAddress());
  final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger requests=new AtomicInteger();
  final CountDownLatch admitted=new CountDownLatch(2),shortResponse=new CountDownLatch(1),longResponse=new CountDownLatch(1);
  final boolean gated;
  Server(boolean gated)throws Exception{this.gated=gated;workers.submit(()->{while(!socket.isClosed())try{Socket peer=socket.accept();workers.submit(()->serve(peer));}catch(IOException ignored){}});}
  String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
  void serve(Socket peer){try(Socket current=peer){
   current.setSoTimeout(3000);BufferedReader input=new BufferedReader(new InputStreamReader(current.getInputStream()));String first=input.readLine();if(first==null)return;
   for(String line;(line=input.readLine())!=null&&!line.isEmpty();){}
   requests.incrementAndGet();boolean longer=first.contains("/long"),bad=longer||first.contains("/short");
   if(gated&&bad){admitted.countDown();if(!(longer?longResponse:shortResponse).await(3,TimeUnit.SECONDS))return;}
   int status=bad?(longer?429:403):206,length=bad?0:64;
   current.getOutputStream().write(("HTTP/1.1 "+status+" Fixture\r\nContent-Length: "+length+"\r\n"+(longer?"Retry-After: 120\r\n":"")+(bad?"":"Content-Range: bytes 0-63/1024\r\n")+"Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
   if(length>0)current.getOutputStream().write(new byte[length]);current.getOutputStream().flush();
  }catch(Exception ignored){}}
  public void close()throws Exception{shortResponse.countDown();longResponse.countDown();socket.close();workers.shutdownNow();}
 }
 static int open(DataSource source,String url)throws Exception {
  try{source.open(PodMediaHttpTest.spec(url));return 200;}catch(IOException error){return PodMediaPrefixCache.httpStatus(error);}finally{source.close();}
 }
 @Test public void successfulBodyRemainsReadableButNewRangeIsDeniedAfterPeer403()throws Exception {
  for(boolean direct:new boolean[]{true,false})try(Server server=new Server(false);PodMediaHttp healthy=new PodMediaHttp();PodMediaHttp peer=new PodMediaHttp()){
   DataSource playing=direct?PodVideoHttpDataSources.create(Collections.emptyMap(),null).createDataSource():healthy.createDataSource();
   try{playing.open(PodMediaHttpTest.spec(server.url("/healthy")));assertEquals(16,playing.read(new byte[16],0,16));
    assertEquals(403,open(peer.createDataSource(),server.url("/short")));
    assertEquals(16,playing.read(new byte[16],0,16));assertEquals(32,playing.read(new byte[32],0,32));
    DataSource range=PodVideoHttpDataSources.create(Collections.emptyMap(),null).createDataSource();
    try{range.open(PodMediaHttpTest.spec(server.url("/healthy")).buildUpon().setPosition(32).setLength(16).build());fail();}catch(IOException error){assertEquals(403,PodMediaPrefixCache.httpStatus(error));}finally{range.close();}
    assertEquals(2,server.requests.get());
   }finally{playing.close();}
   if(!direct)try{playing.read(new byte[1],0,1);fail("closed stream must not read");}catch(InterruptedIOException expected){assertEquals("media_cancelled",expected.getMessage());}
  }
 }
 @Test public void realLongRetryAfterExtendsEarlierDefaultAndSurvivesRecover()throws Exception{concurrentDeadlines(false);}
 @Test public void realShortResponseCannotShortenServerRetryAfterOrRemoveItsProtection()throws Exception{concurrentDeadlines(true);}
 private static void concurrentDeadlines(boolean longFirst)throws Exception {
  AtomicLong clock=new AtomicLong();ExecutorService callers=Executors.newFixedThreadPool(2);
  try(Server server=new Server(true);PodMediaHttp http=new PodMediaHttp(clock::get)){
   Future<Integer> shorter=callers.submit(()->open(http.createDataSource(),server.url("/short")));
   Future<Integer> longer=callers.submit(()->open(http.createDataSource(),server.url("/long")));
   assertTrue(server.admitted.await(3,TimeUnit.SECONDS));
   (longFirst?server.longResponse:server.shortResponse).countDown();assertEquals(Integer.valueOf(longFirst?429:403),(longFirst?longer:shorter).get(3,TimeUnit.SECONDS));
   clock.set(1000);(longFirst?server.shortResponse:server.longResponse).countDown();assertEquals(Integer.valueOf(longFirst?403:429),(longFirst?shorter:longer).get(3,TimeUnit.SECONDS));
   clock.set(31_000);assertEquals(429,open(http.createDataSource(),server.url("/healthy")));assertEquals(2,server.requests.get());
   http.recover();long until=longFirst?120_000:121_000;clock.set(until-1);
   assertEquals(429,open(PodVideoHttpDataSources.create(Collections.emptyMap(),null).createDataSource(),server.url("/healthy")));assertEquals(2,server.requests.get());
   clock.set(until+1);assertEquals(200,open(http.createDataSource(),server.url("/healthy")));assertEquals(3,server.requests.get());
  }finally{callers.shutdownNow();}
 }
 @Test public void local403RefusalsDoNotRenewDefaultDeadline()throws Exception {
  AtomicLong clock=new AtomicLong();try(Server server=new Server(false);PodMediaHttp http=new PodMediaHttp(clock::get)){
   assertEquals(403,open(http.createDataSource(),server.url("/short")));
   for(int n=1;n<20;n++){clock.set(n*1000L);assertEquals(403,open(PodVideoHttpDataSources.create(Collections.emptyMap(),null).createDataSource(),server.url("/short")));}
   assertEquals(1,server.requests.get());clock.set(30_001);assertEquals(200,open(http.createDataSource(),server.url("/healthy")));assertEquals(2,server.requests.get());
  }
 }
}
