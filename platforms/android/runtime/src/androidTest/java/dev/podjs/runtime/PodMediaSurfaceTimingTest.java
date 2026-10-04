package dev.podjs.runtime;

import static dev.podjs.runtime.PodMediaSurfaceServices.Timing.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Optional CPU timing diagnostics: no latency thresholds or GPU-time claims. */
public final class PodMediaSurfaceTimingTest {
    private static final String ID = "timing-image";

    @Test public void timingIsDisabledByDefaultAndStopsAfterObserverRemoval() throws Exception {
        try (Harness h = new Harness()) {
            Collector records = new Collector();
            assertNull(onMain(() -> h.services.setTimingObserver(null)));
            h.open(true);
            h.awaitReady();
            assertEquals(0, records.size());
            assertNull(onMain(() -> h.services.setTimingObserver(records)));
            assertReady(h.update(.5));
            records.assertHealthy();
            int measured = records.size(), writes = h.sink.writes.get();
            assertTrue("enabled observer receives dispatch and render", measured >= 2);
            assertSame(records, onMain(() -> h.services.setTimingObserver(null)));
            assertReady(h.update(.75));
            assertTrue(h.sink.writes.get() > writes);
            assertEquals("removed observer receives no further records", measured, records.size());
            assertNull(onMain(() -> h.services.setTimingObserver(null)));
        }
    }

    @Test public void realImageReportsDistinctCausalDispatchAndRenderTimelines() throws Exception {
        try (Harness h = new Harness()) {
            Collector records = new Collector();
            onMain(() -> h.services.setTimingObserver(records));
            JSONObject opened = h.open(true);
            assertReady(h.awaitReady());
            int initial = records.find(RENDER, INITIAL), decoded = records.find(RENDER, DECODE);
            int openedDispatch = records.find(DISPATCH, OPEN);
            assertEquals(-1, records.value(initial, HANDLE_BEFORE));
            assertEquals(opened.getInt("texture"), records.value(initial, HANDLE_AFTER));
            assertEquals(records.value(openedDispatch, SEQUENCE), records.value(initial, DISPATCH_SEQUENCE));
            assertEquals("decode retains the dispatch that requested it",
                    records.value(openedDispatch, SEQUENCE), records.value(decoded, DISPATCH_SEQUENCE));
            assertTrue(records.value(decoded, DECODE_REVISION) > records.value(initial, DECODE_REVISION));

            // State-only dispatches make the independent sequence domains
            // observably different without requiring any extra render.
            h.call("state", new JSONObject());
            h.call("state", new JSONObject());
            int[] before = h.sink.pixels.clone();
            assertReady(h.update(.5));
            assertFalse(Arrays.equals(before, h.sink.pixels));
            assertReady(h.update(.75));
            int styled = records.last(RENDER, STYLE), updatedDispatch = records.last(DISPATCH, UPDATE);
            assertEquals(records.value(updatedDispatch, SEQUENCE), records.value(styled, DISPATCH_SEQUENCE));
            assertNotEquals(records.value(styled, SEQUENCE), records.value(styled, DISPATCH_SEQUENCE));
            assertEquals(records.value(decoded, DECODE_REVISION), records.value(styled, DECODE_REVISION));
            assertEquals(opened.getInt("texture"), records.value(styled, HANDLE_BEFORE));
            assertEquals(opened.getInt("texture"), records.value(styled, HANDLE_AFTER));
            assertTrue(records.value(initial, BEGIN_NS) >= records.value(openedDispatch, BEGIN_NS));
            assertTrue(records.value(initial, END_NS) <= records.value(openedDispatch, WORK_END_NS));
            assertTrue(records.value(styled, BEGIN_NS) >= records.value(updatedDispatch, BEGIN_NS));
            assertTrue(records.value(styled, END_NS) <= records.value(updatedDispatch, WORK_END_NS));
            records.assertHealthy();
            for (int row = 0; row < records.size(); row++) {
                if (records.kind(row) == RENDER) {
                    assertRenderTimeline(records, row);
                    assertEquals(OK, records.value(row, OUTCOME));
                    assertEquals(0, records.value(row, FAILED_STAGE));
                } else assertDispatchTimeline(records, row, true);
            }

            // The dispatch buffer previously held successful callback stamps.
            // A rejected style must clear them and produce no render record.
            int renders = records.count(RENDER);
            Reply rejected = h.dispatch("update", new JSONObject().put("brightness", 5));
            assertEquals("invalid_argument", rejected.code);
            assertEquals("invalid_brightness", rejected.message);
            assertEquals(renders, records.count(RENDER));
            assertDispatchTimeline(records, records.last(DISPATCH, UPDATE), false);
        }
    }

    @Test public void observerAssertionErrorCannotChangeDecodedImageOrStyleOutput() throws Exception {
        try (Harness h = new Harness()) {
            Collector records = new Collector();
            PodMediaSurfaceServices.TimingObserver throwing = (id, kind, values) -> {
                records.record(id, kind, values);
                throw new AssertionError("intentional diagnostic observer failure");
            };
            onMain(() -> h.services.setTimingObserver(throwing));
            h.open(true);
            JSONObject ready = h.awaitReady();
            assertReady(ready);
            int[] sharp = h.sink.pixels.clone();
            int writes = h.sink.writes.get();
            JSONObject updated = h.update(.5);
            assertReady(updated);
            assertEquals(ready.getInt("texture"), updated.getInt("texture"));
            assertTrue(h.sink.writes.get() > writes);
            assertFalse("style still reaches the real renderer", Arrays.equals(sharp, h.sink.pixels));
            records.assertHealthy();
            assertTrue(records.count(RENDER) >= 3);
            assertTrue(records.count(DISPATCH) >= 3);
        }
    }

    @Test public void sinkAndPixelReadbackFailuresRetainOriginalErrorsAndPartialTimelines() throws Exception {
        for (int mode : new int[] {1, 2}) {
            try (Harness h = new Harness()) {
                Collector records = new Collector();
                onMain(() -> h.services.setTimingObserver(records));
                JSONObject good = h.open(false);
                assertReady(good);
                assertEquals(OK, records.value(records.find(RENDER, INITIAL), OUTCOME));
                // Reuse the same session and timing arrays after success.
                onMain(() -> { h.sink.mode = mode; return null; });
                JSONObject failed = h.update(.5);
                assertEquals("error", failed.getString("state"));
                assertEquals(mode == 1 ? "surface_render_failed: diagnostic sink failure" : "texture_upload_failed",
                        failed.getString("error"));
                assertEquals(good.getInt("texture"), failed.getInt("texture"));
                records.assertHealthy();
                int row = records.last(RENDER, STYLE);
                assertRenderTimeline(records, row);
                assertEquals(mode == 1 ? EXCEPTION : SINK_REJECTED, records.value(row, OUTCOME));
                assertEquals(SINK_STAGE, records.value(row, FAILED_STAGE));
                assertEquals(good.getInt("texture"), records.value(row, HANDLE_BEFORE));
                assertEquals(good.getInt("texture"), records.value(row, HANDLE_AFTER));
                int dispatch = records.last(DISPATCH, UPDATE);
                assertEquals(records.value(dispatch, SEQUENCE), records.value(row, DISPATCH_SEQUENCE));
                // A completed dispatch can truthfully return a surface error;
                // only the render attempt itself failed.
                assertDispatchTimeline(records, dispatch, true);
            }
        }

        long[] partial = new long[FIELD_COUNT];
        RuntimeException failure = onMain(() -> {
            try (PodMediaSurfaceServices.SurfaceRenderer renderer =
                         new PodMediaSurfaceServices.SurfaceRenderer(30, 18, 32, 32)) {
                PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(new JSONObject());
                try { renderer.render(null, null, 0, 0, style, new int[1], partial); }
                catch (RuntimeException error) { return error; }
                return null;
            }
        });
        assertNotNull("a one-pixel destination cannot hold the hardware readback", failure);
        assertTrue("getPixels bounds error must propagate unchanged: " + failure,
                failure instanceof ArrayIndexOutOfBoundsException);
        assertEquals(PIXELS_STAGE, partial[FAILED_STAGE]);
        long previous = 0;
        for (int field : new int[] {RECORD_BEGIN_NS, RECORD_END_NS, DRAW_BEGIN_NS, DRAW_END_NS,
                ACQUIRE_BEGIN_NS, ACQUIRE_END_NS, COPY_BEGIN_NS, COPY_END_NS, PIXELS_BEGIN_NS,
                PIXELS_END_NS, CLEANUP_BEGIN_NS, CLEANUP_END_NS}) {
            assertTrue("missing partial timestamp field=" + field, partial[field] > 0);
            assertTrue("partial timestamps out of order field=" + field, partial[field] >= previous);
            previous = partial[field];
        }
        assertEquals("failure cannot reach the texture sink", 0, partial[SINK_BEGIN_NS]);
        assertEquals(0, partial[SINK_END_NS]);
    }

    private static void assertRenderTimeline(Collector records, int row) {
        assertOrdered(records, row, BEGIN_NS, RECORD_BEGIN_NS, RECORD_END_NS, DRAW_BEGIN_NS, DRAW_END_NS,
                ACQUIRE_BEGIN_NS, ACQUIRE_END_NS, COPY_BEGIN_NS, COPY_END_NS, PIXELS_BEGIN_NS, PIXELS_END_NS,
                CLEANUP_BEGIN_NS, CLEANUP_END_NS, SINK_BEGIN_NS, SINK_END_NS, END_NS);
        assertEquals(30, records.value(row, WIDTH));
        assertEquals(18, records.value(row, HEIGHT));
        assertEquals(32, records.value(row, TEXTURE_WIDTH));
        assertEquals(32, records.value(row, TEXTURE_HEIGHT));
        assertZero(records, row, ENQUEUED_NS, WORK_END_NS, COMPLETE_BEGIN_NS, COMPLETE_END_NS, FAIL_BEGIN_NS, FAIL_END_NS);
    }

    private static void assertDispatchTimeline(Collector records, int row, boolean success) {
        assertEquals(DISPATCH, records.kind(row));
        assertEquals(records.value(row, SEQUENCE), records.value(row, DISPATCH_SEQUENCE));
        if (success) {
            assertOrdered(records, row, ENQUEUED_NS, BEGIN_NS, WORK_END_NS, COMPLETE_BEGIN_NS, COMPLETE_END_NS, END_NS);
            assertZero(records, row, FAIL_BEGIN_NS, FAIL_END_NS);
        } else {
            assertOrdered(records, row, ENQUEUED_NS, BEGIN_NS, WORK_END_NS, FAIL_BEGIN_NS, FAIL_END_NS, END_NS);
            assertZero(records, row, COMPLETE_BEGIN_NS, COMPLETE_END_NS);
        }
        assertEquals(success ? OK : EXCEPTION, records.value(row, OUTCOME));
        assertZero(records, row, FAILED_STAGE, RECORD_BEGIN_NS, RECORD_END_NS, DRAW_BEGIN_NS, DRAW_END_NS,
                ACQUIRE_BEGIN_NS, ACQUIRE_END_NS, COPY_BEGIN_NS, COPY_END_NS, PIXELS_BEGIN_NS, PIXELS_END_NS,
                CLEANUP_BEGIN_NS, CLEANUP_END_NS, SINK_BEGIN_NS, SINK_END_NS);
    }

    private static void assertOrdered(Collector records, int row, int... fields) {
        long previous = 0;
        for (int field : fields) {
            long value = records.value(row, field);
            assertTrue("missing timestamp row=" + row + " field=" + field, value > 0);
            assertTrue("timestamps out of order row=" + row + " field=" + field, value >= previous);
            previous = value;
        }
    }

    private static void assertZero(Collector records, int row, int... fields) {
        for (int field : fields) assertEquals("unused field row=" + row + " field=" + field, 0, records.value(row, field));
    }

    /** Fixed capacity; borrowed values are copied before record returns. No
     * assertions or per-record allocations inside the observer callback. */
    private static final class Collector implements PodMediaSurfaceServices.TimingObserver {
        static final int CAPACITY = 512;
        final long[] values = new long[CAPACITY * FIELD_COUNT];
        final int[] kinds = new int[CAPACITY];
        final String[] ids = new String[CAPACITY];
        int size, overflow, invalidSchema;
        boolean offMain;
        @Override public synchronized void record(String id, int kind, long[] borrowed) {
            if (Looper.myLooper() != Looper.getMainLooper()) offMain = true;
            if (borrowed == null || borrowed.length != FIELD_COUNT) { invalidSchema++; return; }
            if (size == CAPACITY) { overflow++; return; }
            ids[size] = id;
            kinds[size] = kind;
            System.arraycopy(borrowed, 0, values, size * FIELD_COUNT, FIELD_COUNT);
            size++;
        }
        synchronized int size() { return size; }
        synchronized int kind(int row) { return kinds[row]; }
        synchronized long value(int row, int field) { return values[row * FIELD_COUNT + field]; }
        synchronized int count(int kind) { int count = 0; for (int row = 0; row < size; row++) if (kinds[row] == kind) count++; return count; }
        synchronized int find(int kind, int cause) {
            for (int row = 0; row < size; row++) if (kinds[row] == kind && value(row, CAUSE) == cause) return row;
            throw new AssertionError("missing timing record kind=" + kind + " cause=" + cause);
        }
        synchronized int last(int kind, int cause) {
            for (int row = size - 1; row >= 0; row--) if (kinds[row] == kind && value(row, CAUSE) == cause) return row;
            throw new AssertionError("missing timing record kind=" + kind + " cause=" + cause);
        }
        synchronized void assertHealthy() {
            assertEquals("collector overflow", 0, overflow);
            assertEquals("unexpected timing schema", 0, invalidSchema);
            assertFalse("observer callbacks must stay on main", offMain);
            long dispatch = 0, render = 0;
            for (int row = 0; row < size; row++) {
                assertEquals(ID, ids[row]);
                long sequence = value(row, SEQUENCE);
                if (kinds[row] == DISPATCH) { assertTrue(sequence > dispatch); dispatch = sequence; }
                else { assertEquals(RENDER, kinds[row]); assertTrue(sequence > render); render = sequence; }
            }
        }
    }

    private static final class Sink implements PodMediaSurfaceServices.TextureSink {
        final AtomicInteger writes = new AtomicInteger();
        final int[] pixels = new int[32 * 32];
        int mode;
        @Override public int write(int handle, int[] argb, int width, int height, boolean linear) {
            writes.incrementAndGet();
            if (mode == 1) throw new IllegalStateException("diagnostic sink failure");
            if (mode == 2) return -1;
            System.arraycopy(argb, 0, pixels, 0, argb.length);
            return handle < 0 ? 73 : handle;
        }
        @Override public void free(int handle) { }
    }

    private static final class Reply {
        JSONObject value;
        String code, message;
    }

    private static final class Harness implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final PodMediaSurfaceServices services = new PodMediaSurfaceServices(context);
        final Sink sink = new Sink();
        final File directory = new File(context.getFilesDir(), "podjs/files/media-timing-" + UUID.randomUUID());
        final File source = new File(directory, "source.png");
        Harness() throws Exception {
            assertTrue(directory.mkdirs());
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            try {
                for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
                    bitmap.setPixel(x, y, ((x / 8 + y / 8) & 1) == 0 ? Color.BLACK : Color.WHITE);
                try (FileOutputStream out = new FileOutputStream(source)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                }
            } finally { bitmap.recycle(); }
            onMain(() -> { services.attach(new FrameLayout(context), sink); return null; });
        }
        JSONObject open(boolean image) throws Exception {
            JSONObject args = new JSONObject().put("widthPx", 30).put("heightPx", 18);
            if (image) args.put("path", directory.getName() + "/source.png");
            return call("open", args);
        }
        JSONObject update(double brightness) throws Exception { return call("update", new JSONObject().put("brightness", brightness)); }
        JSONObject awaitReady() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                JSONObject state = call("state", new JSONObject());
                assertTrue(state.optString("error"), state.isNull("error"));
                if ("ready".equals(state.getString("state"))) return state;
                assertEquals("loading", state.getString("state"));
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("image decode did not become ready");
        }
        JSONObject call(String action, JSONObject args) throws Exception {
            Reply reply = dispatch(action, args);
            assertNull(action + " failed: " + reply.message, reply.code);
            assertNotNull(reply.value);
            return reply.value;
        }
        Reply dispatch(String action, JSONObject args) throws Exception {
            Reply reply = new Reply();
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            services.dispatch("image.surface." + action, args.put("id", ID), new PodMediaSurfaceServices.Callback() {
                @Override public void complete(JSONObject value) { reply.value = value; callbacks.incrementAndGet(); done.countDown(); }
                @Override public void fail(String code, String message) { reply.code = code; reply.message = message; callbacks.incrementAndGet(); done.countDown(); }
            });
            assertTrue(action + " timed out", done.await(8, TimeUnit.SECONDS));
            // Dispatch timing is emitted in finally, after the callback returns.
            onMain(() -> null);
            assertEquals(1, callbacks.get());
            return reply;
        }
        @Override public void close() throws Exception {
            onMain(() -> services.setTimingObserver(null));
            try { call("close", new JSONObject()); }
            finally {
                services.close();
                onMain(() -> null);
                if (source.exists()) assertTrue(source.delete());
                assertTrue(directory.delete());
            }
        }
    }

    private static void assertReady(JSONObject state) throws Exception {
        assertEquals("ready", state.getString("state"));
        assertTrue(state.optString("error"), state.isNull("error"));
    }

    private static <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        return result.get();
    }
}
