package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;

/** Tests the shared production emitter, not an independent seq/log fixture.
 * player_audio_applied proves a read-back gain only; it never proves audible PCM. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoMuteTelemetryTest {
    Context context;AudioManager audio;
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);ShadowLog.clear();}
    void silent(boolean muted){audio.setRingerMode(muted?AudioManager.RINGER_MODE_SILENT:AudioManager.RINGER_MODE_NORMAL);context.sendBroadcast(new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    List<String> lines(){List<String> rows=new ArrayList<>();for(ShadowLog.LogItem row:ShadowLog.getLogsForTag("DYBENCH")){assertEquals(Log.INFO,row.type);rows.add(row.msg);}return rows;}
    long number(String line,String key){Matcher m=Pattern.compile("(?:^| )"+key+"=(\\d+)(?: |$)").matcher(line);assertTrue(line,m.find());return Long.parseLong(m.group(1));}
    void musicMute(boolean muted){audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);context.sendBroadcast(new Intent(PodMediaAudioPolicy.STREAM_MUTE_CHANGED));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void systemObservationsUseSharedActionDomainAndEventUptimeWithoutFalsePlayerReceipts()throws Exception {
        androidx.media3.exoplayer.ExoPlayer player=new androidx.media3.exoplayer.ExoPlayer.Builder(context).build();
        try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
            policy.observeMuteChanges(()->true,player::getVolume,new PodVideoMuteTelemetry());policy.setHeldMuted(false);
            long unrelated=PodBenchmark.begin("entry_start",new JSONObject());ShadowLog.clear();
            long before=SystemClock.uptimeMillis();musicMute(true);
            List<String> on=lines();assertEquals(1,on.size());assertTrue(on.get(0).startsWith("code=mute_changed "));assertTrue(on.get(0).contains("muted=true"));assertTrue(number(on.get(0),"seq")>unrelated);assertTrue(number(on.get(0),"mono_ms")>=before);assertTrue(number(on.get(0),"mono_ms")<=SystemClock.uptimeMillis());
            musicMute(false);List<String> all=lines();assertEquals(2,all.size());assertTrue(all.get(1).contains("kind=off"));assertTrue(all.get(1).contains("muted=false"));assertTrue(number(all.get(1),"seq")>number(all.get(0),"seq"));
            context.sendBroadcast(new Intent(PodMediaAudioPolicy.STREAM_MUTE_CHANGED));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,lines().size());assertEquals(1f,player.getVolume(),0);
            for(String line:all){assertFalse(line.contains("mixer"));assertFalse(line.contains("url"));assertFalse(line.contains("player_audio_applied"));}
        }finally{player.release();}
    }
    @Test public void privateGainAndRingerChangesNeverEmitMusicMuteAndStandbyOrPausedChangesStayUnreported(){
        boolean[] playing={true};try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,v->{})){
            policy.observeMuteChanges(()->playing[0],()->1f,new PodVideoMuteTelemetry());policy.setVolume(0f,false);
            silent(true);silent(false);assertTrue(lines().isEmpty());
            policy.setHeldMuted(true);musicMute(true);policy.setHeldMuted(false);playing[0]=false;musicMute(false);assertTrue(lines().isEmpty());
        }
    }
    @Test public void telemetryRejectsUnclosedPayloadAndKeepsTheOriginalObservationTime()throws Exception {
        long at=SystemClock.uptimeMillis(),seq=PodBenchmark.begin("mute_changed",new JSONObject().put("kind","on").put("muted",true),at);
        SystemClock.sleep(40);
        assertEquals(0,PodBenchmark.dispatch(new JSONObject().put("code","player_audio_applied").put("seq",seq).put("kind","on").put("muted",true).put("backend","player").toString()));
        assertTrue(PodBenchmark.emit(new JSONObject().put("code","player_audio_applied").put("seq",seq).put("kind","on").put("muted",true).put("backend","player"),at+10));
        assertEquals(at+10,number(lines().get(1),"mono_ms"));
        assertFalse(PodBenchmark.emit(new JSONObject().put("code","player_audio_applied").put("seq",seq).put("url","https://private.invalid"),at+20));assertEquals(2,lines().size());
    }
}
