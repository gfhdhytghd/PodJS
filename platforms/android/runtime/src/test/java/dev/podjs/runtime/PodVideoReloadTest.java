package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.os.Looper;
import android.net.Uri;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoReloadTest {
 @Test public void mediaCdn403404410CanResolveAgainWithoutExposingAddress(){
  for(int status:new int[]{403,404,410}){
   Throwable cause=new HttpDataSource.InvalidResponseCodeException(status,"PRIVATE",null,java.util.Collections.emptyMap(),new DataSpec(Uri.parse("https://fixture.invalid/?token=PRIVATE")),new byte[0]);
   assertEquals("source_expired",PodVideoAnalytics.errorCode(new PlaybackException("PRIVATE",cause,PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)));
  }
 }
 @Test public void lifecycleDoesNotEraseAnErrorWithNoLivePlayer()throws Exception{
  PodVideoServices service=new PodVideoServices(androidx.test.core.app.ApplicationProvider.getApplicationContext());
  try{
   java.lang.reflect.Field state=PodVideoServices.class.getDeclaredField("state");state.setAccessible(true);state.set(service,"error");
   java.lang.reflect.Field code=PodVideoServices.class.getDeclaredField("playbackErrorCode");code.setAccessible(true);code.set(service,"source_expired");
   service.pauseForLifecycle();Shadows.shadowOf(Looper.getMainLooper()).idle();
   final JSONObject[] value={null};
   service.dispatch("video.state",new JSONObject(),new PodVideoServices.Callback(){public void complete(JSONObject v){value[0]=v;}public void fail(String c,String m){org.junit.Assert.fail(c);}});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("error",value[0].getString("state"));assertEquals("source_expired",value[0].getString("errorCode"));
  }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void freshSessionPrepareBypassesPrefixCacheFactory()throws Exception{
  android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
  java.io.File root=java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(),"reload-cache-").toFile();
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root)){
   PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3),cache);
   try{
    java.lang.reflect.Method factory=PodVideoSessionServices.class.getDeclaredMethod("mediaSource",JSONObject.class,PodVideoAnalytics.class);factory.setAccessible(true);
    for(boolean bypass:new boolean[]{false,true}){
     Object media=factory.invoke(host,new JSONObject().put("url","http://127.0.0.1/synthetic.mp4").put("bypassCache",bypass),new PodVideoAnalytics());
     java.lang.reflect.Field field=media.getClass().getDeclaredField("dataSourceFactory");field.setAccessible(true);
     androidx.media3.datasource.DataSource data=((androidx.media3.datasource.DataSource.Factory)field.get(media)).createDataSource();
     assertEquals(!bypass,data.getClass().getSimpleName().contains("PrefixDataSource"));data.close();
    }
   }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
  }
 }

}
