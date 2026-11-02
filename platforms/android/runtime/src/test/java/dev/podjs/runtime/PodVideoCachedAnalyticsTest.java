package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.media3.datasource.*;
import java.io.File;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** Exercise the production session media factory, including a real cache miss. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoCachedAnalyticsTest {
 static final class Remote extends BaseDataSource {
  DataSpec spec;boolean ended;
  Remote(){super(true);}
  public long open(DataSpec s){spec=s;transferInitializing(s);transferStarted(s);return 8192;}
  public int read(byte[] b,int o,int n){if(ended)return -1;ended=true;Arrays.fill(b,o,o+Math.min(n,8192),(byte)1);bytesTransferred(Math.min(n,8192));return Math.min(n,8192);}
  public Uri getUri(){return spec==null?null:spec.uri;}
  public Map<String,List<String>> getResponseHeaders(){return Map.of("Content-Type",List.of("video/mp4"));}
  public void close(){if(spec!=null){transferEnded();spec=null;}}
 }
 @Test public void cachedUpstreamFeedsSessionBytesContentAndStallDetection()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();File root=java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(),"analytics-prefix-").toFile();
  try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,Remote::new,6*1024*1024)){
   String uri=cache.register("fixture","https://example.test/clip.mp4",Map.of());
   PodVideoSessionServices host=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3),cache);
   try{
    PodVideoAnalytics analytics=new PodVideoAnalytics();Method factory=PodVideoSessionServices.class.getDeclaredMethod("mediaSource",JSONObject.class,PodVideoAnalytics.class);factory.setAccessible(true);
    Object media=factory.invoke(host,new JSONObject().put("url",uri).put("type","video/mp4"),analytics);
    Field field=media.getClass().getDeclaredField("dataSourceFactory");field.setAccessible(true);
    DataSource source=((DataSource.Factory)field.get(media)).createDataSource();
    source.open(new DataSpec(Uri.parse(uri)));byte[] b=new byte[8192];assertEquals(8192,source.read(b,0,b.length));
    assertEquals("upstream cache bytes lost from session analytics",8192,analytics.snapshot().getLong("bytesLoaded"));
    assertEquals("mp4",analytics.contentKind());
    org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(900));assertTrue("open cached network cannot hide a real stall",analytics.networkStalled());
    source.close();assertFalse(analytics.networkStalled());
   }finally{host.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
  }
 }
}
