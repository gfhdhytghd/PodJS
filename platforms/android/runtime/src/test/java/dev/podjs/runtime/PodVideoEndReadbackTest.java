package dev.podjs.runtime;
import android.content.Context;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PodVideoEndReadbackTest {
 static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
 @Test public void actualNativeEndedReadbackKeepsTheReceiptWhenTheListenerWasNotObserved()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
  Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);Object session=ctor.newInstance(host,"fixture");
  int[] state={Player.STATE_READY};
  ExoPlayer player=(ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class[]{ExoPlayer.class},(proxy,method,args)->{
   switch(method.getName()){
    case "getPlaybackState":return state[0];case "getCurrentPosition":case "getDuration":return 10000L;
    case "getVideoSize":return VideoSize.UNKNOWN;case "getPlaybackParameters":return PlaybackParameters.DEFAULT;case "getVolume":return 1f;
   }
   Class<?> r=method.getReturnType();if(r==boolean.class)return false;if(r==int.class)return 0;if(r==long.class)return 0L;if(r==float.class)return 0f;return null;
  });
  PodVideoAnalytics analytics=new PodVideoAnalytics();analytics.sourceOrdinal=4;analytics.onRenderedFirstFrame(null,new Object(),0);set(session,"player",player);set(session,"analytics",analytics);set(session,"prewarming",false);set(session,"generation",2L);
  Method snapshot=type.getDeclaredMethod("snapshot");snapshot.setAccessible(true);ShadowLog.clear();
  try{
   snapshot.invoke(session);assertTrue(ShadowLog.getLogsForTag("PodVideoStats").isEmpty());
   state[0]=Player.STATE_ENDED;snapshot.invoke(session);snapshot.invoke(session);
   assertEquals(1,ShadowLog.getLogsForTag("PodVideoStats").size());String line=ShadowLog.getLogsForTag("PodVideoStats").get(0).msg;
   assertTrue(line.startsWith("completed source_ordinal=4 source_generation=2 position_ms=10000"));assertTrue(line.endsWith("duration_ms=10000 ended=1"));
  }finally{set(session,"player",null);host.close();}
 }
}
