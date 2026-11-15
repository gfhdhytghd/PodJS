package dev.podjs.runtime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import android.content.Context;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoBackgroundPolicyTest {
 @Test public void onlyAuthorizedActiveSessionCanPlayInBackground(){
  assertTrue(PodVideoBackgroundPolicy.mayPlay(false,false,false));
  assertFalse(PodVideoBackgroundPolicy.mayPlay(true,false,false));
  assertTrue(PodVideoBackgroundPolicy.mayPlay(true,true,false));
  assertFalse(PodVideoBackgroundPolicy.mayPlay(true,true,true));
 }
 static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
 @Test public void lifecycleKeepsAuthorizedAudioReleasesStandbyAndDoesNotResumeUserPause()throws Exception{
  Context context=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
  Map<String,Object> slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(host);
  try{
   for(String id:new String[]{"allowed","disabled","standby"}){
    Object session=ctor.newInstance(host,id);slots.put(id,session);ExoPlayer player=new ExoPlayer.Builder(context).build();
    field(type,"player").set(session,player);field(type,"autoplay").setBoolean(session,true);field(type,"backgroundPlayback").setBoolean(session,!id.equals("disabled"));field(type,"prewarming").setBoolean(session,id.equals("standby"));player.play();
   }
   ExoPlayer allowed=(ExoPlayer)field(type,"player").get(slots.get("allowed")),disabled=(ExoPlayer)field(type,"player").get(slots.get("disabled"));
   host.lifecycle(false);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(allowed.getPlayWhenReady());assertFalse(disabled.getPlayWhenReady());assertFalse(slots.containsKey("standby"));
   allowed.pause();field(type,"autoplay").setBoolean(slots.get("allowed"),false);
   host.lifecycle(true);Shadows.shadowOf(Looper.getMainLooper()).idle();host.lifecycle(false);Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(allowed.getPlayWhenReady());
  }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
