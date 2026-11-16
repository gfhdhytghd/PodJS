package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Scheduling, fallback and readback boundaries; never opens media or a socket. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoGeometryRecoveryTest {
 static Field field(Class<?> t,String n)throws Exception{Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
 static class Texture extends TextureView {
  boolean attached=true;int readbacks;
  Texture(Context c){super(c);}
  @Override public boolean isAttachedToWindow(){return attached;}
  @Override public boolean isHardwareAccelerated(){return true;}
  @Override public boolean isShown(){return attached&&getVisibility()==View.VISIBLE;}
  @Override public boolean isAvailable(){return true;}
  @Override public Bitmap getBitmap(int w,int h){readbacks++;return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}
  @SuppressWarnings("unchecked") int commit()throws Exception{
   Method m=ViewTreeObserver.class.getDeclaredMethod("captureFrameCommitCallbacks");m.setAccessible(true);
   List<Runnable> callbacks=(List<Runnable>)m.invoke(getViewTreeObserver());if(callbacks==null)return 0;
   for(Runnable r:callbacks)r.run();idle(0);return callbacks.size();
  }
 }
 static class Reply implements PodVideoSessionServices.Callback {
  JSONObject value;String error;
  public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}
 }
 static class Fixture implements AutoCloseable {
  final Context context=ApplicationProvider.getApplicationContext();
  final FrameLayout parent=new FrameLayout(context);
  final PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
  final Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
  final Map<String,Object> slots;
  @SuppressWarnings("unchecked") Fixture()throws Exception{service.attachToParent(parent);idle(0);slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);}
  Object get(Object s,String n)throws Exception{return field(type,n).get(s);}
  void set(Object s,String n,Object v)throws Exception{field(type,n).set(s,v);}
  void invoke(Object s,String n)throws Exception{Method m=type.getDeclaredMethod(n);m.setAccessible(true);m.invoke(s);}
  JSONObject bounds()throws Exception{return new JSONObject().put("width",233).put("height",233).put("roundClip",new JSONObject().put("cx",116.5).put("cy",116.5).put("radius",116.5));}
  Reply command(String method,JSONObject args){Reply r=new Reply();service.dispatch("video.session."+method,args,r);idle(0);return r;}
  Object slot(String id,boolean warm)throws Exception{
   Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);Object s=ctor.newInstance(service,id);slots.put(id,s);
   ExoPlayer p=new ExoPlayer.Builder(context).build();field(p.getClass(),"videoSize").set(p,new VideoSize(320,180));
   set(s,"player",p);set(s,"prewarming",warm);set(s,"presented",!warm);set(s,"hasRenderedFirstFrame",true);set(s,"hasUpdatedFrame",true);
   parent.removeView((View)get(s,"videoView"));Texture t=new Texture(context);parent.addView(t,new FrameLayout.LayoutParams(466,466));t.layout(0,0,466,466);set(s,"videoView",t);
   assertNull(command("setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds())).error);return s;
  }
  Texture texture(Object s)throws Exception{return (Texture)get(s,"videoView");}
  boolean ready(Object s)throws Exception{return (Boolean)get(s,"geometryReady");}
  boolean expired(Object s)throws Exception{return (Boolean)get(s,"geometryWaitExpired");}
  boolean covered(Object s)throws Exception{return parent.indexOfChild((View)get(s,"background"))>parent.indexOfChild(texture(s));}
  void transition(String to)throws Exception{assertNull(command("transition",new JSONObject().put("fromSessionId","current").put("toSessionId",to).put("progress",.4).put("bounds",bounds())).error);}
  public void close(){service.close();idle(0);}
 }
 @Test public void synchronousHiddenFailureRearmsAfterReverseSelection()throws Exception{
  try(Fixture f=new Fixture()){
   f.slot("current",false);f.slot("next",true);Object previous=f.slot("previous",true);f.transition("next");
   assertEquals(View.INVISIBLE,f.texture(previous).getVisibility());f.invoke(previous,"confirmGeometry");idle(0);
   assertNull("completed synchronous failure cannot leave its returned handle",f.get(previous,"geometryCommitCancel"));
   f.transition("previous");idle(17);assertEquals(1,f.texture(previous).commit());assertTrue(f.ready(previous));assertFalse(f.covered(previous));
  }
 }
 @Test public void detachedWindowReturnsToForegroundBeforeDeadline()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);f.texture(s).attached=false;f.invoke(s,"confirmGeometry");idle(0);assertNull(f.get(s,"geometryCommitCancel"));
   f.texture(s).attached=true;f.service.lifecycle(true);idle(17);assertEquals(1,f.texture(s).commit());assertTrue(f.ready(s));assertFalse(f.expired(s));assertFalse(f.covered(s));
  }
 }
 @Test public void asynchronousFailuresCannotExtendTheTotalCoverDeadline()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);f.invoke(s,"confirmGeometry");idle(PodVideoSessionServices.GEOMETRY_WAIT_MAX_MS-1);
   assertFalse(f.ready(s));assertFalse(f.expired(s));assertTrue(f.covered(s));idle(1);
   assertFalse("timeout is not a paint receipt",f.ready(s));assertTrue(f.expired(s));assertFalse("deadline must remove the black cover",f.covered(s));assertNull(f.get(s,"geometryCommitCancel"));
  }
 }
 @Test public void synchronousFailuresAlsoFallBackAndLaterReceiveRealProof()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);f.texture(s).attached=false;f.invoke(s,"confirmGeometry");idle(PodVideoSessionServices.GEOMETRY_WAIT_MAX_MS);
   assertTrue(f.expired(s));assertFalse(f.ready(s));assertFalse(f.covered(s));assertNull(f.get(s,"geometryCommitCancel"));
   f.texture(s).attached=true;f.invoke(s,"confirmGeometry");assertEquals(1,f.texture(s).commit());assertTrue(f.ready(s));assertFalse(f.expired(s));
  }
 }
 @Test public void deadlineNeverMakesAnUnselectedStandbyVisible()throws Exception{
  try(Fixture f=new Fixture()){
   f.slot("current",false);f.slot("next",true);Object third=f.slot("previous",true);f.transition("next");
   f.invoke(third,"confirmGeometry");idle(PodVideoSessionServices.GEOMETRY_WAIT_MAX_MS+1);
   assertTrue(f.expired(third));assertEquals(View.INVISIBLE,f.texture(third).getVisibility());assertFalse(f.ready(third));
  }
 }
 @Test public void sourceResetCancelsBothAttemptAndTotalDeadline()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);f.invoke(s,"confirmGeometry");f.invoke(s,"resetPlayer");idle(PodVideoSessionServices.GEOMETRY_WAIT_MAX_MS+300);f.texture(s).commit();
   assertFalse(f.ready(s));assertFalse(f.expired(s));assertNull(f.get(s,"geometryCommitCancel"));assertNull(f.get(s,"geometryDeadline"));assertTrue(f.covered(s));
  }
 }
 @Test public void replacementTextureCannotReceiveAnOldTexturesCommit()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);Texture old=f.texture(s);f.invoke(s,"confirmGeometry");
   f.parent.removeView(old);f.set(s,"videoView",null);Method attach=f.type.getDeclaredMethod("attachViews",ViewGroup.class);attach.setAccessible(true);attach.invoke(s,f.parent);
   f.parent.removeView((View)f.get(s,"videoView"));Texture replacement=new Texture(f.context);f.parent.addView(replacement,new FrameLayout.LayoutParams(466,466));replacement.layout(0,0,466,466);f.set(s,"videoView",replacement);f.set(s,"hasUpdatedFrame",true);
   f.invoke(s,"confirmGeometry");old.commit();assertFalse(f.ready(s));assertEquals(1,replacement.commit());assertTrue(f.ready(s));
  }
 }
 @Test public void frameReadbackRequiresActualGeometryProofEvenAfterFallback()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);String key="r2-frame-"+System.nanoTime();JSONObject source=new JSONObject().put("url","fixture.mp4");f.set(s,"frameSource",source);
   JSONObject args=new JSONObject().put("sessionId","current").put("key",key).put("source",source).put("sourceGeneration",f.get(s,"generation"));
   assertNotNull(f.command("captureFrame",args).error);assertEquals(0,f.texture(s).readbacks);
   f.invoke(s,"confirmGeometry");idle(PodVideoSessionServices.GEOMETRY_WAIT_MAX_MS);
   assertTrue(f.expired(s));assertFalse(f.ready(s));assertNotNull(f.command("captureFrame",args).error);assertEquals(0,f.texture(s).readbacks);
   f.invoke(s,"confirmGeometry");assertEquals(1,f.texture(s).commit());assertTrue(f.ready(s));Reply captured=f.command("captureFrame",args);
   CountDownLatch drained=new CountDownLatch(1);PodVideoFrameCache.IO.execute(drained::countDown);assertTrue(drained.await(3,TimeUnit.SECONDS));idle(0);
   assertNull(captured.error);assertNotNull(captured.value);assertTrue(captured.value.getBoolean("available"));assertEquals(1,f.texture(s).readbacks);
   File files=(File)field(PodVideoSessionServices.class,"mediaRoot").get(f.service);JSONObject restored=new PodVideoFrameCache(files).restore(key,PodVideoFrameCache.binding(source,null));
   assertTrue(restored.getBoolean("available"));assertEquals(captured.value.getString("path"),restored.getString("path"));
  }
 }
}
