package dev.podjs.runtime;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.datasource.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodMediaLocalStoreTest {
 File root()throws Exception{return Files.createTempDirectory("local-media-test").toFile();}
 PodMediaPrefixCache cache(File root){return new PodMediaPrefixCache(root,new PodMediaPrefixCacheTest.Remote(256*1024),16L<<20);}
 String seed(PodMediaPrefixCache c,String key,int size,boolean watched,long time)throws Exception {
  PodMediaLocalStore store=c.localStore;store.root.mkdirs();String token=PodMediaLocalStore.token(key);File media=store.file(token,".mp4");Files.write(media.toPath(),new byte[size]);
  String uri=Uri.fromFile(media).toString(),binding=PodVideoFrameCache.binding(new JSONObject().put("url",uri),c);
  Bitmap pixels=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);JSONObject frame;
  try{frame=new PodVideoFrameCache(c.frameRoot).save("local:"+token,binding,0,0,pixels);}finally{pixels.recycle();}
  File image=store.frame(token);image.getParentFile().mkdirs();Files.copy(new File(c.frameRoot,frame.getString("path")).toPath(),image.toPath());frame.put("path","local-media-frames/"+token+".jpg");
  JSONObject value=new JSONObject().put("version",2).put("audioTracks",1).put("videoTracks",1).put("key",key).put("metadata",new JSONObject().put("title",key)).put("bytes",size).put("sha256",PodMediaLocalStore.digest(media)).put("frame",frame).put("frameSha256",PodMediaLocalStore.digest(image)).put("watched",watched).put("storedAt",time);
  Files.write(store.file(token,".json").toPath(),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return token;
 }
 @Test public void startupPreviewRejectsUnverifiedPathsAndCancelledDecodeCannotAttach()throws Exception {
  try(PodMediaPrefixCache c=cache(root())){
   android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
   android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
   PodMediaStartupPreview preview=new PodMediaStartupPreview(context,main,c);
   android.widget.FrameLayout parent=new android.widget.FrameLayout(context);preview.attach(parent);
   assertThrows(IllegalArgumentException.class,()->preview.present(new JSONObject().put("uri","file:///etc/passwd"),()->true,v->fail("invalid path accepted")));
   String token=seed(c,"preview",4096,false,1);
   JSONObject args=new JSONObject().put("uri",Uri.fromFile(c.localStore.file(token,".mp4")).toString()).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",200).put("height",200));
   preview.present(args,()->false,v->assertFalse(v.optBoolean("presented")));
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertEquals(0,parent.getChildCount());assertNull(preview.bitmap);
   preview.generation=9;preview.bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);Bitmap pixels=preview.bitmap;
   preview.hide(8);assertFalse(pixels.isRecycled());preview.hide(9);assertTrue(pixels.isRecycled());
  }
 }
 @Test public void requiredAudioRejectsSilentCompleteVideo()throws Exception {
  assertThrows(IOException.class,()->PodMediaLocalStore.requireTracks(0,1,true));
  assertThrows(IOException.class,()->PodMediaLocalStore.requireTracks(1,0,true));
  PodMediaLocalStore.requireTracks(1,1,true);PodMediaLocalStore.requireTracks(0,1,false);
 }
 @Test public void restartCleansPartialsAndRejectsCorruptCompleteFiles()throws Exception {
  File root=root();String good,bad;
  try(PodMediaPrefixCache c=cache(root)){good=seed(c,"good",8192,false,1);bad=seed(c,"bad",8192,false,2);Files.write(c.localStore.file(bad,".mp4").toPath(),new byte[]{1});Files.write(c.localStore.file(good,".part").toPath(),new byte[]{1});Files.write(c.localStore.file(PodMediaLocalStore.token("orphan"),".mp4").toPath(),new byte[]{1});}
  try(PodMediaPrefixCache c=cache(root)){assertFalse(c.localStore.file(good,".part").exists());assertNotNull(c.localStore.lookupToken(good));assertNull(c.localStore.lookupToken(bad));assertEquals(1,c.localStore.list().length());assertFalse(c.localStore.file(PodMediaLocalStore.token("orphan"),".mp4").exists());}
 }
 @Test public void evictionUsesWatchedThenOldestAndHonorsCountAndBytes()throws Exception {
  try(PodMediaPrefixCache c=cache(root())){String oldest=seed(c,"old",65536,false,1),watched=seed(c,"watched",65536,true,3),newer=seed(c,"new",65536,false,2);
   c.localStore.configure(new JSONObject().put("count",2).put("totalBytes",132*1024));assertFalse(c.localStore.file(watched,".mp4").exists());assertNotNull(c.localStore.lookupToken(oldest));assertNotNull(c.localStore.lookupToken(newer));
   assertTrue(c.localStore.trim(null,65536));assertFalse(c.localStore.file(oldest,".mp4").exists());assertNotNull(c.localStore.lookupToken(newer));
   assertThrows(IllegalArgumentException.class,()->c.localStore.configure(new JSONObject().put("entryBytes",33*1024*1024)));assertThrows(IllegalArgumentException.class,()->c.localStore.configure(new JSONObject().put("totalBytes",129*1024*1024)));
  }
 }
 @Test public void expiredHeadNeverOpensOrContinuesUpstream()throws Exception {
  PodMediaPrefixCacheTest.Remote remote=new PodMediaPrefixCacheTest.Remote(1000);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){String uri=c.register("expired","https://fixture.invalid/video",Map.of());c.target(uri).expiresAt=0;
   DataSource player=c.factory(remote).createDataSource();assertThrows(IOException.class,()->player.open(new DataSpec(Uri.parse(uri))));assertEquals(0,remote.opened.size());player.close();c.target(uri).expiresAt=Long.MAX_VALUE;player.open(new DataSpec(Uri.parse(uri)));c.target(uri).expiresAt=0;
   assertThrows(IOException.class,()->player.read(new byte[8],0,8));assertEquals(1,remote.opened.size());player.close();}
 }
 @Test public void trafficLatchAndResourceGatesPauseWithoutDeletingInventory()throws Exception {
  try(PodMediaPrefixCache c=cache(root())){String token=seed(c,"local",4096,false,1);assertTrue(c.preStoreAllowed());c.batteryPercent=()->10;assertFalse(c.preStoreAllowed());c.batteryPercent=()->100;Object player=new Object();c.foregroundBusy(player,true);assertFalse(c.preStoreAllowed());c.foregroundBusy(player,false);assertTrue(c.preStoreAllowed());c.stopTraffic();assertFalse(c.preStoreAllowed());String uri=c.register("blocked","https://fixture.invalid/blocked",Map.of());assertThrows(IOException.class,()->c.checkNetwork(c.target(uri)));assertNotNull(c.localStore.lookupToken(token));}
 }
 @Test public void oversizedCompleteStopsAfterLengthProbeAndDeletesPartial()throws Exception {
  PodMediaPrefixCacheTest.Remote remote=new PodMediaPrefixCacheTest.Remote(512*1024);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){c.localStore.configure(new JSONObject().put("entryBytes",128*1024));String uri=c.register("large","https://fixture.invalid/large",Map.of());assertThrows(IOException.class,()->c.execute("video.cache.local.prepare",new JSONObject().put("key","large").put("uri",uri).put("metadata",new JSONObject())));assertEquals(1,remote.opened.size());assertEquals(0,c.localStore.list().length());assertFalse(c.localStore.file(PodMediaLocalStore.token("large"),".part").exists());}
 }
 @Test public void onlyVerifiedInventoryGrantsLocalPlayerPaths()throws Exception {
  try(PodMediaPrefixCache c=cache(root())){String token=seed(c,"playable",4096,false,1),uri=Uri.fromFile(c.localStore.file(token,".mp4")).toString();assertFalse(c.ownsLocal(uri));assertNotNull(c.localStore.lookupToken(token));assertTrue(c.ownsLocal(uri));
   android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
   PodVideoServices video=new PodVideoServices(context,new PodVideoDecoderBudget(1),c);java.lang.reflect.Method source=PodVideoServices.class.getDeclaredMethod("sourceValue",String.class);source.setAccessible(true);assertEquals(uri,source.invoke(video,uri));assertThrows(java.lang.reflect.InvocationTargetException.class,()->source.invoke(video,"file:///etc/passwd"));video.close();
   c.localStore.remove(token);assertFalse(c.ownsLocal(uri));}
 }

 @Test public void foreground403NotifiesGuestWithoutAnyPrefetchJob()throws Exception {
  java.util.List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();
  try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache c=new PodMediaPrefixCache(root())){
   java.util.concurrent.FutureTask<Void> served=new java.util.concurrent.FutureTask<>(()->{try(java.net.Socket socket=server.accept()){BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());}return null;});new Thread(served).start();
   c.setResultSink(events::add);String uri=c.register("foreground","http://127.0.0.1:"+server.getLocalPort()+"/private",Map.of());DataSource player=c.factory(new PodMediaPrefixCacheTest.Remote(1000)).createDataSource();
   assertThrows(IOException.class,()->player.open(new DataSpec(Uri.parse(uri))));player.close();served.get(3,java.util.concurrent.TimeUnit.SECONDS);assertTrue(c.trafficStopped());assertEquals(1,events.size());JSONObject event=events.get(0).getJSONObject("value");assertEquals("traffic-stop",event.getString("itemToken"));assertEquals(403,event.getInt("httpStatus"));assertFalse(events.toString().contains("127.0.0.1"));
  }
 }

 @Test public void dywaitCDefaultsAndClosedSizeFailures()throws Exception {
  try(PodMediaPrefixCache c=cache(root())){
   assertEquals(32L<<20,c.localStore.entryLimit);assertEquals(128L<<20,c.localStore.totalLimit);assertEquals(512L<<20,c.localStore.minFree);
   c.localStore.configure(new JSONObject().put("entryBytes",16L<<20).put("totalBytes",64L<<20).put("minFreeBytes",256L<<20));
   assertEquals(16L<<20,c.localStore.entryLimit);assertEquals(256L<<20,c.localStore.minFree);
  }
 }
 static final class ProbeSource implements DataSource.Factory {
  final List<DataSpec> requests=new java.util.concurrent.CopyOnWriteArrayList<>();final long video,audio;final boolean range;
  ProbeSource(long video,long audio,boolean range){this.video=video;this.audio=audio;this.range=range;}
  public DataSource createDataSource(){return new DataSource(){DataSpec spec;boolean read;
   public void addTransferListener(TransferListener l){}public long open(DataSpec s){spec=s;requests.add(s);return androidx.media3.common.C.LENGTH_UNSET;}
   public int read(byte[] b,int off,int n){if(read)return -1;read=true;b[off]=1;return 1;}public Uri getUri(){return spec.uri;}public void close(){}
   public Map<String,List<String>> getResponseHeaders(){long size=spec.uri.getPath().contains("audio")?audio:video;return range?Map.of("cOnTeNt-RaNgE",List.of("bytes "+spec.position+"-"+spec.position+"/"+(size==0?"*":Long.toString(size)))):Map.of();}
  };}
 }
 @Test public void dywaitCFirstRangeLearnsTotalEvenWhenOpenLengthIsUnknown()throws Exception {
  ProbeSource remote=new ProbeSource(20L<<20,0,true);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){
   String uri=c.register("probe","https://fixture.invalid/video",Map.of());PodMediaPrefixCache.Job job=c.beginPreparation(uri,1);
   assertEquals(1,c.loadRange(job,0,1).length);assertEquals(20L<<20,c.target(uri).length);assertEquals(1,remote.requests.size());assertEquals(1,remote.requests.get(0).length);c.endPreparation(job);
  }
 }
 @Test public void dywaitCUnknownSizeHasItsOwnFailure()throws Exception {
  ProbeSource remote=new ProbeSource(0,0,false);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){
   String uri=c.register("unknown","https://fixture.invalid/video",Map.of());IOException error=assertThrows(IOException.class,()->c.execute("video.cache.local.prepare",new JSONObject().put("key","unknown").put("uri",uri).put("metadata",new JSONObject())));
   assertEquals("complete_size_unknown",error.getMessage());assertEquals(1,remote.requests.size());assertEquals(0,c.localStore.list().length());
  }
 }
 @Test public void dywaitCCompanionCountsTowardItemBudgetBeforeDownload()throws Exception {
  ProbeSource remote=new ProbeSource(20L<<20,15L<<20,true);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){
   String uri=c.register("dash","https://fixture.invalid/video",Map.of(),true,Collections.emptyList(),"https://fixture.invalid/audio");
   IOException error=assertThrows(IOException.class,()->c.execute("video.cache.local.prepare",new JSONObject().put("key","dash").put("uri",uri).put("metadata",new JSONObject()).put("requireAudio",true)));
   assertEquals("complete_over_budget",error.getMessage());assertEquals(2,remote.requests.size());for(DataSpec spec:remote.requests)assertEquals(1,spec.length);assertEquals(0,c.localStore.list().length());
  }
 }

 @Test public void dywaitCClosedFailureReasonsDoNotLeakMessages()throws Exception {
  assertEquals("size_unknown",PodMediaLocalStore.failureReason(new IOException("complete_size_unknown")));
  assertEquals("over_budget",PodMediaLocalStore.failureReason(new IOException("complete_over_budget")));
  assertEquals("io",PodMediaLocalStore.failureReason(new IOException("https://private.invalid/?token=fixture")));
  try(PodMediaPrefixCache c=cache(root())){assertEquals(31L<<20,c.localStore.checkedInputBytes(30L<<20,1L<<20));assertThrows(IOException.class,()->c.localStore.checkedInputBytes(31L<<20,2L<<20));assertThrows(IOException.class,()->c.localStore.checkedInputBytes(1,-1));}
 }

 @Test public void dywaitCAudio403StopsBeforeAnyFullTrackDownload()throws Exception {
  try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache c=new PodMediaPrefixCache(root())){
   java.util.concurrent.FutureTask<Void> served=new java.util.concurrent.FutureTask<>(()->{for(int i=0;i<2;i++)try(java.net.Socket socket=server.accept()){
    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
    String headers=i==0?"HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/20971520\r\nContent-Length: 1\r\nConnection: close\r\n\r\n":"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    socket.getOutputStream().write(headers.getBytes());if(i==0)socket.getOutputStream().write(1);
   }return null;});new Thread(served).start();
   String host="http://127.0.0.1:"+server.getLocalPort(),uri=c.register("audio-stop",host+"/video",Map.of(),true,Collections.emptyList(),host+"/audio");
   Exception error=assertThrows(Exception.class,()->c.execute("video.cache.local.prepare",new JSONObject().put("key","audio-stop").put("uri",uri).put("metadata",new JSONObject()).put("requireAudio",true)));
   assertEquals(403,PodMediaPrefixCache.httpStatus(error));assertTrue(c.trafficStopped());assertEquals(0,c.localStore.list().length());served.get(3,java.util.concurrent.TimeUnit.SECONDS);
   assertFalse(c.localStore.file(PodMediaLocalStore.token("audio-stop"),".part").exists());assertFalse(c.localStore.file(PodMediaLocalStore.token("audio-stop"),".audio.part").exists());
  }
 }
 @Test public void dywaitCWhole200ContentLengthIsNotTheOneByteRequestLength()throws Exception {
  try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache c=new PodMediaPrefixCache(root())){
   java.util.concurrent.FutureTask<Void> served=new java.util.concurrent.FutureTask<>(()->{try(java.net.Socket socket=server.accept()){
    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 20971520\r\nConnection: close\r\n\r\n".getBytes());socket.getOutputStream().write(1);
   }return null;});new Thread(served).start();
   String uri=c.register("whole-length","http://127.0.0.1:"+server.getLocalPort()+"/video",Map.of());PodMediaPrefixCache.Job job=c.beginPreparation(uri,1);
   assertEquals(1,c.loadRange(job,0,1).length);assertEquals(20L<<20,c.target(uri).length);c.endPreparation(job);served.get(3,java.util.concurrent.TimeUnit.SECONDS);
  }
 }

 @Test public void dywaitCUnknownRangeTotalReportsUnknownRatherThanOneByte()throws Exception {
  ProbeSource remote=new ProbeSource(0,0,true);
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),remote,16L<<20)){
   String uri=c.register("unknown-range","https://fixture.invalid/video",Map.of());IOException error=assertThrows(IOException.class,()->c.execute("video.cache.local.prepare",new JSONObject().put("key","unknown-range").put("uri",uri).put("metadata",new JSONObject())));
   assertEquals("complete_size_unknown",error.getMessage());assertEquals(1,remote.requests.size());
  }
 }

 @Test public void dywaitCFragmentedReadsChargeOnlyTransferredBytes()throws Exception {
  PodMediaPrefixCacheTest.Remote bytes=new PodMediaPrefixCacheTest.Remote(512);
  DataSource.Factory fragmented=()->{DataSource delegate=bytes.createDataSource();return new DataSource(){
   public void addTransferListener(TransferListener l){delegate.addTransferListener(l);}public long open(DataSpec spec)throws IOException{return delegate.open(spec);}
   public int read(byte[] data,int offset,int length)throws IOException{return delegate.read(data,offset,Math.min(1,length));}public Uri getUri(){return delegate.getUri();}
   public Map<String,List<String>> getResponseHeaders(){return delegate.getResponseHeaders();}public void close()throws IOException{delegate.close();}
  };};
  try(PodMediaPrefixCache c=new PodMediaPrefixCache(root(),fragmented,16L<<20)){
   c.localStore.rate=16*1024;String uri=c.register("fragmented","https://fixture.invalid/video",Map.of());PodMediaPrefixCache.Job job=c.beginPreparation(uri,512);job.preStore=true;
   java.lang.reflect.Field credit=PodMediaPrefixCache.class.getDeclaredField("bandwidthCredit");credit.setAccessible(true);credit.setDouble(c,PodMediaPrefixCache.CHUNK);
   assertArrayEquals(bytes.body,c.loadRange(job,0,512));assertTrue("short reads overcharge the background byte budget",credit.getDouble(c)>=PodMediaPrefixCache.CHUNK-512);
   assertEquals(512,c.stats().getLong("networkReadBytes"));c.endPreparation(job);
  }
 }

}
