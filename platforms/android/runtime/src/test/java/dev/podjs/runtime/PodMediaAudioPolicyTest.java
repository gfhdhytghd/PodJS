package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** System readbacks and observer lifetime; no claim of physical speaker output. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodMediaAudioPolicyTest {
 Context context;AudioManager audio;ExoPlayer player;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=PodVideoSystemAudio.audio(context);audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);player=new ExoPlayer.Builder(context).build();}
 @After public void teardown(){player.release();}
 void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
 void broadcast(String action){context.sendBroadcast(new Intent(action));idle();}
 boolean enabled(){return !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO);}
 @Test public void historicalGainNeverBecomesAUserVolumeAndRingerNeverOverridesMusic(){
  try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
   for(float gain:new float[]{0f,.4f,1f}){policy.setVolume(gain,false);assertEquals(1f,player.getVolume(),0);assertTrue(enabled());}
   for(int mode:new int[]{AudioManager.RINGER_MODE_SILENT,AudioManager.RINGER_MODE_VIBRATE,AudioManager.RINGER_MODE_NORMAL}){audio.setRingerMode(mode);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);assertEquals(1f,player.getVolume(),0);assertTrue(enabled());assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC));}
  }
 }
 @Test public void systemVolumeAndMutePushWithoutRestartSeekOrPlayerGainChanges(){
  player.seekTo(1234);player.play();List<Integer> indices=new ArrayList<>();
  try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
   policy.observeVolume(()->indices.add(audio.getStreamVolume(AudioManager.STREAM_MUSIC)));
   for(int index:new int[]{0,7,2,5}){audio.setStreamVolume(AudioManager.STREAM_MUSIC,index,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);assertEquals(index,(int)indices.get(indices.size()-1));}
   for(int mode:new int[]{AudioManager.ADJUST_MUTE,AudioManager.ADJUST_UNMUTE}){audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,mode,0);broadcast(PodMediaAudioPolicy.STREAM_MUTE_CHANGED);assertEquals(1f,player.getVolume(),0);assertTrue(enabled());assertEquals(1234,player.getCurrentPosition());assertTrue(player.getPlayWhenReady());}
  }
 }
 @Test public void settingsNotificationsReadSystemRatherThanIntentExtras(){
  List<Integer> indices=new ArrayList<>();try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
   policy.observeVolume(()->indices.add(audio.getStreamVolume(AudioManager.STREAM_MUSIC)));
   for(android.net.Uri uri:new android.net.Uri[]{Settings.System.getUriFor("volume_music"),Settings.Global.CONTENT_URI}){audio.setStreamVolume(AudioManager.STREAM_MUSIC,indices.size()+1,0);context.getContentResolver().notifyChange(uri,null);idle();assertEquals(audio.getStreamVolume(AudioManager.STREAM_MUSIC),(int)indices.get(indices.size()-1));}
  }
 }
 @Test public void prewarmAndRetireHoldSurviveSystemUnmuteUntilExplicitActivation(){
  try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
   policy.setVolume(0f,true);assertFalse(enabled());
   for(int mode:new int[]{AudioManager.ADJUST_MUTE,AudioManager.ADJUST_UNMUTE}){audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,mode,0);broadcast(PodMediaAudioPolicy.STREAM_MUTE_CHANGED);assertFalse(enabled());assertEquals(1f,player.getVolume(),0);}
   policy.setHeldMuted(false);assertTrue(enabled());policy.setHeldMuted(true);assertFalse(enabled());policy.setHeldMuted(false);assertTrue(enabled());
  }
 }
 @Test public void activationReadsUnannouncedChangesAndCloseDisarmsQueuedCallbacks(){
  List<Integer> indices=new ArrayList<>();PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player);policy.observeVolume(()->indices.add(audio.getStreamVolume(AudioManager.STREAM_MUSIC)));
  policy.setHeldMuted(true);audio.setStreamVolume(AudioManager.STREAM_MUSIC,2,0);policy.setHeldMuted(false);assertEquals(2,(int)indices.get(indices.size()-1));
  context.getContentResolver().notifyChange(Settings.System.CONTENT_URI,null);policy.close();policy.close();int count=indices.size();audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);policy.refresh();policy.setVolume(0,true);assertEquals(count,indices.size());
 }
 @Test @Config(sdk=33) public void android13ReceivesSystemEvents(){systemVolumeAndMutePushWithoutRestartSeekOrPlayerGainChanges();}
}
