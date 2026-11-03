package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPreviewTransformTest {
 static Field field(Class<?> t,String n)throws Exception{Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class PaintedTexture extends TextureView {int paints;PaintedTexture(Context c){super(c);}@Override public void invalidate(){super.invalidate();paints++;}}
 static class Reply implements PodVideoSessionServices.Callback{String error;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c+":"+m;}}
 static JSONObject call(PodVideoSessionServices s,String method,JSONObject args)throws Exception{Reply r=new Reply();s.dispatch("video.session."+method,args,r);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(r.error);return r.value;}
 static RectF mapped(Matrix m,float w,float h){RectF r=new RectF(0,0,w,h);m.mapRect(r);return r;}
 static void rect(RectF a,RectF b){assertEquals(a.left,b.left,.001);assertEquals(a.top,b.top,.001);assertEquals(a.right,b.right,.001);assertEquals(a.bottom,b.bottom,.001);}
 @Test public void cachedAndLiveRectsMatchAcrossModesOrientationsAndDragOffsets()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));s.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(s);
  JSONObject bounds=new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233);
  try{
   for(String id:new String[]{"old","next"}){Object slot=ctor.newInstance(s,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"prewarming").setBoolean(slot,id.equals("next"));field(t,"hasRenderedFirstFrame").setBoolean(slot,true);call(s,"setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds));}
   Object next=sessions.get("next");TextureView original=(TextureView)field(t,"videoView").get(next);
   PaintedTexture texture=new PaintedTexture(c);texture.setLayoutParams(original.getLayoutParams());parent.removeView(original);parent.addView(texture);field(t,"videoView").set(next,texture);ExoPlayer player=(ExoPlayer)field(t,"player").get(next);
   for(int[] size:new int[][]{{100,200},{200,100}}){
    field(player.getClass(),"videoSize").set(player,new VideoSize(size[0],size[1]));
    Bitmap bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.MATRIX);image.setImageBitmap(bitmap);image.setLayoutParams(new FrameLayout.LayoutParams(texture.getLayoutParams()));parent.addView(image);image.layout(0,0,texture.getLayoutParams().width,texture.getLayoutParams().height);
    PodVideoKeyframes.Frame pixels=new PodVideoKeyframes.Frame(bitmap);pixels.pins=1;field(t,"cachedFrame").set(next,pixels);field(t,"keyframeView").set(next,image);
    for(String direction:new String[]{"up","down"})for(double progress:new double[]{0,.25,.5,1})for(String mode:new String[]{"standard","expanded","shrunk"}){
     call(s,"setTransform",new JSONObject().put("sessionId","old").put("scaleMode",mode));
     call(s,"transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction",direction).put("progress",progress).put("bounds",bounds));
     assertEquals(mode,field(t,"scaleMode").get(next));
     float y=image.getTranslationY();long generation=field(t,"generation").getLong(next);
     int paints=texture.paints;
     call(s,"setTransform",new JSONObject().put("sessionId","next").put("scaleMode",mode));
     assertTrue("paused TextureView must redraw without a new decoder buffer",texture.paints>paints);
     assertSame(pixels,field(t,"cachedFrame").get(next));assertEquals(generation,field(t,"generation").getLong(next));assertEquals(y,image.getTranslationY(),0);assertEquals(y,texture.getTranslationY(),0);
     Matrix live=new Matrix();texture.getTransform(live);int w=texture.getLayoutParams().width,h=texture.getLayoutParams().height;
     RectF settled=mapped(PodVideoServices.videoTransform(w,h,size[0],size[1],0,mode,0,0),w,h);
     assertEquals(size[0],player.getVideoSize().width);
     rect(settled,mapped(live,w,h));
     rect(settled,mapped(image.getImageMatrix(),image.getDrawable().getIntrinsicWidth(),image.getDrawable().getIntrinsicHeight()));
     View black=(View)field(t,"background").get(next);assertEquals(w,black.getLayoutParams().width);assertEquals(h,black.getLayoutParams().height);assertEquals(y,black.getTranslationY(),0);
    }
    call(s,"promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",bounds).put("autoplay",false));assertEquals(0,image.getTranslationY(),0);assertEquals("shrunk",field(t,"scaleMode").get(next));
    field(t,"cachedFrame").set(next,null);field(t,"keyframeView").set(next,null);parent.removeView(image);bitmap.recycle();
   }
  }finally{s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void coldTargetAndLateCachedFrameInheritTheActiveTransform()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));s.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(s);
  JSONObject bounds=new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233);
  try{for(String id:new String[]{"old","cold"}){Object slot=ctor.newInstance(s,id);sessions.put(id,slot);field(t,"prewarming").setBoolean(slot,id.equals("cold"));call(s,"setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds));}
   Object target=sessions.get("cold");
   for(String mode:new String[]{"standard","expanded","shrunk"}){
    call(s,"setTransform",new JSONObject().put("sessionId","old").put("scaleMode",mode));
    call(s,"transition",new JSONObject().put("fromSessionId","old").put("toSessionId","cold").put("progress",.5).put("bounds",bounds));
    assertEquals(mode,field(t,"scaleMode").get(target));View black=(View)field(t,"background").get(target);TextureView texture=(TextureView)field(t,"videoView").get(target);assertEquals(View.VISIBLE,black.getVisibility());assertEquals(texture.getTranslationY(),black.getTranslationY(),0);
   }
   field(t,"frameBinding").set(target,"late");PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(s);frames.put("late",Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888));
   float offset=((View)field(t,"videoView").get(target)).getTranslationY();Method reveal=t.getDeclaredMethod("revealKeyframe");reveal.setAccessible(true);reveal.invoke(target);
   ImageView image=(ImageView)field(t,"keyframeView").get(target);assertNotNull(image);image.layout(0,0,image.getLayoutParams().width,image.getLayoutParams().height);assertEquals(offset,image.getTranslationY(),0);assertEquals("shrunk",field(t,"scaleMode").get(target));
   int w=image.getLayoutParams().width,h=image.getLayoutParams().height;rect(mapped(PodVideoServices.videoTransform(w,h,100,200,0,"shrunk",0,0),w,h),mapped(image.getImageMatrix(),image.getDrawable().getIntrinsicWidth(),image.getDrawable().getIntrinsicHeight()));
  }finally{s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void pixelPrepareReadsTransformBeforeRevealingCachedPixels()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new java.io.File(c.getCacheDir(),"preview-transform"));PodVideoSessionServices s=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3),cache);s.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  try{String uri=cache.register("preview","https://fixture.invalid/video",java.util.Collections.emptyMap());JSONObject args=new JSONObject().put("sessionId","cached").put("url",uri).put("prewarm",true).put("bounds",new JSONObject().put("width",233).put("height",233)).put("transform",new JSONObject().put("scaleMode","shrunk"));
   PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(s);frames.put(PodVideoFrameCache.binding(args,cache),Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888));assertTrue(call(s,"prepare",args).getBoolean("hasCachedFirstFrame"));
   Object slot=((Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(s)).get("cached");assertEquals("shrunk",field(slot.getClass(),"scaleMode").get(slot));
   Object pixels=field(slot.getClass(),"cachedFrame").get(slot);call(s,"setTransform",new JSONObject().put("sessionId","cached").put("scaleMode","expanded"));assertSame(pixels,field(slot.getClass(),"cachedFrame").get(slot));assertEquals("expanded",((JSONObject)field(slot.getClass(),"frameSource").get(slot)).getJSONObject("transform").getString("scaleMode"));
  }finally{s.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();}
 }
}
