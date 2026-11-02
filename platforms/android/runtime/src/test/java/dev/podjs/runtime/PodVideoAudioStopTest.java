package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/** Platform-state fixtures; physical audio is measured separately on the watch. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoAudioStopTest {
    private AudioTrack track(){return new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(4096).build();}
    private JSONObject action(long generation)throws Exception {long seq=PodBenchmark.begin("gesture_down",new JSONObject());return new JSONObject().put("benchmark",new JSONObject().put("seq",seq).put("generation",1).put("source_generation",generation));}
    private void attach(PodVideoAudioStop observer,AudioTrack track)throws Exception {Field f=PodVideoAudioStop.class.getDeclaredField("track");f.setAccessible(true);f.set(observer,track);}
    private long count(){return ShadowLog.getLogsForTag("DYBENCH").stream().filter(r->r.msg.startsWith("code=previous_audio_stopped ")).count();}
    @Test public void onlyStoppedMatchingTrackProducesOneAcknowledgement()throws Exception {
        PodVideoAudioStop observer=new PodVideoAudioStop();AudioTrack track=track();attach(observer,track);ShadowLog.clear();
        try{track.play();observer.arm(action(2),2);observer.observed(track,"pause");assertEquals(0,count());
            track.pause();observer.observed(track,"pause");observer.observed(track,"pause");assertEquals(1,count());
            String message=ShadowLog.getLogsForTag("DYBENCH").stream().filter(r->r.msg.startsWith("code=previous_audio_stopped ")).findFirst().get().msg;
            assertTrue(message.contains("backend=audiotrack"));assertTrue(message.contains("audio_track_state=paused"));assertTrue(message.contains("source_generation=2"));
        }finally{track.release();}
    }
    @Test public void absentStaleAndReplacementTracksCannotProveOldAudioStop()throws Exception {
        PodVideoAudioStop observer=new PodVideoAudioStop();AudioTrack first=track(),replacement=track();ShadowLog.clear();
        try{observer.arm(action(2),2);observer.observed(first,"pause");assertEquals(0,count());
            attach(observer,first);observer.arm(action(1),2);observer.observed(first,"pause");assertEquals(0,count());
            observer.arm(action(2),2);observer.observed(replacement,"pause");assertEquals(0,count());
            observer.cancel();observer.observed(first,"pause");assertEquals(0,count());
        }finally{first.release();replacement.release();}
    }
}
