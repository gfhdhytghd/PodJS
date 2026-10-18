package dev.podjs.runtime;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;
import okio.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodLargeDownloadTest {
 private void download(long bytes,long limit,boolean cancel)throws Exception {
  Context c=ApplicationProvider.getApplicationContext();File target=new File(PodTakeover.filesRoot(c),"large-download-test");target.delete();
  AtomicLong readSize=new AtomicLong(),peak=new AtomicLong();Runtime vm=Runtime.getRuntime();long baseline=vm.totalMemory()-vm.freeMemory();
  PodHttpServices[] owner=new PodHttpServices[1];
  OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(new ResponseBody(){
   public MediaType contentType(){return MediaType.get("application/octet-stream");}public long contentLength(){return bytes;}
   public BufferedSource source(){return Okio.buffer(new Source(){long remaining=bytes;boolean stopped;final byte[] block=new byte[8192];
    public long read(Buffer b,long requested)throws IOException {if(stopped)throw new IOException("closed");if(remaining==0)return -1;long n=Math.min(Math.min(requested,8192),remaining);readSize.accumulateAndGet(n,Math::max);peak.accumulateAndGet(vm.totalMemory()-vm.freeMemory(),Math::max);b.write(block,0,(int)n);remaining-=n;if(cancel)owner[0].cancel(91);return n;}
    public Timeout timeout(){return Timeout.NONE;}public void close(){stopped=true;}
   });}
  }).build()).build();
  try(PodHttpServices service=new PodHttpServices(c,client)){
   owner[0]=service;
   try{JSONObject out=service.execute("http.download",new JSONObject().put("requestId",91).put("url","https://example.test/large").put("path",target.getName()).put("maxBytes",limit));assertFalse(cancel);assertTrue(bytes<=limit);assertEquals(bytes,out.getLong("size"));assertEquals(bytes,target.length());}
   catch(IOException e){assertTrue(cancel||bytes>limit);assertEquals(cancel?"download_cancelled":"download_too_large",e.getMessage());assertFalse(target.exists());}
   assertTrue("bounded input chunks",readSize.get()<=32768);
   assertTrue("heap growth "+(peak.get()-baseline),peak.get()-baseline<32L*1024*1024);
   File[] partial=target.getParentFile().listFiles((dir,name)->name.startsWith(target.getName()+".download-"));assertNotNull(partial);assertEquals(0,partial.length);
  }finally{target.delete();}
 }
 @Test public void fortyMiBIsStreamedToDiskWithBoundedHeap()throws Exception {download(40L*1024*1024,64L*1024*1024,false);}
 @Test public void wholeMediaBeyondTheOldCeilingKeepsBoundedHeap()throws Exception {download(80L*1024*1024,256L*1024*1024,false);}
 @Test public void exceedingConfiguredLimitRemovesPartialFile()throws Exception {download(2L*1024*1024,1024*1024,false);}
 @Test public void cancellationRemovesPartialFile()throws Exception {download(40L*1024*1024,64L*1024*1024,true);}
}
