package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Identical test on base and candidate. Loopback only, no JS, account or JNI path. */
public class PodMediaNetworkTest {
 @Test public void firstDecodedFrameFromSlowMirrors()throws Exception {
  String phase=InstrumentationRegistry.getArguments().getString("phase","candidate");
  int count=Integer.parseInt(InstrumentationRegistry.getArguments().getString("samples","5"));
  boolean race=InstrumentationRegistry.getArguments().getString("race","true").equals("true");
  for(String layout:new String[]{"front","tail"}){
   File root=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"dynet-"+System.nanoTime());
   try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root)){
    for(int sample=0;sample<count;sample++){
     String url="http://127.0.0.1:18756/"+layout+"/"+sample+".mp4";
     JSONObject args=new JSONObject().put("key",layout+sample).put("url",url).put("headers",new JSONObject());
     if(race)args.put("alternates",new JSONArray().put("http://localhost:18757/"+layout+"/"+sample+".mp4"));
     String uri=cache.execute("video.cache.register",args).getString("uri");
     PodMediaPrefixCache.Entry entry=cache.target(uri);long began=SystemClock.elapsedRealtime();
     cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(new JSONObject().put("uri",uri).put("prefetchBytes",600*1024).put("captureBytes",4*1024*1024))));
     boolean decoded=false;int polls=0;
     while(SystemClock.elapsedRealtime()-began<15000){
      // Equal read-ahead floor on both versions. Cache-only decoding cannot
      // fetch missing bytes. A metadata hit or a cover never counts as a frame.
      if(entry.file.length()>=128*1024&&(layout.equals("front")||entry.tail().length()>0)){
       polls++;decoded=decode(cache,entry);if(decoded)break;
      }
      Thread.sleep(20);
     }
     long elapsed=SystemClock.elapsedRealtime()-began;
     android.util.Log.i("DYNET",new JSONObject().put("phase",phase).put("layout",layout).put("sample",sample).put("race",race).put("decoded",decoded).put("decodable_ms",elapsed).put("network_bytes",entry.networkBytes).put("prefix_bytes",entry.file.length()).put("tail_bytes",entry.tail().length()).put("requests",entry.rangeRequests).put("polls",polls).toString());
     assertTrue("cache-only frame unavailable",decoded);
     long deadline=SystemClock.elapsedRealtime()+15000;
     while(cache.stats().getInt("activePrefetches")>0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
     assertEquals("prefetch must terminate",0,cache.stats().getInt("activePrefetches"));
    }
   }finally{File[] files=root.listFiles();if(files!=null)for(File file:files)file.delete();root.delete();}
  }
 }
 private static boolean decode(PodMediaPrefixCache cache,PodMediaPrefixCache.Entry entry)throws Exception {
  final boolean[] gap={false};
  try(MediaDataSource bytes=new MediaDataSource(){
   public long getSize(){return entry.length;}
   public int readAt(long position,byte[] target,int offset,int size)throws IOException{
    if(position>=entry.length)return -1;int n=cache.read(entry,position,target,offset,size);
    if(n<=0){gap[0]=true;return -1;}return n;
   }
   public void close(){}
  }){
   MediaMetadataRetriever decoder=new MediaMetadataRetriever();Bitmap frame=null;
   try{decoder.setDataSource(bytes);frame=decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,128,128);return frame!=null&&!gap[0];}
   catch(RuntimeException unavailable){return false;}
   finally{if(frame!=null)frame.recycle();decoder.release();}
  }
 }
}
