package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Three retained decoded textures; player-state checks are not mixer proof. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoDragTest {
 static Field field(Class<?> t,String n)throws Exception {Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;public void complete(JSONObject v){}public void fail(String c,String m){error=c;}}
 @Test public void coldPagerKeepsTheClipAndBlackFloorAtRestOnBothAxes() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();
  FrameLayout parent=new FrameLayout(context);
  PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(2));
  service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
  Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);
  constructor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  Object slot=constructor.newInstance(service,"old");sessions.put("old",slot);
  View video=(View)field(type,"videoView").get(slot);
  float density=context.getResources().getDisplayMetrics().density;
  JSONObject clip=new JSONObject().put("cx",116).put("cy",116).put("radius",116);
  try{
   for(int[] motion:new int[][]{{0,-64},{64,0},{0,64},{-64,0}}){
    JSONObject offset=new JSONObject().put("x",motion[0]).put("y",motion[1]);
    JSONObject bounds=new JSONObject().put("width",232).put("height",232).put("roundClip",clip)
      .put("translateX",motion[0]).put("translateY",motion[1]).put("pagerOffset",offset);
    Reply reply=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),reply);
    assertNull(reply.error);
    assertEquals(motion[0]*density,video.getTranslationX(),0f);
    assertEquals(motion[1]*density,video.getTranslationY(),0f);
    android.graphics.Outline outline=new android.graphics.Outline();
    video.getOutlineProvider().getOutline(video,outline);
    android.graphics.Rect rect=new android.graphics.Rect();assertTrue(outline.getRect(rect));
    assertEquals(116*density,rect.exactCenterX()+video.getTranslationX(),1f);
    assertEquals(116*density,rect.exactCenterY()+video.getTranslationY(),1f);
    View floor=(View)field(PodVideoSessionServices.class,"pagerBackground").get(service);
    assertNotNull(floor);assertEquals(View.VISIBLE,floor.getVisibility());
    assertEquals(0f,floor.getTranslationX(),0f);
    assertEquals(0f,floor.getTranslationY(),0f);
   }
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }

 @Test public void legacyColdPagerKeepsTheClipOnTheRouteOnBothAxes() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();
  PodVideoServices service=new PodVideoServices(context);
  TextureView video=new TextureView(context);
  video.setLayoutParams(new FrameLayout.LayoutParams(232,232));
  field(PodVideoServices.class,"videoView").set(service,video);
  Method applyBounds=PodVideoServices.class.getDeclaredMethod("applyBounds",JSONObject.class);
  applyBounds.setAccessible(true);
  float density=context.getResources().getDisplayMetrics().density;
  JSONObject clip=new JSONObject().put("cx",116).put("cy",116).put("radius",116);
  try{
   for(int[] motion:new int[][]{{64,0},{0,-64},{-64,0},{0,64}}){
    JSONObject offset=new JSONObject().put("x",motion[0]).put("y",motion[1]);
    JSONObject bounds=new JSONObject().put("width",232).put("height",232).put("roundClip",clip)
      .put("translateX",20+motion[0]).put("translateY",10+motion[1]).put("pagerOffset",offset);
    applyBounds.invoke(service,bounds);
    int left=((FrameLayout.LayoutParams)video.getLayoutParams()).leftMargin;
    assertEquals((20+motion[0])*density,left+video.getTranslationX(),1f);
    assertEquals((10+motion[1])*density,video.getTranslationY(),1f);
    android.graphics.Outline outline=new android.graphics.Outline();
    video.getOutlineProvider().getOutline(video,outline);
    android.graphics.Rect rect=new android.graphics.Rect();assertTrue(outline.getRect(rect));
    assertEquals(136*density,rect.exactCenterX()+left+video.getTranslationX(),1f);
    assertEquals(126*density,rect.exactCenterY()+video.getTranslationY(),1f);
   }
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }

 @Test public void bothDirectionsExcludeThirdTextureAndKeepOutgoingPlayingUntilRelease()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("width",466).put("height",466).put("roundClip",new JSONObject().put("cx",233).put("cy",233).put("radius",233));
  try {
   for(String id:new String[]{"old","next","prev"}){
    Object slot=ctor.newInstance(service,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(slot,true);field(t,"prewarming").setBoolean(slot,!id.equals("old"));
    Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),r);assertNull(r.error);
   }
   ExoPlayer old=(ExoPlayer)field(t,"player").get(sessions.get("old"));old.play();
   for(String direction:new String[]{"up","down","up"}){
    String target=direction.equals("up")?"next":"prev",other=direction.equals("up")?"prev":"next";
    Reply r=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId",target).put("direction",direction).put("progress",.5).put("bounds",bounds),r);assertNull(r.error);
    TextureView incoming=(TextureView)field(t,"videoView").get(sessions.get(target)),third=(TextureView)field(t,"videoView").get(sessions.get(other));
    View base=(View)field(PodVideoSessionServices.class,"pagerBackground").get(service);
    assertNotNull("unselected texture needs an opaque pager base",base);assertEquals(View.VISIBLE,base.getVisibility());
    assertTrue("unselected texture is hidden under black but still latches decoded buffers",parent.indexOfChild(third)<parent.indexOfChild(base));
    assertTrue(parent.indexOfChild(base)<parent.indexOfChild(incoming));assertEquals(1f,third.getAlpha(),0f);
    assertEquals(1f,incoming.getAlpha(),0f);assertTrue(old.getPlayWhenReady());
    assertFalse(((ExoPlayer)field(t,"player").get(sessions.get(target))).getPlayWhenReady());
   }
   Reply reset=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),reset);assertNull(reset.error);
   assertTrue("cancellation retains playback",old.getPlayWhenReady());
   TextureView foreground=(TextureView)field(t,"videoView").get(sessions.get("old"));assertEquals(0f,foreground.getTranslationY(),0f);
   for(String id:new String[]{"next","prev"}){TextureView hidden=(TextureView)field(t,"videoView").get(sessions.get(id));assertTrue(parent.indexOfChild(hidden)<parent.indexOfChild(foreground));}
   // Before JS has a decoded target, translated bounds expose black only.
   Reply cold=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",new JSONObject(bounds.toString()).put("translateY",-100)),cold);assertNull(cold.error);
   for(String id:new String[]{"next","prev"}) assertTrue(parent.indexOfChild((TextureView)field(t,"videoView").get(sessions.get(id)))<parent.indexOfChild((View)field(PodVideoSessionServices.class,"pagerBackground").get(service)));
   // A recognized strip consumes current native coordinates, not stale JS progress.
   android.view.MotionEvent down=android.view.MotionEvent.obtain(0,0,0,100,300,0);service.pagerInput(down);down.recycle();
   android.view.MotionEvent moved=android.view.MotionEvent.obtain(0,16,2,100,200,0);service.pagerInput(moved);moved.recycle();
   Reply tracked=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction","up").put("progress",.01).put("trackPointer",true).put("bounds",bounds),tracked);assertNull(tracked.error);
   assertEquals(-100f,foreground.getTranslationY(),0f);
   Object outline=foreground.getOutlineProvider(),layout=foreground.getLayoutParams();
   moved=android.view.MotionEvent.obtain(0,32,2,100,150,0);service.pagerInput(moved);moved.recycle();assertEquals(-150f,foreground.getTranslationY(),0f);assertSame(outline,foreground.getOutlineProvider());assertSame(layout,foreground.getLayoutParams());
   moved=android.view.MotionEvent.obtain(0,48,2,100,330,0);service.pagerInput(moved);moved.recycle();assertEquals(30f,foreground.getTranslationY(),0f);
   assertEquals("wrong-direction standby is hidden until JS selects the other neighbour",0f,((TextureView)field(t,"videoView").get(sessions.get("next"))).getAlpha(),0f);
   Reply rebound=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction","up").put("progress",0).put("trackPointer",false).put("bounds",bounds),rebound);assertNull(rebound.error);assertEquals(0f,foreground.getTranslationY(),0f);
   moved=android.view.MotionEvent.obtain(0,60,2,100,350,0);service.pagerInput(moved);moved.recycle();assertEquals("programmatic rebound releases the live pointer",0f,foreground.getTranslationY(),0f);
   android.view.MotionEvent up=android.view.MotionEvent.obtain(0,64,1,100,330,0);service.pagerInput(up);up.recycle();assertTrue(old.getPlayWhenReady());
   Reply commit=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",bounds).put("autoplay",true),commit);assertNull(commit.error);
   assertFalse(old.getPlayWhenReady());assertTrue(((ExoPlayer)field(t,"player").get(sessions.get("next"))).getPlayWhenReady());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
