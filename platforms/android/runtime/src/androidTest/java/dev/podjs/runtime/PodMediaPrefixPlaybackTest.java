package dev.podjs.runtime;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
/** Default range transport feeds the real watch decoder without a complete local movie. */
public class PodMediaPrefixPlaybackTest {
 @Test public void productionPrefixUriDecodesAndReusesCachedBytes()throws Exception {
  Instrumentation i=InstrumentationRegistry.getInstrumentation();Context target=i.getTargetContext();byte[] movie;
  try(InputStream input=i.getContext().getAssets().open("tiny.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);movie=out.toByteArray();}
  VideoTestActivity activity=(VideoTestActivity)i.startActivitySync(new Intent(i.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  File root=new File(target.getCacheDir(),"prefix-playback-test-"+System.nanoTime());root.mkdirs();
  ServerSocket server=new ServerSocket(0);ExecutorService http=Executors.newSingleThreadExecutor();Future<?> serving=http.submit(()->{while(!server.isClosed())try(Socket s=server.accept()){s.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream()));String line;int start=0;while((line=input.readLine())!=null&&!line.isEmpty()){if(line.toLowerCase(Locale.ROOT).startsWith("range: bytes="))start=Integer.parseInt(line.substring(line.indexOf('=')+1).split("-")[0]);}int count=Math.max(0,movie.length-start);String response="HTTP/1.1 "+(start>0?"206 Partial Content":"200 OK")+"\r\nContent-Type: video/mp4\r\nContent-Length: "+count+"\r\n"+(start>0?"Content-Range: bytes "+start+"-"+(movie.length-1)+"/"+movie.length+"\r\n":"")+"Connection: close\r\n\r\n";s.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));s.getOutputStream().write(movie,start,count);}catch(Exception e){if(!server.isClosed())throw new RuntimeException(e);}});
  PodMediaPrefixCache cache=new PodMediaPrefixCache(root);PodVideoServices video=new PodVideoServices(target,new PodVideoDecoderBudget(1),cache);
  try {
   String uri=cache.register("movie","http://127.0.0.1:"+server.getLocalPort()+"/tiny.mp4",Map.of("User-Agent","PodJS-test"));
   i.runOnMainSync(()->video.attachToParent(activity.videoHost()));Thread.sleep(150);
   prepare(video,uri);waitFrame(video);assertTrue(cache.stats().getLong("bytes")>0);
   prepare(video,uri);waitFrame(video);assertTrue("second playback reads a prefix",cache.stats().getLong("cacheReadBytes")>0);assertTrue(cache.stats().getLong("bytes")<=cache.maxTotal);
  }finally{video.close();cache.execute("video.cache.clear",new JSONObject());cache.close();server.close();http.shutdownNow();i.runOnMainSync(activity::finish);root.delete();}
 }
 static void prepare(PodVideoServices v,String uri)throws Exception {CountDownLatch done=new CountDownLatch(1);String[] error={null};v.dispatch("video.prepare",new JSONObject().put("url",uri).put("autoplay",true).put("loop",true).put("muted",true).put("bounds",new JSONObject().put("width",80).put("height",80)),new PodVideoServices.Callback(){public void complete(JSONObject value){done.countDown();}public void fail(String c,String m){error[0]=c;done.countDown();}});assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error[0]);}
 static void waitFrame(PodVideoServices v)throws Exception {long end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){CountDownLatch done=new CountDownLatch(1);boolean[] frame={false};v.dispatch("video.state",new JSONObject(),new PodVideoServices.Callback(){public void complete(JSONObject value){frame[0]=value.optBoolean("hasRenderedFirstFrame");done.countDown();}public void fail(String c,String m){done.countDown();}});assertTrue(done.await(3,TimeUnit.SECONDS));if(frame[0])return;Thread.sleep(40);}fail("real first frame unavailable");}
}
