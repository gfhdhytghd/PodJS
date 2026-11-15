package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Every pager sample contains only selected pages and the opaque black base. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoSwipeGhostTest {
 static Field field(Class<?> t,String n)throws Exception{Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback{String error;public void complete(JSONObject v){}public void fail(String c,String m){error=c;}}
 @Test public void coldDragAndHandoffNeverReadBackOrAddAnUntransformedImage()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  Object old=ctor.newInstance(service,"old");slots.put("old",old);ExoPlayer player=new ExoPlayer.Builder(c).build();field(t,"player").set(old,player);field(t,"audioPolicy").set(old,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(old,true);field(t,"hasUpdatedFrame").setBoolean(old,true);
  int[] readbacks={0};TextureView texture=new TextureView(c){@Override public boolean isAvailable(){return true;}@Override public Bitmap getBitmap(int w,int h){readbacks[0]++;return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}};
  texture.setLayoutParams(new FrameLayout.LayoutParams(466,466));texture.layout(0,0,466,466);field(t,"videoView").set(old,texture);parent.addView(texture);
  try{
   for(int sign:new int[]{-1,1})for(int i=0;i<=20;i++){
    JSONObject bounds=new JSONObject().put("width",233).put("height",233).put("translateY",sign*233*i/20d);Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),r);assertNull(r.error);assertEquals("no raw snapshot at progress "+i,0,readbacks[0]);
    for(int j=0;j<parent.getChildCount();j++)assertFalse("no stationary image behind the translated page",parent.getChildAt(j) instanceof ImageView);
   }
   View black=(View)field(t,"background").get(old);
   Method raise=t.getDeclaredMethod("raisePresented");raise.setAccessible(true);raise.invoke(old);
   assertTrue("uncommitted aspect transform is occluded by black",parent.indexOfChild(black)>parent.indexOfChild(texture));
   field(t,"geometryReady").setBoolean(old,true);raise.invoke(old);
   assertTrue("paint-confirmed texture replaces black",parent.indexOfChild(texture)>parent.indexOfChild(black));
   parent.removeView(texture);field(t,"videoView").set(old,null);
   Method attach=t.getDeclaredMethod("attachViews",android.view.ViewGroup.class);attach.setAccessible(true);attach.invoke(old,parent);
   assertFalse("a replacement texture never inherits an old paint fence",field(t,"geometryReady").getBoolean(old));
   Reply r=new Reply();service.dispatch("video.session.retire",new JSONObject().put("sessionId","old").put("handoff",true),r);assertNull(r.error);assertEquals(0,readbacks[0]);
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void selectedCachedPageKeepsUniformPixelScaleAtEveryDragSample()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);JSONObject bounds=new JSONObject().put("width",233).put("height",233);
  try{
   for(String id:new String[]{"old","next","other"}){
    Object slot=ctor.newInstance(service,id);slots.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(slot,id.equals("old"));field(t,"hasUpdatedFrame").setBoolean(slot,id.equals("old"));field(t,"prewarming").setBoolean(slot,!id.equals("old"));Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),r);assertNull(r.error);
   }
   Object next=slots.get("next");
   for(int[] size:new int[][]{{320,180},{180,320},{240,240}})for(String mode:new String[]{"standard","expanded","shrunk"})for(int angle:new int[]{0,90,180,270}){
    ImageView picture=new ImageView(c);picture.setScaleType(ImageView.ScaleType.MATRIX);Bitmap pixels=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);picture.setImageBitmap(pixels);parent.addView(picture,new FrameLayout.LayoutParams(466,466));PodVideoKeyframes.Frame frame=new PodVideoKeyframes.Frame(pixels);frame.pins=1;field(t,"cachedFrame").set(next,frame);field(t,"keyframeView").set(next,picture);
    service.dispatch("video.session.setTransform",new JSONObject().put("sessionId","next").put("scaleMode",mode).put("rotationDeg",angle),new Reply());
    for(String direction:new String[]{"up","down"})for(int i=0;i<=20;i++){
     Reply r=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction",direction).put("progress",i/20d).put("bounds",bounds),r);assertNull(r.error);
     float[] axes={0,0,1,0,0,1};picture.getImageMatrix().mapPoints(axes);double sx=Math.hypot(axes[2]-axes[0],axes[3]-axes[1]),sy=Math.hypot(axes[4]-axes[0],axes[5]-axes[1]);assertEquals("decoded pixels keep equal x/y scale",sx,sy,.001);
     Method hide=t.getDeclaredMethod("hideOffscreen");hide.setAccessible(true);hide.invoke(slots.get("other"));
     View third=(View)field(t,"videoView").get(slots.get("other"));assertNotEquals("late standby callback cannot expose a third page",View.VISIBLE,third.getVisibility());assertTrue(parent.indexOfChild(picture)>parent.indexOfChild(third));int images=0;for(int j=0;j<parent.getChildCount();j++)if(parent.getChildAt(j) instanceof ImageView)images++;assertEquals(1,images);
    }
    Method clear=t.getDeclaredMethod("clearKeyframe");clear.setAccessible(true);clear.invoke(next);
   }
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
