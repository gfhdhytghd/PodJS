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
   assertThrows(IllegalArgumentException.class,()->c.localStore.configure(new JSONObject().put("entryBytes",9*1024*1024)));assertThrows(IllegalArgumentException.class,()->c.localStore.configure(new JSONObject().put("totalBytes",41*1024*1024)));
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

}
