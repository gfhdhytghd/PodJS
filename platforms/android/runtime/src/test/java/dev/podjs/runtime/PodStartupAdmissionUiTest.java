package dev.podjs.runtime;

import android.app.Activity;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk={30,35})
public class PodStartupAdmissionUiTest {
 @Test public void healthyAdmissionDoesNotBuildARecoveryTreeAndIgnoresLateProgress()throws Exception {
  try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   AtomicInteger admitted=new AtomicInteger();
   PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),admitted::incrementAndGet);
   controller.update(new JSONObject().put("phase","NONE"));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(controller.view);
   controller.finish(true,PodStartupTrace.now());assertEquals(1,admitted.get());
   controller.update(new JSONObject().put("phase","BLOCKED"));controller.checking.run();
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(controller.view);
   controller.finish(true,PodStartupTrace.now());assertEquals(1,admitted.get());
  }
 }
 @Test public void slowOrFailedAdmissionAndMigrationStillShowTheNativeRecoveryPage()throws Exception {
  try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),()->fail("admission bypass"));
   controller.running.set(true);controller.checking.run();assertNotNull(controller.view);
   controller.running.set(false);controller.finish(false,PodStartupTrace.now());assertFalse(controller.finished);
   PodTakeover.RecoveryController migration=new PodTakeover.RecoveryController(activity.get(),()->fail("migration bypass"));
   migration.update(new JSONObject().put("phase","COPYING"));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(migration.view);
  }
 }
 @Test public void destroyedActivityCannotInstallAViewOrStartTheRuntime(){
  org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup();
  PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),()->fail("destroyed owner"));
  activity.pause().stop().destroy();controller.running.set(true);controller.checking.run();
  controller.finish(true,PodStartupTrace.now());assertNull(controller.view);
 }
}
