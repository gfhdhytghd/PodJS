package dev.podjs.runtime;

import android.content.Context;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Matrix;
import android.view.TextureView;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Host contract smoke tests for transform, volume validation, and backdrop preconditions. */
public final class PodVideoTransformTest {
    @Test public void nonSquareVideoKeepsAspectThroughRotationAndFitsCircle() {
        android.graphics.RectF horizontal=new android.graphics.RectF(0,0,200,200);
        PodVideoServices.videoTransform(200,200,1920,1080,0,"standard",0,0).mapRect(horizontal);
        assertEquals(200f,horizontal.width(),.01f);assertEquals(112.5f,horizontal.height(),.01f);
        android.graphics.RectF vertical=new android.graphics.RectF(0,0,200,200);
        PodVideoServices.videoTransform(200,200,1920,1080,90,"standard",0,0).mapRect(vertical);
        assertEquals(112.5f,vertical.width(),.01f);assertEquals(200f,vertical.height(),.01f);
        android.graphics.RectF safe=new android.graphics.RectF(0,0,200,200);
        PodVideoServices.videoTransform(200,200,1920,1080,0,"shrunk",0,0).mapRect(safe);
        assertEquals(200d,Math.hypot(safe.width(),safe.height()),.02);
    }
    @Test public void transformAndVolumeRejectInvalidValuesWithoutPlayer() throws Exception {
        PodVideoServices s=new PodVideoServices(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try { assertError(s,"video.setTransform",new JSONObject().put("rotationDeg",100001),"invalid_rotation"); assertError(s,"video.setTransform",new JSONObject().put("scaleMode","bad"),"invalid_scale_mode"); assertError(s,"video.setVolume",new JSONObject().put("volume",2),"invalid_volume"); }
        finally { s.close(); }
    }

    @Test public void realPlaybackTransformsVolumeAndLifecycleAreObservable() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        VideoTestActivity activity = (VideoTestActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getContext(), VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodVideoServices services = new PodVideoServices(target);
        File fixture = new File(target.getFilesDir(), "podjs/files/transform-tiny.mp4");
        android.media.AudioManager audio = (android.media.AudioManager) target.getSystemService(Context.AUDIO_SERVICE);
        int originalVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        try {
            copyFixture(instrumentation.getContext(), fixture);
            instrumentation.runOnMainSync(() -> services.attachToParent(activity.videoHost()));
            waitFor("video texture", 3000, () -> activity.videoHost().getChildCount() > 1 && activity.videoHost().getChildAt(1) instanceof TextureView);
            TextureView texture = (TextureView) activity.videoHost().getChildAt(1);
            Result prepared = call(services, "video.prepare", new JSONObject().put("url", fixture.getName())
                    .put("autoplay", true).put("loop", true).put("muted", true)
                    .put("bounds", new JSONObject().put("x", 0).put("y", 0).put("width", 80).put("height", 40).put("visible", false)));
            assertNull(prepared.error); assertNotNull(prepared.value); assertTrue(prepared.value.getBoolean("ok"));
            assertBackgroundMatchesVideo(activity, texture);
            waitFor("decoded video dimensions", 7000, () -> state(services).getInt("width") > 0 && state(services).getInt("height") > 0);
            JSONObject dimensions = state(services);
            assertEquals(16, dimensions.getInt("width")); assertEquals(16, dimensions.getInt("height"));

            waitFor("first frame while hidden", 7000, () -> state(services).getBoolean("hasRenderedFirstFrame"));
            assertEquals(0f, texture.getAlpha(), .001f);
            assertEquals(View.VISIBLE, texture.getVisibility());
            android.view.ViewGroup.LayoutParams originalLayout = texture.getLayoutParams();
            Result moved = call(services, "video.setBounds", new JSONObject().put("x",0).put("y",0).put("width",80).put("height",40).put("translateY",-10).put("visible",true));
            assertNull(moved.error);
            assertSame("a vertical drag must not relayout the decoder surface", originalLayout, texture.getLayoutParams());
            assertEquals(-10f*target.getResources().getDisplayMetrics().density, texture.getTranslationY(), .001f);
            assertEquals(1f, texture.getAlpha(), .001f);
            call(services, "video.setBounds", new JSONObject().put("x",0).put("y",0).put("width",80).put("height",40).put("translateY",0).put("visible",true));
            Matrix standard = transformOf(instrumentation, texture);
            Result rotated90 = call(services, "video.setTransform", new JSONObject().put("rotationDeg", 90));
            assertTrue(rotated90.value.getBoolean("ok"));
            Matrix ninety = transformOf(instrumentation, texture);
            assertMatrixChanged(standard, ninety);
            Result rotated45 = call(services, "video.setTransform", new JSONObject().put("rotationDeg", 45).put("scaleMode", "expanded"));
            assertTrue(rotated45.value.getBoolean("ok"));
            Matrix expanded = transformOf(instrumentation, texture);
            assertMatrixChanged(ninety, expanded);
            Result shrunk = call(services, "video.setTransform", new JSONObject().put("rotationDeg", 45).put("scaleMode", "shrunk"));
            assertTrue(shrunk.value.getBoolean("ok"));
            Matrix shrunkMatrix = transformOf(instrumentation, texture);
            assertMatrixChanged(expanded, shrunkMatrix);

            Result paused = call(services, "video.pause", new JSONObject());
            assertEquals("paused", paused.value.getString("state"));
            assertFalse(texture.getKeepScreenOn());
            Result resumed = call(services, "video.play", new JSONObject());
            assertTrue(resumed.value.getBoolean("ok"));
            waitFor("playing", 3000, () -> "playing".equals(state(services).getString("state")));
            assertTrue(texture.getKeepScreenOn());

            int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            Result volume = call(services, "video.setVolume", new JSONObject().put("volume", 0.25));
            assertTrue(volume.value.getBoolean("ok"));
            assertEquals(Math.round(max * .25f), audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
            Result stopped = call(services, "video.stop", new JSONObject());
            assertEquals("stopped", stopped.value.getString("state"));
            final boolean[] backgroundGone = {false};
            instrumentation.runOnMainSync(() -> backgroundGone[0] = activity.videoHost().getChildCount() > 0
                    && activity.videoHost().getChildAt(0).getVisibility() == android.view.View.GONE);
            assertTrue("video background remained visible after stop", backgroundGone[0]);
        } finally {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0);
            services.close();
            waitFor("video child removal", 3000, () -> activity.videoHost().getChildCount() == 0);
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            assertTrue(fixture.delete() || !fixture.exists());
        }
    }

    private static JSONObject state(PodVideoServices services) throws Exception { return call(services, "video.state", new JSONObject()).value; }
    private static Matrix transformOf(Instrumentation instrumentation, TextureView texture) {
        final Matrix[] matrix = new Matrix[1];
        instrumentation.runOnMainSync(() -> { matrix[0] = new Matrix(); texture.getTransform(matrix[0]); });
        return matrix[0];
    }
    private static void assertMatrixChanged(Matrix before, Matrix after) {
        float[] a = new float[9], b = new float[9]; before.getValues(a); after.getValues(b);
        boolean changed = false; for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > .001f) changed = true;
        assertTrue("TextureView transform did not change", changed);
    }
    private static void assertBackgroundMatchesVideo(VideoTestActivity activity, TextureView texture) {
        final String[] error = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (activity.videoHost().getChildCount() < 2) { error[0] = "video background sibling missing"; return; }
            View background = activity.videoHost().getChildAt(0);
            if (background == texture || activity.videoHost().getChildAt(1) != texture) { error[0] = "background is not directly below video"; return; }
            if (!(background.getBackground() instanceof android.graphics.drawable.ColorDrawable)
                    || ((android.graphics.drawable.ColorDrawable) background.getBackground()).getColor() != android.graphics.Color.BLACK) {
                error[0] = "video background is not black"; return;
            }
            android.view.ViewGroup.LayoutParams bp = background.getLayoutParams();
            android.view.ViewGroup.LayoutParams vp = texture.getLayoutParams();
            android.widget.FrameLayout.LayoutParams bm = (android.widget.FrameLayout.LayoutParams) bp;
            android.widget.FrameLayout.LayoutParams vm = (android.widget.FrameLayout.LayoutParams) vp;
            if (bp.width != vp.width || bp.height != vp.height || bm.leftMargin != vm.leftMargin
                    || bm.topMargin != vm.topMargin || background.getVisibility() != View.VISIBLE) {
                error[0] = "video background bounds or visibility differ"; return;
            }
            if (background.getClipToOutline() != texture.getClipToOutline()
                    || background.getOutlineProvider() != texture.getOutlineProvider()) error[0] = "video background clip differs";
        });
        assertNull(error[0]);
    }
    private static void copyFixture(Context test, File destination) throws Exception {
        File parent = destination.getParentFile(); assertTrue(parent.exists() || parent.mkdirs());
        try (InputStream input = test.getAssets().open("tiny.mp4"); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096]; for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
        }
        assertTrue(destination.isFile() && destination.length() > 0);
    }
    private static void waitFor(String label, long timeoutMs, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(25); }
        fail(label + " timed out");
    }
    private interface Condition { boolean get() throws Exception; }
    private static Result call(PodVideoServices services, String method, JSONObject args) throws Exception {
        Result result = new Result(); CountDownLatch done = new CountDownLatch(1);
        services.dispatch(method, args, new PodVideoServices.Callback() {
            @Override public void complete(JSONObject value) { result.value = value; result.calls++; done.countDown(); }
            @Override public void fail(String code, String message) { result.error = code; result.message = message; result.calls++; done.countDown(); }
        });
        assertTrue(method + " timeout", done.await(8, TimeUnit.SECONDS));
        assertEquals(method + " callback count", 1, result.calls);
        return result;
    }
    private static final class Result { JSONObject value; String error, message; int calls; }
    private static void assertError(PodVideoServices service,String method,JSONObject args,String expected)throws Exception {
        Result result=call(service,method,args);assertEquals("invalid_argument",result.error);assertEquals(expected,result.message);
    }
}
