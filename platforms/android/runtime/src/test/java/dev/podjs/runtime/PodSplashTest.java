package dev.podjs.runtime;
import android.view.View;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import androidx.test.core.app.ApplicationProvider;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk={30,31})
public class PodSplashTest {
 @Test public void defaultWaitsForPresentedContentAndExitsOnce(){
  PodSplash splash=new PodSplash(true,1.12f,80,8000);AtomicInteger removed=new AtomicInteger();View view=new View(ApplicationProvider.getApplicationContext());splash.attach(view,removed::incrementAndGet);
  assertTrue(splash.keeping());assertEquals(0,removed.get());splash.texturePresented();assertFalse(splash.keeping());assertEquals(80,view.animate().getDuration());
  splash.attach(view,removed::incrementAndGet);splash.texturePresented();splash.recovery();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertEquals(1,removed.get());splash.close(null);assertEquals(1,removed.get());
 }
 @Test public void holdsWaitForAFrameAfterReleaseAndCannotResetDeadline(){
  PodSplash splash=new PodSplash(true,1.12f,80,8000);splash.hold("data");splash.hold("data");splash.texturePresented();assertTrue(splash.keeping());splash.release("data");assertTrue(splash.keeping());splash.texturePresented();assertFalse(splash.keeping());splash.close(null);
  splash=new PodSplash(true,1f,0,8000);splash.hold("never");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7999));assertTrue(splash.keeping());splash.hold("again");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertFalse(splash.keeping());splash.hold("late");assertFalse(splash.keeping());splash.close(null);
 }
 @Test public void recoveryReleasesBeforeGuestAndLateProviderAlsoExitsOnlyOnce(){
  PodSplash splash=new PodSplash(true,1.12f,80,8000);splash.hold("data");splash.recovery();assertFalse(splash.keeping());AtomicInteger removed=new AtomicInteger();splash.attach(new View(ApplicationProvider.getApplicationContext()),removed::incrementAndGet);splash.recovery();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertEquals(1,removed.get());splash.close(null);
 }
 @Test public void checkingActiveIsHiddenButMigrationAndBlockedRecoveryAreExposed()throws Exception {
  for(String phase:new String[]{"NONE","ACTIVE"}){PodSplash splash=new PodSplash(true,1.12f,80,8000);splash.recoveryStatus(new org.json.JSONObject().put("phase",phase).put("progressPhase","CHECKING"));assertTrue(splash.keeping());splash.close(null);}
  for(String phase:new String[]{"DETECTED","SNAPSHOTTED","COPYING","VERIFYING","READY","ACTIVATING","PAUSED","BLOCKED"}){PodSplash splash=new PodSplash(true,1.12f,80,8000);splash.hold("data");splash.recoveryStatus(new org.json.JSONObject().put("phase",phase).put("progressPhase","CHECKING"));assertFalse(splash.keeping());splash.close(null);}
 }
 @Test public void destroyedActivityRemovesProviderWithoutWaitingForTimeout(){
  PodSplash splash=new PodSplash(false,1f,80,8000);AtomicInteger removed=new AtomicInteger();splash.attach(new View(ApplicationProvider.getApplicationContext()),removed::incrementAndGet);splash.close(null);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));assertEquals(1,removed.get());assertFalse(splash.keeping());
 }
 @Test public void nativePreviewRevealsWithoutWaitingForTheIconAnimation(){
  PodSplash splash=new PodSplash(true,1.12f,80,8000);AtomicInteger removed=new AtomicInteger();
  splash.attach(new View(ApplicationProvider.getApplicationContext()),removed::incrementAndGet);
  splash.texturePresented(); // The runtime can start the ordinary exit before decoding completes.
  splash.previewPresented();assertFalse(splash.obscuring());assertEquals(1,removed.get());
  splash.previewPresented();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,removed.get());splash.close(null);
 }
 @Test public void nativePreviewHonorsHoldsAndLateProviderOwnership(){
  PodSplash splash=new PodSplash(true,1.12f,80,8000);AtomicInteger removed=new AtomicInteger();
  splash.hold("data");splash.previewPresented();assertTrue(splash.keeping());
  splash.release("data");assertTrue(splash.keeping());splash.texturePresented();assertFalse(splash.keeping());
  splash.attach(new View(ApplicationProvider.getApplicationContext()),removed::incrementAndGet);
  assertEquals(1,removed.get());assertFalse(splash.obscuring());splash.close(null);
 }
}
