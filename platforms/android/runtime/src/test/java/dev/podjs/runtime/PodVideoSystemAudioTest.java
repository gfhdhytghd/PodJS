package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.*;
import android.media.AudioManager;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoSystemAudioTest {
 Context context;AudioManager audio;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();audio=PodVideoSystemAudio.audio(context);audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);Shadows.shadowOf(audio).setStreamMaxVolume(16);audio.setStreamVolume(AudioManager.STREAM_MUSIC,1,0);audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);}
 void broadcast(String action){context.sendBroadcast(new Intent(action));Shadows.shadowOf(Looper.getMainLooper()).idle();}
 @Test public void systemReadbackFixtureUsesActiveStreamIndexAndMax()throws Exception {
  JSONArray cases=new JSONArray();
  for(int index:new int[]{0,1,2,8,16})for(boolean muted:new boolean[]{false,true}){
   audio.setStreamVolume(AudioManager.STREAM_MUSIC,index,0);audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
   JSONObject state=PodVideoSystemAudio.snapshot(context,new JSONObject());
   assertEquals(audio.getStreamVolume(AudioManager.STREAM_MUSIC),state.getInt("volumeIndex"));assertEquals(16,state.getInt("volumeMax"));assertEquals(audio.isStreamMute(AudioManager.STREAM_MUSIC)||state.getInt("volumeIndex")==0,state.getBoolean("muted"));
   state.put("percent",Math.round(state.getDouble("volume")*100));cases.put(state);
  }
  System.out.println("SYSTEM_VOLUME_FIXTURE="+cases);
 }
 @Test public void muteUpdateUsesSystemStreamAndNeverSetsAnIndex()throws Exception {
  PodVideoSystemAudio.update(context,new JSONObject().put("muted",true));assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC));
  PodVideoSystemAudio.update(context,new JSONObject().put("muted",false));assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC));assertEquals(1,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
  PodVideoSystemAudio.update(context,new JSONObject().put("volume",.5));assertEquals(8,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
 }
 @Test public void videoUnityGainCannotRetainPrivateMuteAndPrewarmDisablesOnlyItsAudioTrack()throws Exception {
  ExoPlayer player=new ExoPlayer.Builder(context).build();player.setVolume(0f);
  List<JSONObject> pushes=new ArrayList<>();
  try(PodMediaAudioPolicy policy=new PodMediaAudioPolicy(context,player)){
   policy.observeVolume(()->{try{pushes.add(PodVideoSystemAudio.snapshot(context,new JSONObject()));}catch(Exception error){throw new RuntimeException(error);}});
   policy.setVolume(0f,false);assertEquals(1f,player.getVolume(),0);assertFalse(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));
   audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);broadcast(PodMediaAudioPolicy.STREAM_MUTE_CHANGED);assertEquals(1f,player.getVolume(),0);assertTrue(pushes.get(pushes.size()-1).getBoolean("muted"));
   audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);assertEquals(3,pushes.get(pushes.size()-1).getInt("volumeIndex"));
   audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);broadcast(AudioManager.RINGER_MODE_CHANGED_ACTION);assertEquals(1f,player.getVolume(),0);
   policy.setVolume(0f,true);assertEquals(1f,player.getVolume(),0);assertTrue(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC));
   policy.setHeldMuted(false);assertFalse(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO));assertEquals(1f,player.getVolume(),0);
   int count=pushes.size();policy.close();audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0);broadcast(PodMediaAudioPolicy.VOLUME_CHANGED);assertEquals(count,pushes.size());
  }finally{player.release();}
 }
 @Test public void sessionAndLegacyVolumeOperationsShareSystemTruthAndIgnoreOldPrivateMute()throws Exception {
  PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));service.attachToParent(new android.widget.FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
  java.io.File fixture=new java.io.File(PodTakeover.filesRoot(context),"system-audio-fixture.mp4");fixture.getParentFile().mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){out.write(0);}
  try {
   PodVideoPagerTest.Result prepared=new PodVideoPagerTest.Result();service.dispatch("video.session.prepare",new JSONObject().put("sessionId","active").put("url",fixture.getName()).put("muted",true).put("autoplay",false).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",233).put("height",233)),prepared);
   PodVideoPagerTest.Result state=new PodVideoPagerTest.Result();service.dispatch("video.session.state",new JSONObject().put("sessionId","active"),state);Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertNull(state.error);assertEquals(1,state.value.getInt("volumeIndex"));assertEquals(16,state.value.getInt("volumeMax"));assertFalse(state.value.getBoolean("muted"));assertEquals(1,state.value.getDouble("appliedVolume"),0);
   for(boolean mute:new boolean[]{true,false}) {
    PodVideoPagerTest.Result update=new PodVideoPagerTest.Result();service.dispatch("video.session.setVolume",new JSONObject().put("sessionId","active").put("muted",mute),update);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(update.error);assertEquals(mute,update.value.getBoolean("muted"));assertEquals(mute,audio.isStreamMute(AudioManager.STREAM_MUSIC));assertEquals(1,update.value.getDouble("appliedVolume"),0);
   }
   PodVideoServices legacy=new PodVideoServices(context);try{PodVideoPagerTest.Result changed=new PodVideoPagerTest.Result();legacy.dispatch("video.setVolume",new JSONObject().put("volume",.5),changed);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(changed.error);assertEquals(8,changed.value.getInt("volumeIndex"));PodVideoPagerTest.Result refreshed=new PodVideoPagerTest.Result();service.dispatch("video.session.state",new JSONObject().put("sessionId","active"),refreshed);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(8,refreshed.value.getInt("volumeIndex"));assertEquals(.5,refreshed.value.getDouble("volume"),0);}finally{legacy.close();}
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();fixture.delete();}
 }

}
