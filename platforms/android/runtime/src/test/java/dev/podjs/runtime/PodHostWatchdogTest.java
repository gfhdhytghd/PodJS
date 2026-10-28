package dev.podjs.runtime;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodHostWatchdogTest {
    @Test public void deepSleepIsExcludedWhileAwakeBlockedMainStillCounts() throws Exception {
        PodHostWatchdog w=new PodHostWatchdog(new Handler(Looper.getMainLooper()));
        Method tick=PodHostWatchdog.class.getDeclaredMethod("tick");tick.setAccessible(true);
        try{
            w.state.active(true);w.state.phase(MainThreadWatchdog.Phase.JOBS);
            tick.invoke(w);
            ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(5));
            tick.invoke(w);
            assertEquals("suspended device time is not an unresponsive main turn",0,w.state.stalls());
            ShadowSystemClock.advanceBy(Duration.ofMillis(1251));tick.invoke(w);
            assertEquals("awake main stall retains the same one-second threshold",1,w.state.stalls());
        }finally{w.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void independentObserverCountsStallBeforeMainAcknowledgesAndStopsOnClose() throws Exception {
        PodHostWatchdog w=new PodHostWatchdog(new Handler(Looper.getMainLooper()));
        Method tick=PodHostWatchdog.class.getDeclaredMethod("tick");tick.setAccessible(true);
        try{
            w.state.active(true);w.state.phase(MainThreadWatchdog.Phase.SERVICE);
            tick.invoke(w); // post a real Android Handler ping, leave main paused
            ShadowSystemClock.advanceBy(Duration.ofMillis(1251));
            tick.invoke(w);
            assertEquals("records while main has not acknowledged",1,w.state.stalls());
            tick.invoke(w);assertEquals("does not flood a stuck interval",1,w.state.stalls());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            tick.invoke(w);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("normal idle can answer a ping without visual frames",1,w.state.stalls());
            w.state.active(false);ShadowSystemClock.advanceBy(Duration.ofSeconds(3));tick.invoke(w);
            assertEquals("background idle has no response deadline",1,w.state.stalls());
        }finally{w.close();}
        Field f=PodHostWatchdog.class.getDeclaredField("observer");f.setAccessible(true);
        ScheduledExecutorService executor=(ScheduledExecutorService)f.get(w);
        assertTrue(executor.isShutdown());assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        w.state.active(true);tick.invoke(w);
        assertNotEquals("closed observer cannot enqueue new pings",0,w.state.probe(android.os.SystemClock.elapsedRealtimeNanos()));
        w.state.active(false);
    }
}
