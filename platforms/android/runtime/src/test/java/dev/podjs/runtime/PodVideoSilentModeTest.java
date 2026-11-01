package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Service dispatch and lifetime checks with real Media3 players; no decoder or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoSilentModeTest {
    Context context;AudioManager audio;
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static class Result implements PodVideoServices.Callback,PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code+":"+message;}
        void ok(){assertNull(error);assertNotNull(value);}
    }
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void silent(boolean silent){audio.setRingerMode(silent?AudioManager.RINGER_MODE_SILENT:AudioManager.RINGER_MODE_NORMAL);context.sendBroadcast(new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION));idle();}
    @Test public void productionPlaybackServiceOwnsObserverUntilItReleasesPlayer()throws Exception {
        org.robolectric.android.controller.ServiceController<PodVideoPlaybackService> controller=Robolectric.buildService(PodVideoPlaybackService.class).create();
        PodVideoPlaybackService service=controller.get();
        ExoPlayer player=service.player();assertNotNull(player);assertNotNull(service.audioPolicy());
        service.audioPolicy().setVolume(1f,false);
        silent(true);assertEquals(0f,player.getVolume(),0);
        silent(false);assertEquals(1f,player.getVolume(),0);
        controller.destroy();assertNull(service.audioPolicy());
        silent(true);silent(false); // No callback may touch the released player.
    }
    @Test public void uiCloseKeepsBackgroundPolicyAndExplicitPrepareMuteSurvivesToggle()throws Exception {
        PodVideoServices services=new PodVideoServices(context);
        ExoPlayer player=new ExoPlayer.Builder(context).build();
        PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player::setVolume);
        field(PodVideoServices.class,"player").set(services,player);
        field(PodVideoServices.class,"audioPolicy").set(services,policy);
        field(PodVideoServices.class,"backgroundPlayback").setBoolean(services,true);
        policy.setVolume(0f,false);
        Result play=new Result();services.dispatch("video.play",new JSONObject(),play);idle();play.ok();
        silent(true);silent(false);assertEquals(0f,player.getVolume(),0);
        policy.setVolume(.8f,false);player.seekTo(1234);
        services.close();idle();assertTrue(player.getPlayWhenReady());
        silent(true);assertEquals(0f,player.getVolume(),0);
        silent(false);assertEquals(.8f,player.getVolume(),0);assertEquals(1234,player.getCurrentPosition());
        policy.close();player.release();
    }
    @Test public void promotionAndSystemUnmuteCannotExposeRetiredOrPrewarmedSlots()throws Exception {
        PodVideoSessionServices services=new PodVideoSessionServices(context,new PodVideoDecoderBudget(2));
        services.attachToParent(new FrameLayout(context));idle();
        Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(services);
        ExoPlayer active=null,standby=null;
        try {
            for(String id:new String[]{"active","standby"}) {
                Object session=constructor.newInstance(services,id);
                ExoPlayer player=new ExoPlayer.Builder(context).build();player.seekTo(4321);
                PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player::setVolume);
                boolean prewarm=id.equals("standby");
                field(type,"player").set(session,player);field(type,"audioPolicy").set(session,policy);
                field(type,"prewarming").setBoolean(session,prewarm);field(type,"hasRenderedFirstFrame").setBoolean(session,true);
                sessions.put(id,session);policy.setVolume(1f,prewarm);
                Result bounds=new Result();services.dispatch("video.session.setBounds",new JSONObject().put("sessionId",id).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",100).put("height",100)),bounds);idle();bounds.ok();
                if(prewarm)standby=player;else active=player;
            }
            final ExoPlayer first=active,second=standby;
            Player.Listener noOverlap=new Player.Listener() {
                @Override public void onVolumeChanged(float volume) {
                    assertFalse("both slots became audible",first.getVolume()>0f && second.getVolume()>0f);
                }
            };
            active.addListener(noOverlap);standby.addListener(noOverlap);
            silent(true);assertEquals(0f,active.getVolume(),0);assertEquals(0f,standby.getVolume(),0);
            silent(false);assertEquals(1f,active.getVolume(),0);assertEquals(0f,standby.getVolume(),0);
            // Activate while a silent-mode notification has not yet been delivered.
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            Result promote=new Result();services.dispatch("video.session.promote",new JSONObject().put("sessionId","standby").put("replaceSessionId","active"),promote);idle();promote.ok();
            assertEquals(0f,active.getVolume(),0);assertEquals(0f,standby.getVolume(),0);
            silent(false);assertEquals(0f,active.getVolume(),0);assertEquals(1f,standby.getVolume(),0);assertEquals(4321,standby.getCurrentPosition());
            Result volume=new Result();services.dispatch("video.session.setVolume",new JSONObject().put("sessionId","standby").put("volume",.4),volume);idle();volume.ok();
            silent(true);silent(false);assertEquals(.4f,standby.getVolume(),0);assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            Result audiblePromotion=new Result();services.dispatch("video.session.promote",new JSONObject().put("sessionId","active").put("replaceSessionId","standby"),audiblePromotion);idle();audiblePromotion.ok();
            assertEquals(1f,active.getVolume(),0);assertEquals(0f,standby.getVolume(),0);
            silent(true);silent(false);assertEquals(1f,active.getVolume(),0);assertEquals(0f,standby.getVolume(),0);
        } finally {services.close();idle();}
        silent(true);silent(false);
    }
}
