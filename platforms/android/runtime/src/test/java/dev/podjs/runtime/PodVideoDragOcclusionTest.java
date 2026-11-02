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
public class PodVideoDragOcclusionTest {
 static Field field(Class<?> t,String n)throws Exception {Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;public void complete(JSONObject v){}public void fail(String c,String m){error=c;}}
 @Test public void incomingPageCoversTheUnselectedStandby()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("width",466).put("height",466).put("roundClip",new JSONObject().put("cx",233).put("cy",233).put("radius",233));
  try {
   for(String id:new String[]{"old","next","prev"}){
    Object slot=ctor.newInstance(service,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(slot,true);field(t,"hasUpdatedFrame").setBoolean(slot,true);field(t,"prewarming").setBoolean(slot,!id.equals("old"));
    Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),r);assertNull(r.error);
   }
   ExoPlayer old=(ExoPlayer)field(t,"player").get(sessions.get("old"));old.play();
   // Baseline puts the third standby above the requested incoming texture.
   // A black pager base and the selected pair must cover all other standbys.
   PodVideoLastFrame held=(PodVideoLastFrame)field(PodVideoSessionServices.class,"lastFrame").get(service);
   TextureView copySource=new TextureView(c){@Override public boolean isAvailable(){return true;}@Override public android.graphics.Bitmap getBitmap(int w,int h){return android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);}};
   copySource.setLayoutParams(new FrameLayout.LayoutParams(466,466));copySource.layout(0,0,466,466);
   held.capture(sessions.get("old"),1,copySource,parent);assertTrue(held.present());
   Reply r=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("direction","up").put("progress",.5).put("bounds",bounds),r);assertNull(r.error);
   assertFalse("a decoded incoming page replaces the fallback copy during drag",held.present());
   TextureView incoming=(TextureView)field(t,"videoView").get(sessions.get("next"));
   TextureView unselected=(TextureView)field(t,"videoView").get(sessions.get("prev"));
   assertTrue("incoming decoded texture must be above the unrelated standby",parent.indexOfChild(incoming)>parent.indexOfChild(unselected));
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
