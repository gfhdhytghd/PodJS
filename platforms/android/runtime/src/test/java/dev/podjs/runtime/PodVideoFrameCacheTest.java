package dev.podjs.runtime;
import android.graphics.Bitmap;
import androidx.test.core.app.ApplicationProvider;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.Config;
import java.io.*;import java.nio.file.*;import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class PodVideoFrameCacheTest {
 private File root()throws Exception{return Files.createTempDirectory(ApplicationProvider.<android.content.Context>getApplicationContext().getCacheDir().toPath(),"frame-test-").toFile();}
 private JSONObject source(String cookie)throws Exception{return new JSONObject().put("url","https://example.test/private?signature=fixture").put("headers",new JSONObject().put("Cookie",cookie).put("Referer","https://example.test/"));}
 @Test public void actualPixelsPersistWithExactEntrySourceAndCredentialsButNoUrlsOnDisk()throws Exception {
  File root=root();PodVideoFrameCache cache=new PodVideoFrameCache(root);Bitmap bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.RED);
  String binding=PodVideoFrameCache.binding(source("fixture-secret"),null);JSONObject saved=cache.save("douyin:item",binding,7,4321,bitmap);bitmap.recycle();
  assertEquals("actual_frame",saved.getString("kind"));assertEquals(7,saved.getLong("capturedGeneration"));assertEquals(4321,saved.getLong("positionMs"));
  assertTrue(new PodVideoFrameCache(root).restore("douyin:item",binding).getBoolean("available"));
  assertFalse(cache.restore("douyin:other",binding).getBoolean("available"));assertFalse(cache.restore("douyin:item",PodVideoFrameCache.binding(source("rotated"),null)).getBoolean("available"));
  assertFalse(cache.restore("douyin:item",PodVideoFrameCache.binding(source("fixture-secret").put("url","https://example.test/new"),null)).getBoolean("available"));
  for(File file:new File(root,"video-frames").listFiles()){assertTrue(file.getName().matches("[a-f0-9]{64}\\.(jpg|json)"));if(file.getName().endsWith("json")){String text=new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);assertFalse(text.contains("fixture-secret"));assertFalse(text.contains("https://"));}}
  assertEquals(2,cache.clear().getInt("removedFiles"));assertFalse(cache.restore("douyin:item",binding).getBoolean("available"));
 }
 @Test public void prefixRegistrationAndRemoteHaveSameBindingWhileRotatedHeadersDoNot()throws Exception {
  try(PodMediaPrefixCache prefix=new PodMediaPrefixCache(root())){JSONObject source=source("fixture");String uri=prefix.register("item",source.getString("url"),Map.of("Cookie","fixture","Referer","https://example.test/"));
   assertEquals(PodVideoFrameCache.binding(source,null),PodVideoFrameCache.binding(new JSONObject().put("url",uri).put("headers",source.getJSONObject("headers")),prefix));
   try{PodVideoFrameCache.binding(new JSONObject().put("url",uri),null);fail();}catch(IllegalArgumentException expected){}
  }
 }
 @Test public void corruptFramesFallBackAndDiskCountIsBounded()throws Exception {
  File root=root();PodVideoFrameCache cache=new PodVideoFrameCache(root);Bitmap bitmap=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);String binding=PodVideoFrameCache.binding(source("fixture"),null);
  for(int i=0;i<12;i++)cache.save("item"+i,binding,i,0,bitmap);bitmap.recycle();File[] images=new File(root,"video-frames").listFiles((d,n)->n.endsWith("jpg"));assertEquals(6,images.length);
  JSONObject saved=cache.restore("item11",binding);assertTrue(saved.getBoolean("available"));Files.write(new File(root,saved.getString("path")).toPath(),new byte[]{1,2});assertFalse(cache.restore("item11",binding).getBoolean("available"));
 }
 @Test public void noRenderedFrameOrWrongGenerationCannotCapture()throws Exception {
  for(boolean rendered:new boolean[]{false,true})try{PodVideoFrameCache.capture(null,rendered,2,new JSONObject().put("sourceGeneration",1));fail();}catch(IllegalStateException expected){}
  try{PodVideoFrameCache.binding(source("a\r\nb"),null);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void textureCaptureReadsActualPixelsOnlyAfterRenderingAndWithinNativeGeneration()throws Exception {
  android.view.TextureView view=new android.view.TextureView(ApplicationProvider.getApplicationContext()){
   @Override public Bitmap getBitmap(int width,int height){Bitmap frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);frame.eraseColor(android.graphics.Color.BLUE);return frame;}
  };view.layout(0,0,1000,500);
  Bitmap frame=PodVideoFrameCache.capture(view,true,12,new JSONObject().put("sourceGeneration",12));assertEquals(320,frame.getWidth());assertEquals(160,frame.getHeight());assertEquals(android.graphics.Color.BLUE,frame.getPixel(100,100));frame.recycle();
  try{PodVideoFrameCache.capture(view,true,13,new JSONObject().put("sourceGeneration",12));fail();}catch(IllegalStateException expected){}
  try{PodVideoFrameCache.capture(view,false,12,new JSONObject().put("sourceGeneration",12));fail();}catch(IllegalStateException expected){}
 }
 @Test public void persistentFrameUsesTheSameRenditionIdentityAsBytesAcrossRestartAndCredentialRotation()throws Exception {
  File root=root();String binding;PodVideoFrameCache frames=new PodVideoFrameCache(root);
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root)){
   String uri=cache.register("rendition-avc-320","https://old.test/movie?signature=old",Map.of("Cookie","old"),true);
   binding=PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache);
   Bitmap frame=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);frame.eraseColor(android.graphics.Color.GREEN);
   frames.save("stock-item",binding,0,0,frame);frame.recycle();
  }
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root)){
   String uri=cache.register("rendition-avc-320","https://new.test/movie?signature=new",Map.of("Cookie","new"),true);
   String restored=PodVideoFrameCache.binding(new JSONObject().put("url",uri).put("headers",new JSONObject().put("Cookie","current")),cache);
   assertEquals(binding,restored);assertTrue(frames.restore("stock-item",restored).getBoolean("available"));
   String other=cache.register("rendition-hevc-640","https://new.test/movie?signature=new",Map.of("Cookie","new"),true);
   assertFalse(frames.restore("stock-item",PodVideoFrameCache.binding(new JSONObject().put("url",other),cache)).getBoolean("available"));
  }
 }

 @org.junit.Test public void dashPrefixPixelsBindTheCompanionWithoutReadingIt()throws Exception {
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root())){
   org.json.JSONObject args=new org.json.JSONObject().put("key","dash").put("url","https://fixture/video").put("audioUrl","https://fixture/audio").put("headers",new org.json.JSONObject());
   String uri=cache.execute("video.cache.register",args).getString("uri");PodMediaPrefixCache.Entry entry=cache.target(uri);
   assertEquals("https://fixture/audio",entry.frameAudioUrl);
   assertEquals(PodVideoFrameCache.binding(new org.json.JSONObject().put("url",uri).put("audioUrl",entry.frameAudioUrl),cache),PodVideoFrameCache.binding(args,cache));
   assertNotEquals(uri,cache.execute("video.cache.register",new org.json.JSONObject(args.toString()).put("audioUrl","https://fixture/other-audio")).getString("uri"));
   assertEquals(0,cache.stats().getLong("networkReadBytes"));
  }
 }
}
