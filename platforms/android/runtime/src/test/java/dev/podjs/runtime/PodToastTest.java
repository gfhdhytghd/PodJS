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
        PodRecoveryRoundLayout.Rect topLimited = PodRecoveryRoundLayout.safeTopBlock(466, 466, w -> 160, 79, 466, 466, 1, 8);
        assertEquals(79, topLimited.y);
        int expected = (int)Math.floor(2 * Math.sqrt(225.0 * 225 - 154.0 * 154));
        assertEquals(expected - expected % 2, topLimited.width);
        PodRecoveryRoundLayout.Rect bottomLimited = PodRecoveryRoundLayout.safeTopBlock(466, 466, w -> 320, 79, 466, 466, 1, 8);
        assertEquals(79, bottomLimited.y);
        assertTrue(bottomLimited.width < topLimited.width);
        assertNull(PodRecoveryRoundLayout.safeTopBlock(466, 466, w -> 470, 0, 466, 466, 1, 8));
    }

    @Test public void everyCornerStaysInsideForAllTopsAndHeights() {
        for (int size : new int[]{390, 454, 466}) {
            for (int top = 0; top < size * .66; top += 13) {
                for (int height = 20; height <= 200; height += 17) {
                    int blockHeight = height;
                    PodRecoveryRoundLayout.Rect box = PodRecoveryRoundLayout.safeTopBlock(size, size, w -> blockHeight, top, size, size, 1, 8);
                    if (box == null) continue;
                    assertTrue(box.y >= top);
                    assertTrue(PodRecoveryRoundLayout.rectInsideCircle(box, size, size, 8));
                }
            }
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
        assertMeasuredPillInside(context, toast);
        assertEquals(android.view.View.VISIBLE, text.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
        assertEquals(android.view.View.INVISIBLE, text.getVisibility());
        assertTrue(Shadows.shadowOf(toast).isCancelled());
    }

    @Config(qualifiers = "w160dp-h160dp-round-xhdpi")
    @Test public void shortAndWrappedMessagesStayInsideWithLargeFont() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        float original = configuration.fontScale;
        try {
            android.content.res.Configuration larger = new android.content.res.Configuration(configuration);
            larger.fontScale = 1.6f;
            context.getResources().updateConfiguration(larger, context.getResources().getDisplayMetrics());
            for (String message : new String[]{"完成", "状态更新：请检查网络后重试", String.join("", java.util.Collections.nCopies(28, "LongUnbrokenToken")), "第一行\n第二行\n第三行\n第四行\n结束"}) {
                Toast toast = PodToast.show(context, message, 3000, true);
                assertMeasuredPillInside(context, toast);
            }
        } finally {
            android.content.res.Configuration restored = new android.content.res.Configuration(configuration);
            restored.fontScale = original;
            context.getResources().updateConfiguration(restored, context.getResources().getDisplayMetrics());
        }
    }

    private static void assertMeasuredPillInside(Context context, Toast toast) {
        TextView text = (TextView) toast.getView();
        assertNotNull(text);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        text.measure(android.view.View.MeasureSpec.makeMeasureSpec(text.getMaxWidth(), android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        int left = (metrics.widthPixels - text.getMeasuredWidth()) / 2;
        PodRecoveryRoundLayout.Rect rect = new PodRecoveryRoundLayout.Rect(left, toast.getYOffset(), text.getMeasuredWidth(), text.getMeasuredHeight());
        assertTrue(PodRecoveryRoundLayout.rectInsideCircle(rect, metrics.widthPixels, metrics.heightPixels, 2));
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
