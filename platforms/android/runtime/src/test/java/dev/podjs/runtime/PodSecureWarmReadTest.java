package dev.podjs.runtime;
import android.content.Context;import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.spec.SecretKeySpec;import org.json.JSONObject;
import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class PodSecureWarmReadTest {
 @Test public void committedForegroundReadBypassesAnActiveBackgroundHostTicket()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();List<String> replies=new CopyOnWriteArrayList<>();
  try(PodDeviceServices writer=new PodDeviceServices(PodTakeover.filesRoot(context),()->new SecretKeySpec(new byte[32],"AES"));
      PodServices foreground=new PodServices(context,replies::add)){
   writer.execute("secure.set",new JSONObject().put("key","warm-synthetic").put("value","committed"));
   try(HostWorkPriority.Ticket active=HostWorkPriority.DEVICE.reserve(true)){
    active.enter();
    foreground.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",1).put("method","secure.get").put("args",new JSONObject().put("key","warm-synthetic")));
    long deadline=System.nanoTime()+2_000_000_000L;
    while(replies.isEmpty()&&System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}
    assertEquals("warm read queued behind background ticket",1,replies.size());
    JSONObject reply=new JSONObject(replies.get(0));assertTrue(reply.getBoolean("ok"));assertEquals("committed",reply.getJSONObject("value").getString("value"));
   }
  }
 }
}
