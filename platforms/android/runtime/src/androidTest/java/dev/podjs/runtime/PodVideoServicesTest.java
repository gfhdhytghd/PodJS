package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Device tests for the real Media3 decoder and its TextureView output. */
public final class PodVideoServicesTest {
 @Test public void decodedFramesPlaybackLayoutAndCleanupAreObservable() throws Exception {
  Instrumentation i=InstrumentationRegistry.getInstrumentation(); Context target=i.getTargetContext(); File source=fixture(i.getContext(),target); VideoTestActivity a=launch(i); PodVideoServices s=new PodVideoServices(target);
  try {
   i.runOnMainSync(()->s.attachToParent(a.videoHost())); TextureView texture=texture(a);
   Result prepared=call(s,"video.prepare",new JSONObject().put("url",source.getName()).put("autoplay",true).put("loop",true).put("muted",true).put("headers",new JSONObject().put("Cookie","sessionid="+new String(new char[6000]).replace('\0','a'))).put("bounds",new JSONObject().put("x",7).put("y",9).put("width",48).put("height",48).put("visible",true)));
   ok(prepared); assertTrue(stateIs(prepared.value,"ready","playing"));
   await("surface",5000,texture::isAvailable); await("decoded red pixels",7000,()->redFrame(texture,i));
   await("playing state",4000,()->"playing".equals(call(s,"video.state",new JSONObject()).value.optString("state")));
   long initial=call(s,"video.state",new JSONObject()).value.getLong("positionMs");
   await("position advance",3000,()->{long p=call(s,"video.state",new JSONObject()).value.getLong("positionMs");return p>initial+40||(initial>850&&p<initial);});
   Result pause=call(s,"video.pause",new JSONObject()); ok(pause); assertEquals("paused",pause.value.getString("state"));
   // Media3 applies playWhenReady on its playback thread; allow its queued audio position to settle.
   Thread.sleep(180);
   long p1=call(s,"video.state",new JSONObject()).value.getLong("positionMs"); Thread.sleep(180); long p2=call(s,"video.state",new JSONObject()).value.getLong("positionMs"); assertTrue("paused position moved: "+p1+" -> "+p2,Math.abs(p2-p1)<80);
   Result seek=call(s,"video.seek",new JSONObject().put("positionMs",500)); ok(seek); assertEquals("paused",seek.value.getString("state"));
   await("seek",3000,()->{long p=call(s,"video.state",new JSONObject()).value.getLong("positionMs");return p>=400&&p<=650;});
   JSONObject b=new JSONObject().put("x",11).put("translateX",13).put("y",15).put("width",40).put("height",42).put("visible",true).put("roundClip",new JSONObject().put("cx",20).put("cy",21).put("radius",12));
   ok(call(s,"video.setBounds",b)); i.waitForIdleSync(); float d=target.getResources().getDisplayMetrics().density; FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)texture.getLayoutParams();
   assertEquals(Math.round(24*d),lp.leftMargin); assertEquals(Math.round(15*d),lp.topMargin); assertEquals(Math.round(40*d),lp.width); assertEquals(Math.round(42*d),lp.height); assertEquals(View.VISIBLE,texture.getVisibility()); assertTrue(texture.getClipToOutline());
   Outline outline=new Outline(); i.runOnMainSync(()->texture.getOutlineProvider().getOutline(texture,outline)); Rect r=new Rect(); assertTrue(outline.getRect(r)); assertEquals(Math.round(8*d),r.left); assertEquals(Math.round(9*d),r.top); assertEquals(Math.round(32*d),r.right); assertEquals(Math.round(33*d),r.bottom);
   ok(call(s,"video.setBounds",new JSONObject().put("width",40).put("height",42).put("visible",false))); i.waitForIdleSync(); // Keep the TextureView attached; visibility is alpha-gated so first-frame delivery survives handoff.
   assertEquals(View.VISIBLE,texture.getVisibility()); assertEquals(0f,texture.getAlpha(),0f);
   Result stop=call(s,"video.stop",new JSONObject()); ok(stop); assertEquals("stopped",stop.value.getString("state")); Result stopped=call(s,"video.state",new JSONObject()); ok(stopped); assertEquals("stopped",stopped.value.getString("state")); assertEquals(0,stopped.value.getLong("positionMs"));
  } finally { s.close(); await("child removal",3000,()->a.videoHost().getChildCount()==0); a.finish(); i.waitForIdleSync(); assertTrue(source.delete()||!source.exists()); }
 }

 @Test public void cancellingPreparationReportsCancelledExactlyOnce() throws Exception {
  Instrumentation i=InstrumentationRegistry.getInstrumentation(); VideoTestActivity a=launch(i); PodVideoServices s=new PodVideoServices(i.getTargetContext());
  try { i.runOnMainSync(()->s.attachToParent(a.videoHost())); texture(a); CountDownLatch done=new CountDownLatch(1); Result r=new Result();
   s.dispatch("video.prepare",new JSONObject().put("url","http://127.0.0.1:9/never.mp4").put("bounds",new JSONObject().put("width",16).put("height",16)),new PodVideoServices.Callback(){public void complete(JSONObject v){r.value=v;r.calls++;done.countDown();}public void fail(String c,String m){r.error=c;r.message=m;r.calls++;done.countDown();}}); s.cancel("video.prepare");
   assertTrue(done.await(5,TimeUnit.SECONDS)); Thread.sleep(150); assertEquals(1,r.calls); assertNull(r.value); assertEquals("cancelled",r.error); assertNotNull(r.message);
  } finally { s.close(); await("child removal",3000,()->a.videoHost().getChildCount()==0); a.finish(); i.waitForIdleSync(); }
 }

 private static VideoTestActivity launch(Instrumentation i){Intent in=new Intent(i.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return (VideoTestActivity)i.startActivitySync(in);}
 private static TextureView texture(VideoTestActivity a)throws Exception{AtomicReference<TextureView> out=new AtomicReference<>();await("TextureView",3000,()->{for(int n=0;n<a.videoHost().getChildCount();n++){View v=a.videoHost().getChildAt(n);if(v instanceof TextureView){out.set((TextureView)v);return true;}}return false;});return out.get();}
 private static boolean redFrame(TextureView t,Instrumentation i){AtomicReference<Bitmap>b=new AtomicReference<>();i.runOnMainSync(()->b.set(t.getBitmap(16,16)));Bitmap bm=b.get();if(bm==null)return false;try{int red=0,total=bm.getWidth()*bm.getHeight();for(int y=0;y<bm.getHeight();y++)for(int x=0;x<bm.getWidth();x++){int p=bm.getPixel(x,y);if(Color.red(p)>180&&Color.green(p)<80&&Color.blue(p)<80)red++;}return red>=total*3/4;}finally{bm.recycle();}}
 private static File fixture(Context test,Context target)throws Exception{File root=new File(target.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());File dst=new File(root,"video-red-tiny.mp4");try(InputStream in=test.getAssets().open("tiny.mp4");FileOutputStream out=new FileOutputStream(dst)){byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);}assertTrue(dst.length()>0);return dst;}
 private static void ok(Result r)throws Exception{assertNull(r.message,r.error);assertNotNull(r.value);assertTrue(r.value.getBoolean("ok"));}
 private static boolean stateIs(JSONObject o,String a,String b){String s=o.optString("state");return a.equals(s)||b.equals(s);}
 private static Result call(PodVideoServices s,String method,JSONObject args)throws Exception{Result r=new Result();CountDownLatch done=new CountDownLatch(1);s.dispatch(method,args,new PodVideoServices.Callback(){public void complete(JSONObject v){r.value=v;r.calls++;done.countDown();}public void fail(String c,String m){r.error=c;r.message=m;r.calls++;done.countDown();}});assertTrue(method+" timeout",done.await(8,TimeUnit.SECONDS));assertEquals(method+" callback count",1,r.calls);return r;}
 private static void await(String what,long ms,Condition c)throws Exception{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);Throwable last=null;while(System.nanoTime()<end){try{if(c.get())return;}catch(Throwable x){last=x;}Thread.sleep(25);}if(last instanceof Exception)throw(Exception)last;fail(what+" timed out");}
 private interface Condition{boolean get()throws Exception;} private static final class Result{JSONObject value;String error,message;int calls;}
}
