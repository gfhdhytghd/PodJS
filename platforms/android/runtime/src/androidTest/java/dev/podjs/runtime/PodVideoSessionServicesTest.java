package dev.podjs.runtime;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.TextureView;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device coverage for the two-decoder pager pool and retained-surface handoff. */
public final class PodVideoSessionServicesTest {
    @Test public void transitionOffsetsFollowTheFingerInBothDirections() {
        assertArrayEquals(new float[]{-60f, 180f}, PodVideoSessionServices.transitionOffsets(240, .25f, "up"), .001f);
        assertArrayEquals(new float[]{60f, -180f}, PodVideoSessionServices.transitionOffsets(240, .25f, "down"), .001f);
        assertArrayEquals(new float[]{-240f, 0f}, PodVideoSessionServices.transitionOffsets(240, 2f, "up"), .001f);
    }

    @Test public void twoSessionsPrewarmTransitionPromoteAndReleaseWithoutReplacingSurface() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        File fixture = copyFixture(instrumentation.getContext(), target, "session-tiny.mp4");
        VideoTestActivity activity = (VideoTestActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getContext(), VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodVideoDecoderBudget budget = new PodVideoDecoderBudget(2);
        PodVideoSessionServices services = new PodVideoSessionServices(target, budget);
        JSONObject bounds = new JSONObject().put("x", 0).put("y", 0).put("width", 80).put("height", 80)
                .put("roundClip", new JSONObject().put("cx", 40).put("cy", 40).put("radius", 40));
        try {
            instrumentation.runOnMainSync(() -> services.attachToParent(activity.videoHost()));
            Result current = call(services, "video.session.prepare", new JSONObject()
                    .put("sessionId", "current").put("url", fixture.getName()).put("bounds", bounds)
                    .put("autoplay", false).put("muted", true).put("loop", true));
            ok(current); assertEquals("current", current.value.getString("sessionId"));
            Result next = call(services, "video.session.prepare", new JSONObject()
                    .put("sessionId", "next").put("url", fixture.getName()).put("bounds", bounds)
                    .put("prewarm", true).put("autoplay", false).put("muted", false).put("loop", true));
            ok(next); assertEquals("prewarmed", next.value.getString("state"));
            assertTrue(next.value.getBoolean("hasRenderedFirstFrame"));
            assertTrue(next.value.getBoolean("muted"));
            assertEquals(2, budget.inUse());

            List<TextureView> textures = textures(activity);
            assertEquals(2, textures.size());
            TextureView currentTexture = textures.get(0), nextTexture = textures.get(1);
            assertEquals(1f, currentTexture.getAlpha(), .001f);
            assertEquals(.01f, nextTexture.getAlpha(), .001f);

            Result limit = call(services, "video.session.prepare", new JSONObject()
                    .put("sessionId", "third").put("url", fixture.getName()).put("bounds", bounds).put("prewarm", true));
            assertEquals("resource_exhausted", limit.error);
            assertEquals("video_session_limit", limit.message);

            Result transition = call(services, "video.session.transition", new JSONObject()
                    .put("fromSessionId", "current").put("toSessionId", "next")
                    .put("progress", .25).put("direction", "up").put("bounds", bounds));
            ok(transition); assertTrue(transition.value.getBoolean("composited"));
            float heightPx = Math.round(80 * target.getResources().getDisplayMetrics().density);
            assertEquals(-heightPx * .25f, currentTexture.getTranslationY(), 1f);
            assertEquals(heightPx * .75f, nextTexture.getTranslationY(), 1f);

            TextureView retainedNext = nextTexture;
            Result promoted = call(services, "video.session.promote", new JSONObject()
                    .put("sessionId", "next").put("replaceSessionId", "current")
                    .put("bounds", bounds).put("autoplay", false));
            ok(promoted); assertEquals("paused", promoted.value.getString("state"));
            assertTrue(promoted.value.getBoolean("presented"));
            assertSame(retainedNext, textures(activity).get(1));
            assertEquals(1f, retainedNext.getAlpha(), .001f);
            assertEquals(.01f, currentTexture.getAlpha(), .001f);

            ok(call(services, "video.session.release", new JSONObject().put("sessionId", "current")));
            ok(call(services, "video.session.release", new JSONObject().put("sessionId", "next")));
            await("decoder release", 3000, () -> budget.inUse() == 0);
        } finally {
            services.close();
            await("surface removal", 3000, () -> activity.videoHost().getChildCount() == 0);
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            assertTrue(fixture.delete() || !fixture.exists());
        }
    }

    private static List<TextureView> textures(VideoTestActivity activity) {
        ArrayList<TextureView> result = new ArrayList<>();
        for (int index = 0; index < activity.videoHost().getChildCount(); index++) {
            View child = activity.videoHost().getChildAt(index);
            if (child instanceof TextureView) result.add((TextureView) child);
        }
        return result;
    }

    private static File copyFixture(Context test, Context target, String name) throws Exception {
        File root = new File(target.getFilesDir(), "podjs/files"); assertTrue(root.exists() || root.mkdirs());
        File output = new File(root, name);
        try (InputStream input = test.getAssets().open("tiny.mp4"); FileOutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[4096]; for (int count; (count = input.read(buffer)) != -1;) stream.write(buffer, 0, count);
        }
        return output;
    }

    private static Result call(PodVideoSessionServices services, String method, JSONObject args) throws Exception {
        Result result = new Result(); CountDownLatch done = new CountDownLatch(1);
        services.dispatch(method, args, new PodVideoSessionServices.Callback() {
            @Override public void complete(JSONObject value) { result.value = value; result.calls += 1; done.countDown(); }
            @Override public void fail(String code, String message) { result.error = code; result.message = message; result.calls += 1; done.countDown(); }
        });
        assertTrue(method + " timeout", done.await(10, TimeUnit.SECONDS));
        assertEquals(method + " callback count", 1, result.calls);
        return result;
    }

    private static void ok(Result result) throws Exception {
        assertNull(result.message, result.error); assertNotNull(result.value);
        if (result.value.has("ok")) assertTrue(result.value.getBoolean("ok"));
    }

    private static void await(String label, long timeoutMs, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(25); }
        fail(label + " timed out");
    }

    private interface Condition { boolean get() throws Exception; }
    private static final class Result { JSONObject value; String error, message; int calls; }
}
