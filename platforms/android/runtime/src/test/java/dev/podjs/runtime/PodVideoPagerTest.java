package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.*;
import android.widget.FrameLayout;
import android.view.TextureView;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.annotation.*;
/** Real retained-player host mutations; no network, codec or device proof. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPagerTest {
 static Field f(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static final class Result implements PodVideoServices.Callback,PodVideoSessionServices.Callback {
  JSONObject value;String error;long completed=-1;
  public void complete(JSONObject v){value=v;completed=SystemClock.uptimeMillis();}
  public void fail(String c,String m){error=c;}
 }
 @Test public void prewarmCreatesItsTextureBeforeWaitingForTheDecodedFrame()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  java.io.File fixture=new java.io.File(PodTakeover.filesRoot(c),"prewarm-surface-fixture.mp4");fixture.getParentFile().mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){out.write(0);}
  try {
   Result result=new Result();service.dispatch("video.session.prepare",new JSONObject().put("sessionId","warm").put("url","prewarm-surface-fixture.mp4").put("prewarm",true).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233)),result);
   assertNull(result.error);Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
   @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);
   Object slot=slots.get("warm");assertNotNull(slot);assertNotNull(f(type,"player").get(slot));
   TextureView view=(TextureView)f(type,"videoView").get(slot);assertEquals(android.view.View.VISIBLE,view.getVisibility());
   assertFalse(f(type,"hasRenderedFirstFrame").getBoolean(slot));assertFalse(f(type,"presented").getBoolean(slot));
   assertTrue(view.getTranslationY()>233);assertTrue(view.getAlpha()>0);assertTrue(view.getAlpha()<1);
  } finally {service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();fixture.delete();}
 }
 @Test public void releasePauseKeepsDecodedSurfaceAndCancelDoesNotRestart()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233);
  ExoPlayer active=null;TextureView view=null;
  try {
   for(String id:new String[]{"active","warm"}){
    Object slot=ctor.newInstance(service,id);slots.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    f(type,"player").set(slot,player);f(type,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));f(type,"hasRenderedFirstFrame").set(slot,true);f(type,"prewarming").set(slot,id.equals("warm"));
    service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),new Result());
    if(id.equals("active")){active=player;view=(TextureView)f(type,"videoView").get(slot);}
   }
   active.play();Object owner=slots.get("active"),layout=view.getLayoutParams();long generation=f(type,"generation").getLong(owner);
   // A cancelled drag returns the same playing player and decoded surface.
   for(double progress:new double[]{.4,0}){
    Result r=new Result();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","active").put("toSessionId","warm").put("progress",progress).put("direction","up").put("bounds",bounds),r);assertNull(r.error);
   }
   assertTrue(active.getPlayWhenReady());assertSame(active,f(type,"player").get(owner));assertEquals(generation,f(type,"generation").getLong(owner));
   // A committed release pauses immediately but leaves the strip frame intact.
   long released=SystemClock.uptimeMillis();Result paused=new Result();service.dispatch("video.session.pause",new JSONObject().put("sessionId","active"),paused);
   assertNull(paused.error);assertFalse(active.getPlayWhenReady());assertTrue(paused.completed-released<=100);assertTrue(paused.value.getBoolean("hasRenderedFirstFrame"));
   assertSame(view,f(type,"videoView").get(owner));assertSame(layout,view.getLayoutParams());assertEquals(generation,f(type,"generation").getLong(owner));
  } finally {service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void settledPromotionIsAtomicUnderRestoreReplies()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(2));
  s.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(s);
  ExoPlayer old=new ExoPlayer.Builder(c).build(),next=new ExoPlayer.Builder(c).build();Object incoming=null;
  JSONObject b=new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233);
  for(String id:new String[]{"old","next"}){Object slot=ctor.newInstance(s,id);slots.put(id,slot);f(t,"player").set(slot,id.equals("old")?old:next);ExoPlayer output=id.equals("old")?old:next;PodMediaAudioPolicy policy=new PodMediaAudioPolicy(c,output::setVolume);f(t,"audioPolicy").set(slot,policy);policy.setVolume(1f,id.equals("next"));f(t,"hasRenderedFirstFrame").set(slot,true);f(t,"prewarming").set(slot,id.equals("next"));s.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",b),new Result());if(id.equals("next"))incoming=slot;}
  Shadows.shadowOf(Looper.getMainLooper()).idle();old.play();next.pause();next.setVolume(0);
  TextureView retained=(TextureView)f(t,"videoView").get(incoming);Object layout=retained.getLayoutParams();
  s.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("progress",.01).put("direction","up").put("bounds",b),new Result());Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertSame(layout,retained.getLayoutParams());assertEquals(1,retained.getAlpha(),.001f);
  long generation=f(t,"generation").getLong(incoming);Handler main=new Handler(Looper.getMainLooper());
  for(int i=0;i<800;i++)main.post(()->ShadowSystemClock.advanceBy(Duration.ofMillis(4)));
  long settled=SystemClock.uptimeMillis();Result r=new Result();s.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",b).put("autoplay",true),r);
  assertFalse(old.getPlayWhenReady());assertEquals(0,old.getVolume(),.001f);assertTrue(next.getPlayWhenReady());assertNull(r.error);assertNotNull(r.value);assertEquals(0,r.completed-settled);
  assertTrue(r.value.getBoolean("hasRenderedFirstFrame"));assertSame(retained,f(t,"videoView").get(incoming));assertEquals(generation,f(t,"generation").getLong(incoming));
  System.out.println("SWIPEFEED_NATIVE restoreReplies=800 replyCostMs=4 settleToOldPausedMs="+(r.completed-settled)+" settleToRetainedFrameMs="+(r.completed-settled)+" prepareAtSettle=0");
  s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
 @Test public void watchSystemMemoryCapacityRecoversWithoutServiceRebuild()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();android.app.ActivityManager manager=(android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
  Shadows.shadowOf(manager).setMemoryClass(128);
  android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();info.availMem=920L*1024*1024;info.threshold=216L*1024*1024;info.lowMemory=false;
  Shadows.shadowOf(manager).setMemoryInfo(info);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));
  Method limit=PodVideoSessionServices.class.getDeclaredMethod("memoryLimit",PodVideoDecoderCapabilities.Profile.class);limit.setAccessible(true);
  PodVideoDecoderCapabilities.Profile profile=new PodVideoDecoderCapabilities.Profile("video/avc",1080,1920);
  try {
   assertEquals(3,limit.invoke(service,profile));
   Result capabilities=new Result();service.dispatch("video.session.capabilities",new JSONObject(),capabilities);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(capabilities.error);assertEquals(3,capabilities.value.getInt("decoderLimit"));
   service.onTrimMemory(10);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,limit.invoke(service,profile));
   service.onTrimMemory(15);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,limit.invoke(service,profile));
   service.onTrimMemory(10);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,limit.invoke(service,profile));
   service.onTrimMemory(0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(3,limit.invoke(service,profile));
   Result recovered=new Result();service.dispatch("video.session.capabilities",new JSONObject(),recovered);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(recovered.error);assertEquals(3,recovered.value.getInt("decoderLimit"));
   info.lowMemory=true;Shadows.shadowOf(manager).setMemoryInfo(info);assertEquals(1,limit.invoke(service,profile));
   info.lowMemory=false;info.availMem=info.threshold;Shadows.shadowOf(manager).setMemoryInfo(info);assertEquals(1,limit.invoke(service,profile));
   info.availMem=920L*1024*1024;Shadows.shadowOf(manager).setMemoryInfo(info);assertEquals(3,limit.invoke(service,profile));
   service.onTrimMemory(80);Shadows.shadowOf(Looper.getMainLooper()).idle();service.onTrimMemory(0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,limit.invoke(service,profile));
  } finally {service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void trimReleasesOnlySpeculativeLeasesAndForegroundCanTakeTheLastSlot()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
  PodVideoSessionServices service=new PodVideoSessionServices(c,budget);
  service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
  Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);
  Object foreground=null;
  for(String id:new String[]{"active","next","prev"}){
   assertTrue(budget.acquire());Object slot=ctor.newInstance(service,id);slots.put(id,slot);
   f(type,"player").set(slot,new ExoPlayer.Builder(c).build());f(type,"decoderLease").set(slot,true);
   f(type,"prewarming").set(slot,!id.equals("active"));if(id.equals("active"))foreground=slot;
  }
  service.onTrimMemory(15);Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertEquals(1,budget.inUse());assertEquals(1,slots.size());assertSame(foreground,slots.get("active"));assertNotNull(f(type,"player").get(foreground));
  service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,budget.inUse());
  // An active request claims the only remaining slot even if a warm decoder
  // previously owned it. Admission is synchronous and does not await playback.
  service=new PodVideoSessionServices(c,budget);service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  @SuppressWarnings("unchecked") Map<String,Object> warmSlots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);
  Object warm=ctor.newInstance(service,"warm");warmSlots.put("warm",warm);assertTrue(budget.acquire());
  f(type,"decoderLease").set(warm,true);f(type,"prewarming").set(warm,true);f(type,"player").set(warm,new ExoPlayer.Builder(c).build());
  service.onTrimMemory(15);Shadows.shadowOf(Looper.getMainLooper()).idle();
  java.io.File fixture=new java.io.File(PodTakeover.filesRoot(c),"offline-fixture.mp4");fixture.getParentFile().mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){out.write(0);}
  Result result=new Result();service.dispatch("video.session.prepare",new JSONObject().put("sessionId","foreground").put("url","offline-fixture.mp4").put("autoplay",false).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233)),result);
  assertNull(result.error);assertFalse("warm survived",warmSlots.containsKey("warm"));assertTrue("foreground missing",warmSlots.containsKey("foreground"));assertEquals(1,budget.inUse());
  service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,budget.inUse());fixture.delete();
 }
 @Test public void legacyRetireMutesAndPausesBeforeAnyReply()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();android.media.AudioManager setupAudio=(android.media.AudioManager)c.getSystemService(Context.AUDIO_SERVICE);setupAudio.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL);setupAudio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,5,0);PodVideoServices s=new PodVideoServices(c);ExoPlayer p=new ExoPlayer.Builder(c).build();f(PodVideoServices.class,"player").set(s,p);PodMediaAudioPolicy policy=new PodMediaAudioPolicy(c,p::setVolume);f(PodVideoServices.class,"audioPolicy").set(s,policy);policy.setVolume(.6f,false);p.play();Result r=new Result();s.dispatch("video.retire",new JSONObject(),r);assertFalse(p.getPlayWhenReady());assertEquals(0,p.getVolume(),.001f);assertNotNull(r.value);
  android.media.AudioManager audio=(android.media.AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
  for(int mode:new int[]{android.media.AudioManager.RINGER_MODE_SILENT,android.media.AudioManager.RINGER_MODE_NORMAL}){audio.setRingerMode(mode);policy.refresh();assertEquals(0,p.getVolume(),.001f);assertFalse(p.getPlayWhenReady());}
  Result resumed=new Result();s.dispatch("video.play",new JSONObject(),resumed);assertNull(resumed.error);assertEquals(1f,p.getVolume(),.001f);assertTrue(p.getPlayWhenReady());policy.close();s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }

 @Test public void terminalForegroundFailureKeepsOriginalCodeAndReleasesDecoderForPolling()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);PodVideoSessionServices service=new PodVideoSessionServices(c,budget);
  service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  Object active=ctor.newInstance(service,"active");ExoPlayer player=new ExoPlayer.Builder(c).build();f(type,"player").set(active,player);f(type,"prewarming").set(active,false);assertTrue(budget.acquire());f(type,"decoderLease").set(active,true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);slots.put("active",active);
  Method fail=PodVideoSessionServices.class.getDeclaredMethod("failPrepare",type,ExoPlayer.class,long.class,String.class,String.class,boolean.class);fail.setAccessible(true);fail.invoke(service,active,player,f(type,"generation").getLong(active),"decode_error","PRIVATE",false);
  Result result=new Result();service.dispatch("video.session.state",new JSONObject().put("sessionId","active"),result);Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertNull(result.error);assertEquals("error",result.value.getString("state"));assertEquals("decode_error",result.value.getString("errorCode"));assertEquals(0,budget.inUse());assertNull(f(type,"player").get(active));
  service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
 @Test public void immersiveFloorIsOpaqueStationaryAndNeverCapturesEntryPixels()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout host=new FrameLayout(c);
  host.layout(0,0,466,466);host.setBackgroundColor(android.graphics.Color.MAGENTA);
  PodVideoServices service=new PodVideoServices(c);service.attachToParent(host);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Result capture=new Result();service.dispatch("video.captureBackdrop",new JSONObject().put("opaque",true),capture);Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertNull(capture.error);assertNotNull(capture.value);
  android.widget.ImageView floor=(android.widget.ImageView)f(PodVideoServices.class,"backdrop").get(service);
  android.graphics.Bitmap bitmap=(android.graphics.Bitmap)f(PodVideoServices.class,"backdropBitmap").get(service);
  assertEquals(android.graphics.Color.BLACK,bitmap.getPixel(0,0));assertEquals(1,bitmap.getWidth());
  assertEquals(0,host.indexOfChild(floor));assertEquals(-1,floor.getLayoutParams().width);assertEquals(-1,floor.getLayoutParams().height);
  for(int offset:new int[]{-200,0,200}){
   Result bounds=new Result();service.dispatch("video.setBounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233).put("translateY",offset).put("visible",true),bounds);Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertNull(bounds.error);assertEquals(android.view.View.VISIBLE,floor.getVisibility());assertEquals(1f,floor.getAlpha(),0f);assertEquals(0f,floor.getTranslationY(),0f);assertFalse(floor.getClipToOutline());
  }
  // Preparing textures can be transparent. The fixed full-parent black floor
  // still covers both edges and the absent/unready incoming slot.
  assertEquals(0f,((TextureView)f(PodVideoServices.class,"videoView").get(service)).getAlpha(),0f);
  service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(bitmap.isRecycled());
 }

 @Test public void hiddenForegroundReservationRetainsTheSameSurfaceUntilNavigationShowsIt()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);Object session=ctor.newInstance(service,"reserved");
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)f(PodVideoSessionServices.class,"sessions").get(service);slots.put("reserved",session);ExoPlayer player=new ExoPlayer.Builder(c).build();f(type,"player").set(session,player);f(type,"hasRenderedFirstFrame").set(session,true);
  TextureView texture=(TextureView)f(type,"videoView").get(session);long generation=f(type,"generation").getLong(session);
  JSONObject bounds=new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233).put("visible",false);
  service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","reserved").put("bounds",bounds),new Result());Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertEquals(android.view.View.VISIBLE,texture.getVisibility());assertTrue(texture.getTranslationY()>233);assertEquals(.01f,texture.getAlpha(),.001f);assertFalse(player.getPlayWhenReady());
  bounds.put("visible",true);service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","reserved").put("bounds",bounds),new Result());Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertSame(texture,f(type,"videoView").get(session));assertEquals(generation,f(type,"generation").getLong(session));assertEquals(0,texture.getTranslationY(),.001f);assertEquals(1,texture.getAlpha(),.001f);service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
}
