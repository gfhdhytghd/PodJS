package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** Retained texture, player identity, and atomic receipt. No codec/device timing proof. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoDecodedSwapTest {
 static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {JSONObject value;String error;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}}
 @Test public void standbyDrawsUnderForegroundAndPromotionKeepsItsPlayerAndTexture()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("width",233).put("height",233);ExoPlayer old=null,next=null;Object warm=null,active=null;
  try {
   for(String id:new String[]{"old","next"}){
    Object slot=ctor.newInstance(service,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    field(type,"player").set(slot,player);field(type,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(type,"hasRenderedFirstFrame").setBoolean(slot,true);field(type,"prewarming").setBoolean(slot,id.equals("next"));
    Reply reply=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),reply);assertNull(reply.error);
    if(id.equals("old")){old=player;active=slot;}else{next=player;warm=slot;}
   }
   TextureView texture=(TextureView)field(type,"videoView").get(warm),foreground=(TextureView)field(type,"videoView").get(active);
   assertEquals("standby must be drawn, not translated outside the window",0f,texture.getTranslationY(),0f);assertEquals(1f,texture.getAlpha(),0f);
   assertTrue(parent.indexOfChild(texture)<parent.indexOfChild(foreground));Object layout=texture.getLayoutParams();long generation=field(type,"generation").getLong(warm);
   old.play();Reply promoted=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",bounds).put("loop",true).put("autoplay",true),promoted);
   assertNull(promoted.error);
   assertTrue("atomic outgoing frame receipt",promoted.value.getBoolean("replacedHasRenderedFirstFrame"));
   assertEquals(Player.REPEAT_MODE_ONE,next.getRepeatMode());
   assertTrue("incoming playing",next.getPlayWhenReady());
   assertFalse("outgoing paused",old.getPlayWhenReady());
   assertTrue("outgoing audio selection disabled",old.getTrackSelectionParameters().disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO));
   assertSame(next,field(type,"player").get(warm));assertSame(texture,field(type,"videoView").get(warm));assertSame(layout,texture.getLayoutParams());assertSame(parent,texture.getParent());assertEquals(generation,field(type,"generation").getLong(warm));
  } finally {service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void swapStagesRejectGuestProofAndPrivateFields()throws Exception {
  long seq=PodBenchmark.begin("gesture_down",new JSONObject());JSONObject row=new JSONObject().put("code","swap_stage").put("seq",seq).put("generation",1).put("source_generation",2).put("swap_stage","texture_updated");
  assertEquals(0,PodBenchmark.dispatch(row.toString()));assertTrue(PodBenchmark.emit(row,android.os.SystemClock.uptimeMillis()));
  assertFalse(PodBenchmark.emit(new JSONObject(row.toString()).put("url","PRIVATE"),android.os.SystemClock.uptimeMillis()));
  assertFalse(PodBenchmark.emit(new JSONObject(row.toString()).put("swap_stage","PRIVATE"),android.os.SystemClock.uptimeMillis()));
 }
}
