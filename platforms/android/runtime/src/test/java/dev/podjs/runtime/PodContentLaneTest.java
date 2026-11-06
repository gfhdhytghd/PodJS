package dev.podjs.runtime;

import android.app.Activity;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodContentLaneTest {
 @Test(timeout=10000) public void blockedContentLaneDoesNotHoldControlOrAccountQueue()throws Exception{
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();List<Integer> replies=new CopyOnWriteArrayList<>();PodServices host=new PodServices(activity,line->{try{JSONObject e=new JSONObject(line);if(e.optString("t").equals("service.result"))replies.add(e.getInt("id"));}catch(Exception error){throw new RuntimeException(error);}});
  Field field=PodServices.class.getDeclaredField("contentWorker");field.setAccessible(true);ExecutorService content=(ExecutorService)field.get(host);CountDownLatch release=new CountDownLatch(1);Future<?> blocked=content.submit(()->{try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
  try{long start=System.nanoTime();host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",1).put("method","secure.get").put("args",new JSONObject().put("key","p3-synthetic-missing")));
   while(replies.isEmpty()&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1)){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}assertEquals(Collections.singletonList(1),replies);assertFalse(blocked.isDone());
  }finally{release.countDown();blocked.get(2,TimeUnit.SECONDS);host.close();}
 }
}
