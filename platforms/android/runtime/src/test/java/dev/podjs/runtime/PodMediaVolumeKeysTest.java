package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaVolumeKeysTest {
 Context context;AudioManager audio;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=PodVideoSystemAudio.audio(context);Shadows.shadowOf(audio).setStreamMaxVolume(15);audio.setStreamVolume(AudioManager.STREAM_MUSIC,7,0);audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);}
 @Test public void keyUsesMusicAndTogglesOncePerDownUpPair()throws Exception {
  for(int pair=0;pair<20;pair++)for(boolean muted:new boolean[]{true,false}){
   assertTrue(PodMediaVolumeKeys.dispatch(context,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_MUTE)));
   assertEquals(muted,PodVideoSystemAudio.snapshot(context,new JSONObject()).getBoolean("muted"));
   assertTrue(PodMediaVolumeKeys.dispatch(context,new KeyEvent(1,2,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_MUTE,1)));
   assertTrue(PodMediaVolumeKeys.dispatch(context,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_MUTE)));
   assertEquals(muted,PodVideoSystemAudio.snapshot(context,new JSONObject()).getBoolean("muted"));
  }
 }
 @Test public void zeroIndexIsEffectivelyMutedEvenAfterAnExternalUnmute()throws Exception {
  audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
  assertTrue(PodVideoSystemAudio.snapshot(context,new JSONObject()).getBoolean("muted"));
  assertTrue(PodMediaVolumeKeys.dispatch(context,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_MUTE)));
  assertFalse(PodVideoSystemAudio.snapshot(context,new JSONObject()).getBoolean("muted"));assertEquals(1,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
 }
 @Test public void unrelatedAndCancelledKeysDoNotWriteMusic(){
  assertFalse(PodMediaVolumeKeys.dispatch(context,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP)));
  KeyEvent cancelled=new KeyEvent(1,2,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_MUTE,0,0,0,0,KeyEvent.FLAG_CANCELED);
  assertTrue(PodMediaVolumeKeys.dispatch(context,cancelled));assertEquals(7,audio.getStreamVolume(AudioManager.STREAM_MUSIC));assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC));
 }
}
