package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.graphics.Bitmap;
import android.view.TextureView;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoHoldOwnershipTest {
 static Field field(Class<?> t,String n)throws Exception {Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;public void complete(JSONObject v){}public void fail(String c,String m){error=c;}}
 @Test public void retiredBoundsResetCannotDropTheColdHandoffFrame()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  Object old=ctor.newInstance(service,"old");sessions.put("old",old);ExoPlayer player=new ExoPlayer.Builder(c).build();
  field(t,"player").set(old,player);field(t,"audioPolicy").set(old,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(old,true);field(t,"hasUpdatedFrame").setBoolean(old,true);
  JSONObject bounds=new JSONObject().put("width",466).put("height",466);Reply r=new Reply();
  try {
   service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),r);assertNull(r.error);
   PodVideoLastFrame held=(PodVideoLastFrame)field(PodVideoSessionServices.class,"lastFrame").get(service);
   TextureView copy=new TextureView(c){@Override public boolean isAvailable(){return true;}@Override public Bitmap getBitmap(int w,int h){return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}};
   copy.setLayoutParams(new FrameLayout.LayoutParams(466,466));copy.layout(0,0,466,466);
   held.capture(old,field(t,"generation").getLong(old),copy,parent);assertTrue(held.present());
   service.dispatch("video.session.retire",new JSONObject().put("sessionId","old"),r);assertNull(r.error);
   service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),r);assertNull(r.error);
   assertTrue("retired geometry is not an incoming decoded frame",held.present());
   field(t,"prewarming").setBoolean(old,false);
   service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","old").put("bounds",bounds),r);assertNull(r.error);
   assertFalse("the decoded foreground can replace its own fallback at zero offset",held.present());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
