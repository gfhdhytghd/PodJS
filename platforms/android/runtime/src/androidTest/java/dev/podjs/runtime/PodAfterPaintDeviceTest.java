package dev.podjs.runtime;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PodAfterPaintDeviceTest {
 @Test public void realWindowAcknowledgesTwoCommits() throws Exception {
  Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   CompletableFuture<Boolean> committed=new CompletableFuture<>();
   instrumentation.runOnMainSync(()->PodAfterPaint.await(activity.videoHost(),new Handler(Looper.getMainLooper()),committed::complete));
   assertTrue("real accelerated window commits, not the timer fallback",committed.get(3,TimeUnit.SECONDS));
  }finally{instrumentation.runOnMainSync(activity::finish);}
 }
}
