package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.app.NotificationManager;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoRingerPolicyTest {
 Context context;AudioManager audio;ExoPlayer player;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=PodVideoSystemAudio.audio(context);audio.setRingerMode(2);audio.setStreamVolume(3,5,0);audio.adjustStreamVolume(3,AudioManager.ADJUST_UNMUTE,0);player=new ExoPlayer.Builder(context).build();}
 @After public void end(){player.release();}
 void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
 void mode(int mode){audio.setRingerMode(mode);context.sendBroadcast(new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION));idle();}
 void opt(PodMediaAudioPolicy p)throws Exception{PodMediaAudioPolicy.class.getDeclaredMethod("respectSilentMode",boolean.class).invoke(p,true);}
 @Test public void coldStartAndLiveRingerChangesOnlyChangeGain()throws Exception{
  mode(1);try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);p.setVolume(1,false);assertEquals(0,player.getVolume(),0);player.seekTo(1234);player.play();
   for(int m:new int[]{2,0,2,1,2}){mode(m);assertEquals(m==2?1:0,player.getVolume(),0);assertEquals(5,audio.getStreamVolume(3));assertEquals(1234,player.getCurrentPosition());assertTrue(player.getPlayWhenReady());}
  }
 }
 @Test public void crownOverrideSurvivesPlayersAndZeroRevokesIt()throws Exception{
  mode(1);try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);assertEquals(0,player.getVolume(),0);
   PodVideoSystemAudio.update(context,new JSONObject().put("volume",.6).put("crownDirection",1));assertEquals(1,player.getVolume(),0);
   p.setHeldMuted(true);assertTrue(player.getTrackSelectionParameters().disabledTrackTypes.contains(1));p.setHeldMuted(false);assertEquals(1,player.getVolume(),0);
   ExoPlayer next=new ExoPlayer.Builder(context).build();try(PodMediaAudioPolicy q=new PodMediaAudioPolicy(context,next)){opt(q);assertEquals(1,next.getVolume(),0);
    PodVideoSystemAudio.update(context,new JSONObject().put("volume",0).put("crownDirection",-1));assertEquals(0,player.getVolume(),0);assertEquals(0,next.getVolume(),0);
   }finally{next.release();}
  }
 }
 @Test public void positiveCrownAtSameIndexUnmutesTheSystemStream()throws Exception{
  mode(1);audio.adjustStreamVolume(3,AudioManager.ADJUST_MUTE,0);
  try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);
   double unchanged=audio.getStreamVolume(3)/(double)audio.getStreamMaxVolume(3);
   PodVideoSystemAudio.update(context,new JSONObject().put("volume",unchanged).put("crownDirection",1));
   assertFalse(audio.isStreamMute(3));assertEquals(1,player.getVolume(),0);
   PodVideoSystemAudio.update(context,new JSONObject().put("volume",0).put("crownDirection",-1));
  }
 }
 @Test public void prewarmPromotionAndFreshPolicyReadCurrentRinger()throws Exception{
  try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);p.setHeldMuted(true);mode(1);p.setHeldMuted(false);assertEquals(0,player.getVolume(),0);mode(2);assertEquals(1,player.getVolume(),0);}
  mode(0);try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);assertEquals(0,player.getVolume(),0);}
 }
 @Test public void zenBroadcastReevaluatesCurrentConsolidatedPolicy()throws Exception{
  NotificationManager n=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
  Shadows.shadowOf(n).setNotificationPolicyAccessGranted(true);
  try(PodMediaAudioPolicy p=new PodMediaAudioPolicy(context,player)){opt(p);
   Shadows.shadowOf(n).setConsolidatedNotificationPolicy(new NotificationManager.Policy(0,0,0));n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
   context.sendBroadcast(new Intent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED));idle();assertEquals(0,player.getVolume(),0);
   Shadows.shadowOf(n).setConsolidatedNotificationPolicy(new NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA,0,0));
   context.sendBroadcast(new Intent(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED));idle();assertEquals(1,player.getVolume(),0);
  }finally{n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);}
 }
 @Test public void zenMediaCategoriesAreClosed()throws Exception{
  java.lang.reflect.Method m=PodMediaAudioPolicy.class.getDeclaredMethod("zenSilencesMedia",int.class,int.class);
  assertEquals(true,m.invoke(null,3,64));assertEquals(false,m.invoke(null,4,0));assertEquals(true,m.invoke(null,2,0));assertEquals(false,m.invoke(null,2,64));assertEquals(false,m.invoke(null,1,0));
 }
}
