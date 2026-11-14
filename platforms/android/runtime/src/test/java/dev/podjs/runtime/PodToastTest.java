package dev.podjs.runtime;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Looper;
import java.time.Duration;
import org.junit.Test;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodToastTest {
    @Test public void expiresAtThreeSeconds() {
        PodToast.show(RuntimeEnvironment.getApplication(), "default");
        Toast toast = ShadowToast.getLatestToast();
        assertEquals(Toast.LENGTH_LONG, toast.getDuration());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999));
        assertFalse(Shadows.shadowOf(toast).isCancelled());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertTrue(Shadows.shadowOf(toast).isCancelled());
    }

    @Test public void replacementCancelsOldToastAndItsExpiry() {
        Toast first = PodToast.show(RuntimeEnvironment.getApplication(), "first", 3000, true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        Toast second = PodToast.show(RuntimeEnvironment.getApplication(), "second", 3000, true);
        assertTrue(Shadows.shadowOf(first).isCancelled());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
        assertFalse(Shadows.shadowOf(second).isCancelled());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertTrue(Shadows.shadowOf(second).isCancelled());
    }

    @Test public void durationIsClampedToOneThroughTenSeconds() {
        for (int requested : new int[]{-1, 0, 999, 1000, 5000, 10000, 10001, Integer.MAX_VALUE}) {
            Toast toast = PodToast.show(RuntimeEnvironment.getApplication(), "clamped", requested, true);
            int expected = Math.max(1000, Math.min(10000, requested));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(expected - 1));
            assertFalse(Shadows.shadowOf(toast).isCancelled());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
            assertTrue(Shadows.shadowOf(toast).isCancelled());
        }
    }

    @Test public void hostOperationDefaultsAndClampsWithoutIntegerOverflow() throws Exception {
        try (PodServices services = new PodServices(RuntimeEnvironment.getApplication(), line -> {})) {
            long[] requested = {3000, -100, 10001, Long.MAX_VALUE};
            int id = 1;
            for (long duration : requested) {
                org.json.JSONObject args = new org.json.JSONObject().put("message", "host");
                if (id != 1) args.put("durationMs", duration); // First request omits the option.
                services.dispatch(new org.json.JSONObject().put("t", "service.request").put("version", 1)
                    .put("id", id++).put("method", "ui.toast").put("args", args));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Toast toast = ShadowToast.getLatestToast();
                long expected = Math.max(1000L, Math.min(10000L, duration));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(expected - 1));
                assertFalse(Shadows.shadowOf(toast).isCancelled());
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
                assertTrue(Shadows.shadowOf(toast).isCancelled());
            }
        }
    }

    @Test public void workerDispatchUsesApplicationContextOnMainThread() throws Exception {
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        Thread worker = new Thread(() -> PodToast.show(activity, "worker"));
        worker.start(); worker.join();
        assertNull(ShadowToast.getLatestToast());
        activity.finish();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Toast toast = ShadowToast.getLatestToast();
        assertNotNull(toast);
        assertSame(activity.getApplicationContext(), org.robolectric.util.ReflectionHelpers.getField(toast, "mContext"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
        assertTrue(Shadows.shadowOf(toast).isCancelled());
    }

    @Test public void customViewOnlyForRoundForeground() {
        assertTrue(PodToast.roundView(true, true));
        assertFalse(PodToast.roundView(true, false));
        assertFalse(PodToast.roundView(false, true));
        assertFalse(PodToast.roundView(false, false));
    }

    @Test public void chordUsesTheEdgeFartherFromCentre() {
        // 466 round, top edge 79 px: the top edge is 154 px from centre and limits the width.
        int width = PodToast.chordWidth(466, 466, 79, 160, 8);
        assertEquals((int) Math.floor(2 * Math.sqrt(225.0 * 225 - 154.0 * 154)), width);
        // A box reaching far below centre is limited by its bottom edge instead.
        assertTrue(PodToast.chordWidth(466, 466, 79, 380, 8) < width);
        // A box that cannot fit at all.
        assertEquals(0, PodToast.chordWidth(466, 466, 0, 40, 8));
    }

    @Test public void everyCornerStaysInsideForAllTopsAndHeights() {
        for (int size : new int[]{390, 454, 466}) for (int top = 0; top < size / 2; top += 3) for (int h = 20; h <= 200; h += 7) {
            int w = PodToast.chordWidth(size, size, top, h, 8);
            if (w == 0) continue;
            int left = (size - w) / 2;
            assertTrue(size + "/" + top + "/" + h, PodToast.cornersInside(size, size, left, top, w, h));
            // The fit is tight: one more pixel on each side crosses the 8 px inner margin.
            double far = Math.max(Math.abs(top - size / 2.0), Math.abs(top + h - size / 2.0));
            assertTrue(Math.hypot(w / 2.0 + 1, far) > size / 2.0 - 8);
        }
    }

    @Config(qualifiers = "w233dp-h233dp-round-xhdpi")
    @Test public void roundForegroundShowsInsetPill() {
        Context context = RuntimeEnvironment.getApplication();
        Toast toast = PodToast.show(context, "这是一条很长的提示消息，用来检查圆形屏幕上的换行与截断，确保它不会超出表盘边缘。再多一些文字。", 3000, true);
        TextView text = (TextView) toast.getView();
        assertNotNull(text);
        assertSame(toast, ShadowToast.getLatestToast());
        assertEquals(PodToast.MAX_LINES, text.getMaxLines());
        assertEquals(PodToast.BACKGROUND, ((GradientDrawable) text.getBackground()).getColor().getDefaultColor());
        assertEquals(Gravity.TOP | Gravity.CENTER_HORIZONTAL, toast.getGravity());
        int size = context.getResources().getDisplayMetrics().widthPixels;
        int tallest = text.getLineHeight() * PodToast.MAX_LINES + text.getPaddingTop() + text.getPaddingBottom();
        int left = (size - text.getMaxWidth()) / 2;
        assertTrue(PodToast.cornersInside(size, size, left, toast.getYOffset(), text.getMaxWidth(), tallest));
        assertEquals(android.view.View.VISIBLE, text.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
        assertEquals(android.view.View.INVISIBLE, text.getVisibility());
        assertTrue(Shadows.shadowOf(toast).isCancelled());
    }

    @Config(qualifiers = "w233dp-h233dp-round-xhdpi")
    @Test public void roundBackgroundFallsBackToTextToast() {
        Toast toast = PodToast.show(RuntimeEnvironment.getApplication(), "后台消息", 3000, false);
        assertSame(toast, ShadowToast.getLatestToast());
        assertEquals("后台消息", ShadowToast.getTextOfLatestToast());
        assertEquals(Toast.LENGTH_LONG, toast.getDuration());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
        assertTrue(Shadows.shadowOf(toast).isCancelled());
    }

    @Config(qualifiers = "w233dp-h233dp-notround-xhdpi")
    @Test public void squareScreenKeepsTextToast() {
        Toast toast = PodToast.show(RuntimeEnvironment.getApplication(), "方屏消息", 3000, true);
        assertSame(toast, ShadowToast.getLatestToast());
        assertEquals("方屏消息", ShadowToast.getTextOfLatestToast());
    }
}
