package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
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
public class PodVideoKeepScreenTest {
 static Field field(Class<?> t,String n)throws Exception {Field f=t.getDeclaredField(n);f.setAccessible(true);return f;}
 static class Reply implements PodVideoSessionServices.Callback {String error;JSONObject value;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}}
 @Test public void promotedPlaybackKeepsScreenOnAndPauseReleasesIt()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(c);
  PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(parent);Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  JSONObject bounds=new JSONObject().put("width",466).put("height",466);
  try {
   for(String id:new String[]{"old","next"}){
    Object slot=ctor.newInstance(service,id);sessions.put(id,slot);ExoPlayer player=new ExoPlayer.Builder(c).build();
    field(t,"player").set(slot,player);field(t,"audioPolicy").set(slot,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(slot,true);field(t,"hasUpdatedFrame").setBoolean(slot,true);field(t,"prewarming").setBoolean(slot,!id.equals("old"));
    Reply r=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds),r);assertNull(r.error);
   }
   TextureView next=(TextureView)field(t,"videoView").get(sessions.get("next"));assertFalse(next.getKeepScreenOn());
   Reply r=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",bounds).put("autoplay",true),r);assertNull(r.error);
   assertTrue("promotion shows a paused standby before play; refresh the wake request after play",next.getKeepScreenOn());
   assertFalse(((TextureView)field(t,"videoView").get(sessions.get("old"))).getKeepScreenOn());
   service.dispatch("video.session.pause",new JSONObject().put("sessionId","next"),r);assertNull(r.error);assertFalse(next.getKeepScreenOn());
   service.dispatch("video.session.play",new JSONObject().put("sessionId","next"),r);assertNull(r.error);assertTrue(next.getKeepScreenOn());
   service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","next").put("bounds",new JSONObject(bounds.toString()).put("visible",false)),r);assertNull(r.error);assertFalse(next.getKeepScreenOn());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void selectedDecodedPlayerPromotesAfterTheOutgoingSlotWasReclaimed()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  try{
   Object next=ctor.newInstance(service,"next");sessions.put("next",next);ExoPlayer player=new ExoPlayer.Builder(c).build();
   field(t,"player").set(next,player);field(t,"audioPolicy").set(next,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(next,true);field(t,"hasUpdatedFrame").setBoolean(next,true);field(t,"prewarming").setBoolean(next,true);
   JSONObject bounds=new JSONObject().put("width",466).put("height",466);Reply result=new Reply();
   service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","next").put("bounds",bounds),result);assertNull(result.error);
   service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","already-reclaimed").put("bounds",bounds).put("autoplay",true),result);
   assertNull(result.error);assertNotNull(result.value);assertFalse(result.value.optBoolean("replacedHasRenderedFirstFrame",true));assertTrue(player.getPlayWhenReady());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }

 @Test public void missingOutgoingRecoveryStillRejectsMissingTargetAndSelfReplacement()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  try{
   Object next=ctor.newInstance(service,"next");sessions.put("next",next);ExoPlayer player=new ExoPlayer.Builder(c).build();
   field(t,"player").set(next,player);field(t,"audioPolicy").set(next,new PodMediaAudioPolicy(c,player));field(t,"hasRenderedFirstFrame").setBoolean(next,true);field(t,"hasUpdatedFrame").setBoolean(next,true);field(t,"prewarming").setBoolean(next,true);
   JSONObject bounds=new JSONObject().put("width",466).put("height",466);
   Reply absent=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","unknown").put("replaceSessionId","next").put("bounds",bounds),absent);
   assertEquals("invalid_argument",absent.error);assertNull(absent.value);
   Reply self=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","next").put("bounds",bounds),self);
   assertEquals("invalid_argument",self.error);assertNull(self.value);assertSame(player,field(t,"player").get(next));assertTrue(field(t,"prewarming").getBoolean(next));assertEquals(1,sessions.size());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void ordinaryUnpreparedPromotionKeepsNotReadyAndDoesNotRetireTheOutgoingPlayer()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));service.attachToParent(new FrameLayout(c));Shadows.shadowOf(Looper.getMainLooper()).idle();
  Class<?> t=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=t.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
  try{
   Object old=ctor.newInstance(service,"old"),next=ctor.newInstance(service,"next");ExoPlayer oldPlayer=new ExoPlayer.Builder(c).build(),nextPlayer=new ExoPlayer.Builder(c).build();sessions.put("old",old);sessions.put("next",next);
   field(t,"player").set(old,oldPlayer);field(t,"player").set(next,nextPlayer);field(t,"audioPolicy").set(old,new PodMediaAudioPolicy(c,oldPlayer));field(t,"audioPolicy").set(next,new PodMediaAudioPolicy(c,nextPlayer));field(t,"hasRenderedFirstFrame").setBoolean(old,true);field(t,"prewarming").setBoolean(next,true);
   Reply reply=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","next").put("replaceSessionId","old").put("bounds",new JSONObject().put("width",466).put("height",466)),reply);
   assertEquals("not_ready",reply.error);assertNull(reply.value);assertSame(oldPlayer,field(t,"player").get(old));assertSame(nextPlayer,field(t,"player").get(next));assertTrue(field(t,"prewarming").getBoolean(next));assertEquals(2,sessions.size());
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
