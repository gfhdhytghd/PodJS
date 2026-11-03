package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Player configuration proof only. Media extraction and audible output require device tests. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoForegroundAudioTest {
    static Field field(Class<?> type,String name)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    static class Reply implements PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
    }
    @Test public void readyAndPendingPromotionsRestoreAudioAfterEveryRetirement()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        AudioManager audio=PodVideoSystemAudio.audio(context);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0);
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
        PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
        host.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(host);
        Object[] sessions={ctor.newInstance(host,"a"),ctor.newInstance(host,"b")};
        ExoPlayer[] players={new ExoPlayer.Builder(context).build(),new ExoPlayer.Builder(context).build()};
        try {
            for(int i=0;i<2;i++){
                slots.put(i==0?"a":"b",sessions[i]);field(type,"player").set(sessions[i],players[i]);
                PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,players[i]);
                field(type,"audioPolicy").set(sessions[i],policy);policy.setHeldMuted(true);
            }
            for(int step=0;step<10;step++){
                int incoming=step%2,outgoing=1-incoming;
                // Alternate a decoded standby and an in-flight initial adoption.
                field(type,"hasRenderedFirstFrame").setBoolean(sessions[incoming],step%2==0);
                field(type,"prewarming").setBoolean(sessions[incoming],true);
                field(type,"prepareCallback").set(sessions[incoming],new Reply());
                players[incoming].setVolume(0f); // Stale historical/private gain.
                JSONObject args=new JSONObject().put("sessionId",incoming==0?"a":"b")
                    .put("replaceSessionId",outgoing==0?"a":"b").put("adoptPreparing",true).put("autoplay",false)
                    .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233));
                Reply result=new Reply();host.dispatch("video.session.promote",args,result);
                assertNull(result.error);assertNotNull(result.value);
                assertEquals(1f,players[incoming].getVolume(),0);
                assertFalse(players[incoming].getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));
                assertTrue(players[outgoing].getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));
                assertEquals(C.USAGE_MEDIA,players[incoming].getAudioAttributes().usage);
                assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE,players[incoming].getAudioAttributes().contentType);
                assertFalse(result.value.getBoolean("muted"));assertEquals(3,result.value.getInt("volumeIndex"));
                // The pending extractor completes before this player is reused as outgoing.
                field(type,"prepareCallback").set(sessions[incoming],null);
            }
        } finally { host.close();Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
}
