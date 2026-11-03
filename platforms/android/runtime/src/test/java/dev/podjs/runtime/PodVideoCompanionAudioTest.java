package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoCompanionAudioTest {
 @Test public void primaryUrlWithCompanionAudioUsesTheSameMergerAsExplicitVideoUrl()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();
  PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
  Method source=PodVideoSessionServices.class.getDeclaredMethod("mediaSource",JSONObject.class,PodVideoAnalytics.class);source.setAccessible(true);
  try {
   for(String field:new String[]{"url","videoUrl"}){
    JSONObject args=new JSONObject().put(field,"https://fixture/video.mp4").put("audioUrl","https://fixture/audio.m4a");
    MediaSource result=(MediaSource)source.invoke(host,args,new PodVideoAnalytics());
    assertTrue("Companion audio must not be ignored for "+field,result instanceof MergingMediaSource);
   }
  }finally{host.close();}
 }
}
