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
  Result resumed=new Result();s.dispatch("video.play",new JSONObject(),resumed);assertNull(resumed.error);assertEquals(.6f,p.getVolume(),.001f);assertTrue(p.getPlayWhenReady());policy.close();s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
}
