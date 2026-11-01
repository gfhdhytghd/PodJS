package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;
import android.provider.Settings;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Real Android registration and AudioManager reads, with an output receipt.
 * These tests do not claim physical speaker output or OEM broadcast parity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodMediaAudioPolicyTest {
    Context context;
    AudioManager audio;
    List<Float> gains;
    @Before public void setup() {
        context=ApplicationProvider.getApplicationContext();
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);
        gains=new ArrayList<>();
    }
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void broadcast(String action){context.sendBroadcast(new Intent(action));idle();}
    float gain(){return gains.get(gains.size()-1);}
    @Test public void currentOutputFollowsRingerBothWaysAndPreservesRequestedGain() {
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add)) {
            policy.setVolume(.6f,false);idle();assertEquals(.6f,gain(),0);
            for(int mode:new int[]{AudioManager.RINGER_MODE_SILENT,AudioManager.RINGER_MODE_VIBRATE}) {
                audio.setRingerMode(mode);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
                assertEquals(0f,gain(),0);
                audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
                assertEquals(.6f,gain(),0);
            }
            assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
    }
    @Test public void zeroVolumeAndStreamMuteAreIndependentGates() {
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add)) {
            policy.setVolume(.8f,false);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);
            assertEquals(0f,gain(),0);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,7,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);
            assertEquals(.8f,gain(),0);assertEquals(7,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);broadcast(PodMediaAudioPolicy.STREAM_MUTE_CHANGED);
            assertEquals(0f,gain(),0);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);broadcast(PodMediaAudioPolicy.STREAM_MUTE_CHANGED);
            assertEquals(.8f,gain(),0);
        }
    }
    @Test public void settingsNotificationReadsActualSystemStateRatherThanIntentExtras() {
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add)) {
            policy.setVolume(1f,false);
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            context.getContentResolver().notifyChange(Settings.System.getUriFor("volume_music"),null);idle();
            assertEquals(0f,gain(),0);
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            context.getContentResolver().notifyChange(Settings.Global.CONTENT_URI,null);idle();
            assertEquals(1f,gain(),0);
        }
    }
    @Test public void prewarmNeverBecomesAudibleUntilActivationAndExplicitMuteSurvivesSystemUnmute() {
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add)) {
            policy.setVolume(.7f,true);idle();
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(List.of(0f),gains);
            policy.setVolume(.7f,false);assertEquals(.7f,gain(),0);
            policy.setHeldMuted(true);
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(0f,gain(),0);
            policy.setHeldMuted(false);assertEquals(.7f,gain(),0);
            policy.setVolume(0f,false);
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(0f,gain(),0);
        }
    }
    @Test public void activationReadsUnannouncedChangesAndCloseDisarmsQueuedCallbacks() {
        PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add);
        policy.setVolume(1f,true);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        policy.setVolume(1f,false);assertEquals(0f,gain(),0);
        context.getContentResolver().notifyChange(Settings.System.CONTENT_URI,null);
        policy.close();policy.close();int count=gains.size();
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
        policy.setVolume(1f,false);policy.refresh();idle();assertEquals(count,gains.size());
    }
    @Test public void realPlayerGainChangesWithoutRestartOrSeek() {
        ExoPlayer player=new ExoPlayer.Builder(context).build();player.seekTo(1234);player.play();
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player::setVolume)) {
            policy.setVolume(.9f,false);
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(0f,player.getVolume(),0);assertEquals(1234,player.getCurrentPosition());assertTrue(player.getPlayWhenReady());
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(.9f,player.getVolume(),0);assertEquals(1234,player.getCurrentPosition());assertTrue(player.getPlayWhenReady());
        } finally {player.release();}
    }
    @Test public void systemObservationsAreDistinctOnOffAndNeverComeFromPrewarmOrGainChanges() {
        List<Boolean> muted=new ArrayList<>();List<Long> times=new ArrayList<>();
        boolean[] playing={true};
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,gains::add)) {
            policy.observeMuteChanges(()->playing[0],()->gain(),(value,at)->{muted.add(value);times.add(at);});
            policy.setVolume(1f,false);idle();assertTrue(muted.isEmpty());
            policy.setVolume(.4f,false);policy.setVolume(0f,false);assertTrue(muted.isEmpty());
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);assertEquals(List.of(true),muted);
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(List.of(true,false),muted);
            assertTrue(times.get(0)<=android.os.SystemClock.uptimeMillis());assertTrue(times.get(1)>=times.get(0));
            policy.setVolume(1f,true);
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            policy.setVolume(1f,false);playing[0]=false;
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);
            assertEquals(List.of(true,false),muted);
        }
    }
    @Test @Config(sdk=33) public void android13RegistrationReceivesSystemEvents() {
        currentOutputFollowsRingerBothWaysAndPreservesRequestedGain();
    }
}
