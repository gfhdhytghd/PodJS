package dev.podjs.runtime;

import androidx.media3.datasource.*;
import org.junit.*;
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
public class PodMediaHttpTest {
 static class Server implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0);final ExecutorService workers=Executors.newCachedThreadPool();
  final AtomicInteger connections=new AtomicInteger(),requests=new AtomicInteger();final List<String> headers=Collections.synchronizedList(new ArrayList<>());
  final int headerDelay,bodyDelay,status;final byte[] body;final CountDownLatch received=new CountDownLatch(1);
  Server(int headerDelay,int bodyDelay,int status,byte value)throws Exception{this.headerDelay=headerDelay;this.bodyDelay=bodyDelay;this.status=status;body=new byte[1024];Arrays.fill(body,value);
   workers.execute(()->{while(!server.isClosed())try{Socket socket=server.accept();connections.incrementAndGet();workers.execute(()->serve(socket));}catch(IOException ignored){}});
  }
  String url(){return "http://127.0.0.1:"+server.getLocalPort()+"/fixture";}
  void serve(Socket socket){try(Socket s=socket){s.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream()));while(true){String line=input.readLine();if(line==null)return;int lo=0,hi=body.length-1;StringBuilder seen=new StringBuilder(line);
   while((line=input.readLine())!=null&&!line.isEmpty()){seen.append('\n').append(line);if(line.toLowerCase(Locale.ROOT).startsWith("range: bytes=")){String[] range=line.substring(line.indexOf('=')+1).split("-",-1);lo=Integer.parseInt(range[0]);if(!range[1].isEmpty())hi=Math.min(hi,Integer.parseInt(range[1]));}}
   requests.incrementAndGet();headers.add(seen.toString());received.countDown();Thread.sleep(headerDelay);int length=status==206?hi-lo+1:0;
   s.getOutputStream().write(("HTTP/1.1 "+status+" Fixture\r\nContent-Length: "+length+"\r\nContent-Range: bytes "+lo+"-"+hi+"/"+body.length+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));s.getOutputStream().flush();Thread.sleep(bodyDelay);
   if(length>0){s.getOutputStream().write(body,lo,length);s.getOutputStream().flush();}
  }}catch(Exception ignored){}}
  public void close()throws Exception{server.close();workers.shutdownNow();}
 }
 static DataSpec spec(String url){return new DataSpec.Builder().setUri(url).setLength(64).setHttpRequestHeaders(Map.of("Cookie","fixture","Referer","https://example.test/")).build();}
 static byte[] read(DataSource source,int size)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[size];int n;while((n=source.read(buf,0,Math.min(buf.length,size-out.size())))>0){out.write(buf,0,n);if(out.size()==size)break;}return out.toByteArray();}
 @Test public void raceSelectsFirstBodyNotFirstHeadersAndNeverMixesBodies()throws Exception {
  try(Server primary=new Server(0,800,206,(byte)1);Server alternate=new Server(60,0,206,(byte)2);PodMediaHttp http=new PodMediaHttp()){
   AtomicReference<String> winner=new AtomicReference<>();DataSource source=http.race(Arrays.asList(primary.url(),alternate.url()),winner::set);
   long start=System.nanoTime();assertEquals(64,source.open(spec(primary.url())));byte[] body=read(source,64);source.close();
   assertEquals(alternate.url(),winner.get());for(byte b:body)assertEquals(2,b);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<750);
   assertEquals(1,primary.requests.get());assertEquals(1,alternate.requests.get());assertTrue(alternate.headers.get(0).contains("Cookie: fixture"));
  }
 }
 @Test public void boundedReadsReuseConnectionWithoutWarmupRequest()throws Exception {
  try(Server server=new Server(0,0,206,(byte)3);PodMediaHttp http=new PodMediaHttp()){
   for(int i=0;i<3;i++){DataSource source=http.createDataSource();source.open(spec(server.url()));assertEquals(64,read(source,64).length);source.close();}
   assertEquals(3,server.requests.get());assertEquals(1,server.connections.get());
  }
 }
 @Test public void forbiddenMirrorStopsAllLaterRequestsAndDoesNotRetry()throws Exception {
  try(Server primary=new Server(0,0,403,(byte)1);Server alternate=new Server(300,0,206,(byte)2);PodMediaHttp http=new PodMediaHttp()){
   DataSource source=http.race(Arrays.asList(primary.url(),alternate.url()),url->{});
   try{source.open(spec(primary.url()));fail("403 must stop race");}catch(IOException failure){assertEquals(403,PodMediaPrefixCache.httpStatus(failure));}finally{source.close();}
   int requests=primary.requests.get()+alternate.requests.get();DataSource later=http.createDataSource();
   try{later.open(spec(alternate.url()));fail("stopped instance must not request again");}catch(IOException failure){assertEquals(403,PodMediaPrefixCache.httpStatus(failure));}finally{later.close();}
   Thread.sleep(80);assertEquals(requests,primary.requests.get()+alternate.requests.get());
  }
 }
 @Test public void closeCancelsBothPendingOpens()throws Exception {
  try(Server primary=new Server(3000,0,206,(byte)1);Server alternate=new Server(3000,0,206,(byte)2);PodMediaHttp http=new PodMediaHttp()){
   DataSource source=http.race(Arrays.asList(primary.url(),alternate.url()),url->{});ExecutorService caller=Executors.newSingleThreadExecutor();
   try{Future<?> result=caller.submit(()->{try{source.open(spec(primary.url()));throw new AssertionError("cancelled open succeeded");}catch(IOException expected){}});
    assertTrue(primary.received.await(2,TimeUnit.SECONDS));assertTrue(alternate.received.await(2,TimeUnit.SECONDS));source.close();result.get(1,TimeUnit.SECONDS);
   }finally{caller.shutdownNow();source.close();}
  }
 }
 @Test public void foregroundSourceBypassesTwoBlockedPrefetchRacers()throws Exception {
  try(Server slow=new Server(3000,0,206,(byte)1);Server slowMirror=new Server(3000,0,206,(byte)2);Server fast=new Server(0,0,206,(byte)3);PodMediaHttp http=new PodMediaHttp()){
   DataSource race=http.race(Arrays.asList(slow.url(),slowMirror.url()),url->{});ExecutorService caller=Executors.newSingleThreadExecutor();
   try{caller.submit(()->{try{race.open(spec(slow.url()));}catch(IOException expected){}});
    assertTrue(slow.received.await(2,TimeUnit.SECONDS));assertTrue(slowMirror.received.await(2,TimeUnit.SECONDS));
    long began=System.nanoTime();DataSource foreground=http.createDataSource();foreground.open(spec(fast.url()));assertEquals(64,read(foreground,64).length);foreground.close();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1000);
   }finally{race.close();caller.shutdownNow();}
  }
 }

}
