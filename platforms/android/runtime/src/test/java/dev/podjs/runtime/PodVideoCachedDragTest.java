package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
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
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoCachedDragTest {
 static Field field(Class<?> t,String n)throws Exception{Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback{String error;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}}
 @Test public void decodedBitmapMovesWithTheFingerBeforeTheNewPlayerDecodes()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("width",233).put("height",233);
  try{
   for(String id:new String[]{"old","next"}){
    Object slot=ctor.newInstance(service,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(slot,id.equals("old"));field(t,"prewarming").setBoolean(slot,id.equals("next"));
    Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),r);assertNull(r.error);
   }
   Object next=sessions.get("next");ImageView picture=new ImageView(c);picture.setLayoutParams(new FrameLayout.LayoutParams(466,466));parent.addView(picture);picture.setVisibility(View.GONE);
   PodVideoKeyframes.Frame frame=new PodVideoKeyframes.Frame(android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888));frame.pins=1;
   field(t,"cachedFrame").set(next,frame);field(t,"keyframeView").set(next,picture);
   Reply moved=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction","up").put("progress",.5).put("bounds",bounds),moved);
   assertNull(moved.error);assertEquals(View.VISIBLE,picture.getVisibility());assertTrue(picture.getTranslationY()>0);
   Reply promoted=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",bounds).put("autoplay",true),promoted);
   assertNull(promoted.error);assertFalse(promoted.value.getBoolean("hasRenderedFirstFrame"));assertTrue(promoted.value.getBoolean("hasCachedFirstFrame"));assertEquals(0,picture.getTranslationY(),0);
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
