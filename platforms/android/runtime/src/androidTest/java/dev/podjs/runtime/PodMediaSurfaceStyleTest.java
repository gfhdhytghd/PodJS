package dev.podjs.runtime;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class PodMediaSurfaceStyleTest {
    @Test public void omittedStyleKeepsExistingBrightnessAndDecalDefaults() throws Exception {
        PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(new JSONObject());
        assertEquals(0f, style.brightnessBias, 0f);
        assertEquals("decal", style.blurEdgeMode);
        assertEquals(1f, style.brightness, 0f);
        assertEquals(1f, style.saturation, 0f);
        assertEquals(0f, style.blur, 0f);
        assertNull(style.presentationFrame);
    }

    @Test public void normalizedBiasAcceptsBothEndpointsAndExplicitZero() throws Exception {
        for (float bias : new float[] {-1f, -0.25f, 0f, 58f / 255f, 1f}) {
            for (String edge : new String[] {"decal", "clamp"}) {
                JSONObject args = new JSONObject().put("brightnessBias", bias).put("blurEdgeMode", edge);
                PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(args);
                assertEquals(bias, style.brightnessBias, 0f);
                assertEquals(edge, style.blurEdgeMode);
            }
        }
    }

    @Test public void invalidBiasAndBlurEdgeInputsAreRejectedInsteadOfCoerced() throws Exception {
        for (Object value : new Object[] {-1.0001, 1.0001, "0", true, JSONObject.NULL,
                new JSONObject(), new JSONArray()}) {
            rejectStyle("brightnessBias", value);
        }
        for (Object value : new Object[] {"", "CLAMP", "mirror", "repeat", 0, true,
                JSONObject.NULL, new JSONObject(), new JSONArray()}) {
            rejectStyle("blurEdgeMode", value);
        }
    }

    @Test public void textureSamplingDefaultsToNearestAndAcceptsOnlyExactModes() throws Exception {
        assertFalse(PodMediaSurfaceServices.linearSampling(new JSONObject()));
        assertFalse(PodMediaSurfaceServices.linearSampling(new JSONObject().put("textureSampling", "nearest")));
        assertTrue(PodMediaSurfaceServices.linearSampling(new JSONObject().put("textureSampling", "linear")));
        for (Object value : new Object[] {"", "NEAREST", "LINEAR", "Linear", " linear", "linear ",
                "bilinear", "clamp", 0, 1, .5, true, false, JSONObject.NULL,
                new JSONObject(), new JSONArray()}) {
            try {
                PodMediaSurfaceServices.linearSampling(new JSONObject().put("textureSampling", value));
                fail("accepted textureSampling=" + value);
            } catch (IllegalArgumentException expected) {
                assertEquals("invalid_textureSampling", expected.getMessage());
            }
        }
    }

    @Test public void presentationFrameAcceptsBoundariesAndNormalizesIntegralDimensions() throws Exception {
        JSONObject[] frames = {
                frameArgs(-32768, 32768, 1, 1024, .25, 0),
                frameArgs(32768, -32768, 1024, 1, 8, 1),
                frameArgs(-.5, 21.25, 24, 20, 1.1, .5).put("widthPx", 24.0).put("heightPx", 20.0)
        };
        for (JSONObject expected : frames) {
            PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(
                    new JSONObject().put("presentationFrame", expected));
            PodMediaSurfaceServices.PresentationFrame frame = style.presentationFrame;
            assertNotNull(frame);
            assertEquals((float) expected.getDouble("leftPx"), frame.left, 0f);
            assertEquals((float) expected.getDouble("topPx"), frame.top, 0f);
            assertEquals(expected.getInt("widthPx"), frame.width);
            assertEquals(expected.getInt("heightPx"), frame.height);
            assertEquals((float) expected.getDouble("scale"), frame.scale, 0f);
            assertEquals((float) expected.getDouble("opacity"), frame.opacity, 0f);
            assertFrameState(expected, frame.toJson());
        }
    }

    @Test public void presentationFrameRejectsMalformedObjectsAndEveryMissingField() throws Exception {
        for (Object value : new Object[] {JSONObject.NULL, "", "frame", 0, 1, true, false,
                new JSONArray(), new JSONObject()}) {
            rejectStyle("presentationFrame", value);
        }
        for (String field : new String[] {"leftPx", "topPx", "widthPx", "heightPx", "scale", "opacity"}) {
            JSONObject missing = frameArgs(20, 21, 24, 20, 1.1, 1);
            missing.remove(field);
            rejectStyle("presentationFrame", missing);
            for (Object value : new Object[] {JSONObject.NULL, "1", true, false, new JSONObject(), new JSONArray()}) {
                rejectStyle("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, 1).put(field, value));
            }
            // JSONObject.put rejects non-finite numbers itself. Override the raw
            // value accessor to exercise the native parser's own finite guard.
            for (double value : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                JSONObject nonfinite = new JSONObject(frameArgs(20, 21, 24, 20, 1.1, 1).toString()) {
                    @Override public Object opt(String name) { return field.equals(name) ? value : super.opt(name); }
                };
                rejectStyle("presentationFrame", nonfinite);
            }
        }
        for (String field : new String[] {"leftPx", "topPx"}) {
            for (double value : new double[] {-32768.01, 32768.01}) {
                rejectStyle("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, 1).put(field, value));
            }
        }
        for (String field : new String[] {"widthPx", "heightPx"}) {
            for (double value : new double[] {-1, 0, .5, 1.5, 1024.5, 1025}) {
                rejectStyle("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, 1).put(field, value));
            }
        }
        for (double value : new double[] {0, .2499, 8.0001}) {
            rejectStyle("presentationFrame", frameArgs(20, 21, 24, 20, value, 1));
        }
        for (double value : new double[] {-.0001, 1.0001}) {
            rejectStyle("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, value));
        }
    }

    @Test public void presentationFrameMatchesSinglePassHardwareBitmapTransform() throws Exception {
        Bitmap source = Bitmap.createBitmap(37, 31, Bitmap.Config.ARGB_8888);
        Bitmap cached = null;
        try {
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                source.setPixel(x, y, ((x + y) & 1) == 0 ? Color.BLACK : Color.WHITE);
            }
            PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(new JSONObject()
                    .put("fit", "fill").put("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, 1)));
            int[] actual = renderPixels(source, style, 64, 64);
            // Fixed viewport-space oracle: center (32,31), extent 26.4 x 22.
            // One drawBitmap maps the original source directly to its final size;
            // it never creates a 24 x 20 frame bitmap or uses production geometry.
            RectF destination = new RectF(18.8f, 20f, 45.2f, 42f);
            int[] expected = onMain(() -> hardwarePixels(64, 64, canvas ->
                    canvas.drawBitmap(source, null, destination, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG))));
            assertRegionNear(expected, actual, 64, 21, 22, 43, 40, 2);
            assertTransparentOutside(actual, 64, 64, 18, 19, 47, 44);
            int lowest = 255, highest = 0;
            for (int y = 22; y < 40; y++) for (int x = 21; x < 43; x++) {
                int pixel = expected[y * 64 + x];
                lowest = Math.min(lowest, Color.red(pixel));
                highest = Math.max(highest, Color.red(pixel));
                assertEquals("opaque reference interior", 255, Color.alpha(pixel));
                assertEquals("opaque presentation interior", 255, Color.alpha(actual[y * 64 + x]));
            }
            assertTrue("reference must retain checkerboard contrast", highest - lowest > 64);

            // A deliberately cached frame is a negative control for the fixture:
            // the high-frequency source must expose the extra filtering pass.
            int[] local = onMain(() -> hardwarePixels(24, 20, canvas ->
                    canvas.drawBitmap(source, null, new RectF(0, 0, 24, 20),
                            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG))));
            cached = Bitmap.createBitmap(local, 24, 20, Bitmap.Config.ARGB_8888);
            Bitmap cachedFrame = cached;
            int[] twiceSampled = onMain(() -> hardwarePixels(64, 64, canvas ->
                    canvas.drawBitmap(cachedFrame, null, destination,
                            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG))));
            int differing = 0;
            for (int y = 22; y < 40; y++) for (int x = 21; x < 43; x++) {
                if (Math.abs(Color.red(expected[y * 64 + x]) - Color.red(twiceSampled[y * 64 + x])) > 2) differing++;
            }
            assertTrue("fixture must expose a cached intermediate; differing pixels=" + differing, differing > 64);
        } finally {
            source.recycle();
            if (cached != null) cached.recycle();
        }
    }

    @Test public void presentationOpacityAppliesOnceAfterBaseMediaAndOverlayComposition() throws Exception {
        Bitmap source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.BLUE);
        try {
            onMain(() -> {
                try (PodMediaSurfaceServices.SurfaceRenderer renderer =
                             new PodMediaSurfaceServices.SurfaceRenderer(64, 64, 64, 64)) {
                    for (double opacity : new double[] {1, .5, 0, 1}) {
                        PodMediaSurfaceServices.Style style = new PodMediaSurfaceServices.Style(new JSONObject()
                                .put("fit", "fill").put("colorArgb", Color.RED)
                                .put("overlayColorArgb", Color.WHITE).put("overlayOpacity", .25)
                                .put("presentationFrame", frameArgs(20, 21, 24, 20, 1.1, opacity)));
                        int[] actual = new int[64 * 64];
                        renderer.render(source, null, 0, 0, style, actual);
                        // Opaque blue covers red, then the 25% white overlay yields
                        // (64,64,255). Group alpha must not accumulate across draws.
                        // Returning to opacity 1 also catches stale layer alpha.
                        if (opacity == 0) {
                            for (int pixel : actual) assertEquals(0, Color.alpha(pixel));
                        } else {
                            int expected = Color.argb(opacity == 1 ? 255 : 128, 64, 64, 255);
                            for (int y = 23; y < 39; y++) for (int x = 22; x < 42; x++) {
                                assertPixelNear("opacity=" + opacity + " at " + x + "," + y,
                                        expected, actual[y * 64 + x], 2);
                            }
                        }
                        assertTransparentOutside(actual, 64, 64, 18, 19, 47, 44);
                    }
                }
                return null;
            });
        } finally { source.recycle(); }
    }

    @Test public void presentationFrameUpdatesAckStateAndReuseDecodedImageAndTexture() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "podjs/files/media-surface-frame-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        File source = new File(directory, "source.png"), moved = new File(directory, "decoded-only.png");
        PodMediaSurfaceServices services = new PodMediaSurfaceServices(context);
        RecordingSink sink = new RecordingSink();
        String id = "presentation-frame", otherId = "presentation-frame-other";
        JSONObject initialFrame = frameArgs(20, 21, 24, 20, 1.1, 1);
        JSONObject otherFrame = frameArgs(2, 4, 20, 28, .8, .6);
        try {
            writeCheckerboard(source);
            onMain(() -> { services.attach(new FrameLayout(context), sink); return null; });
            JSONObject opened = call(services, "image.surface.open", new JSONObject().put("id", id)
                    .put("path", directory.getName() + "/" + source.getName())
                    .put("widthPx", 64).put("heightPx", 64).put("textureSampling", "linear")
                    .put("fit", "fill").put("presentationFrame", initialFrame));
            assertFrameState(initialFrame, opened.getJSONObject("presentationFrame"));
            JSONObject ready = awaitReady(services, id);
            assertFrameState(initialFrame, ready.getJSONObject("presentationFrame"));
            PodMediaSurfaceServices.Session owner = onMain(() -> session(services, id));
            Snapshot decoded = onMain(() -> new Snapshot(owner));
            assertNotNull(decoded.image);
            int texture = ready.getInt("texture");
            int writes = assertSamplingWrites(sink, texture, true, 2);
            call(services, "image.surface.open", new JSONObject().put("id", otherId)
                    .put("path", directory.getName() + "/" + source.getName())
                    .put("widthPx", 64).put("heightPx", 64).put("fit", "fill")
                    .put("presentationFrame", otherFrame));
            JSONObject otherReady = awaitReady(services, otherId);
            int otherTexture = otherReady.getInt("texture");
            assertTrue(texture != otherTexture);
            PodMediaSurfaceServices.Session otherOwner = onMain(() -> session(services, otherId));
            assertNotSame(owner, otherOwner);
            Snapshot otherDecoded = onMain(() -> new Snapshot(otherOwner));
            assertNotNull(otherDecoded.image);
            int[] otherPixels = sink.pixelsFor(otherTexture);
            int otherWrites = assertSamplingWrites(sink, otherTexture, false, 2);
            int[] previousPixels = sink.pixelsFor(texture);
            assertEquals("initial frame occupies the clearing probe", 255, Color.alpha(previousPixels[35 * 64 + 40]));
            assertTrue(source.renameTo(moved));
            assertFalse(source.exists());

            int frameIndex = 0;
            for (JSONObject frame : new JSONObject[] {frameArgs(5.5, 7.25, 32, 16, .75, .5),
                    frameArgs(-4, 15, 40, 30, 1.5, 1), frameArgs(11, 3, 18, 40, 1, 0)}) {
                JSONObject updated = call(services, "image.surface.update", new JSONObject().put("id", id)
                        .put("fit", "fill").put("presentationFrame", frame));
                assertFrameState(frame, updated.getJSONObject("presentationFrame"));
                assertEquals("ready", updated.getString("state"));
                assertTrue(updated.isNull("error"));
                assertEquals(texture, updated.getInt("texture"));
                assertEquals(64, updated.getInt("contentWidth"));
                assertEquals(64, updated.getInt("contentHeight"));
                assertEquals("linear", updated.getString("textureSampling"));
                Snapshot current = onMain(() -> new Snapshot(owner));
                assertSame(decoded.image, current.image);
                assertEquals(decoded.revision, current.revision);
                assertFalse(current.loading);
                assertFalse(decoded.image.isRecycled());
                writes = assertSamplingWrites(sink, texture, true, writes + 1);
                int[] currentPixels = sink.pixelsFor(texture);
                assertFalse("presentation update must change rendered pixels", Arrays.equals(previousPixels, currentPixels));
                if (frameIndex++ == 0) {
                    assertEquals("old frame location must clear after movement", 0, Color.alpha(currentPixels[35 * 64 + 40]));
                }
                previousPixels = currentPixels;
                JSONObject state = call(services, "image.surface.state", new JSONObject().put("id", id));
                assertFrameState(frame, state.getJSONObject("presentationFrame"));
                JSONObject otherState = call(services, "image.surface.state", new JSONObject().put("id", otherId));
                assertFrameState(otherFrame, otherState.getJSONObject("presentationFrame"));
                assertEquals(otherTexture, otherState.getInt("texture"));
                assertSame(otherOwner, onMain(() -> session(services, otherId)));
                Snapshot otherCurrent = onMain(() -> new Snapshot(otherOwner));
                assertSame(otherDecoded.image, otherCurrent.image);
                assertEquals(otherDecoded.revision, otherCurrent.revision);
                assertFalse(otherCurrent.loading);
                assertEquals(otherWrites, assertSamplingWrites(sink, otherTexture, false, otherWrites));
                assertArrayEquals("another owner's pixels must not change", otherPixels, sink.pixelsFor(otherTexture));
            }

            assertCallFails(services, "image.surface.update", new JSONObject().put("id", id)
                    .put("presentationFrame", JSONObject.NULL), "invalid_presentationFrame");
            assertEquals(writes, assertSamplingWrites(sink, texture, true, writes));
            assertEquals(0f, onMain(() -> owner.style.presentationFrame.opacity), 0f);

            // Updates replace the complete style. Omitting presentationFrame
            // restores the legacy full viewport without decoding or reallocating.
            JSONObject reset = call(services, "image.surface.update", new JSONObject().put("id", id));
            assertTrue(reset.has("presentationFrame"));
            assertTrue(reset.isNull("presentationFrame"));
            assertEquals(texture, reset.getInt("texture"));
            assertEquals("ready", reset.getString("state"));
            assertTrue(reset.isNull("error"));
            assertNull(onMain(() -> owner.style.presentationFrame));
            Snapshot restored = onMain(() -> new Snapshot(owner));
            assertSame(decoded.image, restored.image);
            assertEquals(decoded.revision, restored.revision);
            assertFalse(restored.loading);
            writes = assertSamplingWrites(sink, texture, true, writes + 1);
            int[] restoredPixels = sink.pixelsFor(texture);
            for (int pixel : restoredPixels) assertEquals(255, Color.alpha(pixel));
            JSONObject state = call(services, "image.surface.state", new JSONObject().put("id", id));
            assertTrue(state.has("presentationFrame"));
            assertTrue(state.isNull("presentationFrame"));

            JSONObject changedOtherFrame = frameArgs(29, 1, 18, 30, 1.2, 1);
            JSONObject changedOther = call(services, "image.surface.update", new JSONObject().put("id", otherId)
                    .put("fit", "fill").put("presentationFrame", changedOtherFrame));
            assertFrameState(changedOtherFrame, changedOther.getJSONObject("presentationFrame"));
            assertEquals(otherTexture, changedOther.getInt("texture"));
            assertEquals("ready", changedOther.getString("state"));
            assertTrue(changedOther.isNull("error"));
            assertEquals("nearest", changedOther.getString("textureSampling"));
            assertSame(otherOwner, onMain(() -> session(services, otherId)));
            Snapshot otherUpdated = onMain(() -> new Snapshot(otherOwner));
            assertSame(otherDecoded.image, otherUpdated.image);
            assertEquals(otherDecoded.revision, otherUpdated.revision);
            assertFalse(otherUpdated.loading);
            assertSamplingWrites(sink, otherTexture, false, otherWrites + 1);
            assertFalse(Arrays.equals(otherPixels, sink.pixelsFor(otherTexture)));
            JSONObject unchanged = call(services, "image.surface.state", new JSONObject().put("id", id));
            assertTrue(unchanged.isNull("presentationFrame"));
            assertEquals(texture, unchanged.getInt("texture"));
            assertEquals("ready", unchanged.getString("state"));
            assertTrue(unchanged.isNull("error"));
            assertSame(owner, onMain(() -> session(services, id)));
            Snapshot stillRestored = onMain(() -> new Snapshot(owner));
            assertSame(decoded.image, stillRestored.image);
            assertEquals(decoded.revision, stillRestored.revision);
            assertFalse(stillRestored.loading);
            assertEquals(writes, assertSamplingWrites(sink, texture, true, writes));
            assertArrayEquals(restoredPixels, sink.pixelsFor(texture));
        } finally {
            try {
                call(services, "image.surface.close", new JSONObject().put("id", id));
                call(services, "image.surface.close", new JSONObject().put("id", otherId));
            }
            finally {
                services.close();
                onMain(() -> null);
                if (source.exists()) assertTrue(source.delete());
                if (moved.exists()) assertTrue(moved.delete());
                assertTrue(directory.delete());
            }
        }
    }

    @Test public void imageSessionsOwnCreationSamplingAcrossStyleUpdates() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "podjs/files/media-surface-sampling-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        File source = new File(directory, "source.png");
        PodMediaSurfaceServices services = new PodMediaSurfaceServices(context);
        RecordingSink sink = new RecordingSink();
        String[] requestedModes = {null, "nearest", "linear"};
        int[] textures = new int[requestedModes.length];
        PodMediaSurfaceServices.Session[] owners = new PodMediaSurfaceServices.Session[requestedModes.length];
        Snapshot[] decoded = new Snapshot[requestedModes.length];
        try {
            writeCheckerboard(source);
            onMain(() -> { services.attach(new FrameLayout(context), sink); return null; });
            for (int index = 0; index < requestedModes.length; index++) {
                String id = "sampling-" + index;
                boolean linear = "linear".equals(requestedModes[index]);
                String expectedMode = linear ? "linear" : "nearest";
                JSONObject args = new JSONObject().put("id", id)
                        .put("path", directory.getName() + "/" + source.getName())
                        .put("widthPx", 32).put("heightPx", 32);
                if (requestedModes[index] != null) args.put("textureSampling", requestedModes[index]);
                JSONObject opened = call(services, "image.surface.open", args);
                textures[index] = opened.getInt("texture");
                assertTrue(textures[index] >= 0);
                assertEquals(expectedMode, opened.getString("textureSampling"));
                assertSamplingWrites(sink, textures[index], linear, 1);
                JSONObject ready = awaitReady(services, id);
                assertEquals(expectedMode, ready.getString("textureSampling"));
                assertEquals(textures[index], ready.getInt("texture"));
                PodMediaSurfaceServices.Session owner = onMain(() -> session(services, id));
                owners[index] = owner;
                decoded[index] = onMain(() -> new Snapshot(owner));
                assertEquals(linear, owner.linearSampling);
                assertNotNull(decoded[index].image);
                assertFalse(decoded[index].loading);
                assertSamplingWrites(sink, textures[index], linear, 2);
                for (int previous = 0; previous < index; previous++) {
                    assertNotSame(owners[previous], owner);
                    assertTrue("sessions must own distinct textures", textures[previous] != textures[index]);
                }
            }

            // All three owners remain live while each is updated, so a shared
            // sampling flag cannot silently replace an earlier session's mode.
            for (int index = 0; index < requestedModes.length; index++) {
                String id = "sampling-" + index;
                boolean linear = "linear".equals(requestedModes[index]);
                String expectedMode = linear ? "linear" : "nearest";
                PodMediaSurfaceServices.Session owner = owners[index];
                int writes = assertSamplingWrites(sink, textures[index], linear, 2);
                JSONObject updated = call(services, "image.surface.update", new JSONObject()
                        .put("id", id).put("brightness", .5));
                assertEquals("ready", updated.getString("state"));
                assertTrue(updated.isNull("error"));
                assertEquals(expectedMode, updated.getString("textureSampling"));
                assertEquals(textures[index], updated.getInt("texture"));
                assertSame(owner, onMain(() -> session(services, id)));
                assertEquals(linear, owner.linearSampling);
                Snapshot current = onMain(() -> new Snapshot(owner));
                assertSame(decoded[index].image, current.image);
                assertEquals(decoded[index].revision, current.revision);
                assertFalse(current.loading);
                writes = assertSamplingWrites(sink, textures[index], linear, writes + 1);

                // Creation options are immutable even when repeated unchanged,
                // and rejection must precede any requested style mutation.
                for (Object mode : new Object[] {"nearest", "linear", "invalid", false, JSONObject.NULL}) {
                    assertCallFails(services, "image.surface.update", new JSONObject()
                            .put("id", id).put("textureSampling", mode).put("brightness", 1),
                            "immutable_textureSampling");
                    assertEquals(.5f, onMain(() -> owner.style.brightness), 0f);
                    assertEquals(writes, assertSamplingWrites(sink, textures[index], linear, writes));
                }
                JSONObject state = call(services, "image.surface.state", new JSONObject().put("id", id));
                assertEquals("ready", state.getString("state"));
                assertTrue(state.isNull("error"));
                assertEquals(expectedMode, state.getString("textureSampling"));
                assertEquals(textures[index], state.getInt("texture"));
            }
        } finally {
            try {
                for (int index = 0; index < requestedModes.length; index++) {
                    call(services, "image.surface.close", new JSONObject().put("id", "sampling-" + index));
                }
            } finally {
                services.close();
                onMain(() -> null);
                if (source.exists()) assertTrue(source.delete());
                assertTrue(directory.delete());
            }
        }
    }

    @Test public void brightnessBiasAddsAfterGainAndPreservesPixelAlpha() {
        int[] output = filtered(PodMediaSurfaceServices.colorMatrix(.5f, 1f, .25f),
                Color.BLACK, Color.WHITE, Color.rgb(128, 128, 128), Color.argb(128, 64, 64, 64));
        // The normalized bias is +63.75, independent of the 0.5 gain.
        assertColorNear(Color.rgb(64, 64, 64), output[0]);
        assertColorNear(Color.rgb(191, 191, 191), output[1]);
        assertColorNear(Color.rgb(128, 128, 128), output[2]);
        assertColorNear(Color.argb(128, 96, 96, 96), output[3]);
    }

    @Test public void additiveBiasBrightensOpaqueBlackWithoutDimmingWhite() {
        int[] output = filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f, 58f / 255f),
                Color.BLACK, Color.WHITE);
        assertColorNear(Color.rgb(58, 58, 58), output[0]);
        assertColorNear(Color.WHITE, output[1]);
    }

    @Test public void combinedBiasAndWhiteOverlayKeepBinaryWhiteAndPixelAlpha() {
        float bias = 58f / 255f, overlay = .24f;
        float combined = bias + (1 - bias) * overlay;
        int[] output = filtered(PodMediaSurfaceServices.colorMatrix(1 - combined, 1, combined),
                Color.BLACK, Color.WHITE, Color.argb(128, 0, 0, 0), Color.argb(64, 255, 255, 255));
        // Fixed oracle: 58 + (255 - 58) * 0.24 = 105.28 for black;
        // the gain and offset sum to one, so white stays 255.
        assertColorNear(Color.rgb(105, 105, 105), output[0]);
        assertColorNear(Color.WHITE, output[1]);
        assertColorNear(Color.argb(128, 105, 105, 105), output[2]);
        assertColorNear(Color.argb(64, 255, 255, 255), output[3]);
    }

    @Test public void negativeAndEndpointBiasClampRenderedChannels() {
        int[] negative = filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f, -.25f),
                Color.BLACK, Color.WHITE, Color.rgb(128, 128, 128));
        assertColorNear(Color.BLACK, negative[0]);
        assertColorNear(Color.rgb(191, 191, 191), negative[1]);
        assertColorNear(Color.rgb(64, 64, 64), negative[2]);
        assertArrayEquals(new int[] {Color.WHITE, Color.WHITE},
                filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f, 1f), Color.BLACK, Color.WHITE));
        assertArrayEquals(new int[] {Color.BLACK, Color.BLACK},
                filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f, -1f), Color.BLACK, Color.WHITE));
    }

    @Test public void legacyDesaturationAndHalfGainMatchFixedColorGolden() {
        int[] source = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK};
        int[] expected = {Color.rgb(27, 27, 27), Color.rgb(91, 91, 91), Color.rgb(9, 9, 9),
                Color.rgb(128, 128, 128), Color.BLACK};
        // Independent legacy oracle; comparing the two overloads only to each
        // other would miss a shared regression because one delegates to the other.
        for (ColorMatrix matrix : new ColorMatrix[] {PodMediaSurfaceServices.colorMatrix(.5f, 0f),
                PodMediaSurfaceServices.colorMatrix(.5f, 0f, 0f)}) {
            int[] output = filtered(matrix, source);
            for (int index = 0; index < expected.length; index++) assertColorNear(expected[index], output[index]);
        }
        assertArrayEquals(source, filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f), source));
        assertArrayEquals(source, filtered(PodMediaSurfaceServices.colorMatrix(1f, 1f, 0f), source));
    }

    @Test public void api31BlurUpdatesKeepDecodedImageWhenSourceIsNoLongerPresent() throws Exception {
        assumeTrue("RenderEffect blur update regression requires Android 31+", Build.VERSION.SDK_INT >= 31);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "podjs/files/media-surface-style-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        File source = new File(directory, "source.png"), moved = new File(directory, "decoded-only.png");
        PodMediaSurfaceServices services = new PodMediaSurfaceServices(context);
        RecordingSink sink = new RecordingSink();
        String id = "style-regression";
        try {
            writeCheckerboard(source);
            onMain(() -> { services.attach(new FrameLayout(context), sink); return null; });
            JSONObject opened = call(services, "image.surface.open", new JSONObject()
                    .put("id", id).put("path", directory.getName() + "/" + source.getName())
                    .put("widthPx", 32).put("heightPx", 32).put("blurEdgeMode", "clamp")
                    .put("textureSampling", "linear"));
            assertTrue(opened.getInt("texture") >= 0);
            assertEquals("linear", opened.getString("textureSampling"));
            assertSamplingWrites(sink, opened.getInt("texture"), true, 1);
            JSONObject ready = awaitReady(services, id);
            assertTrue(ready.isNull("error"));
            assertEquals("linear", ready.getString("textureSampling"));
            assertSamplingWrites(sink, ready.getInt("texture"), true, 2);
            PodMediaSurfaceServices.Session session = onMain(() -> session(services, id));
            assertTrue(session.linearSampling);
            Snapshot initial = onMain(() -> new Snapshot(session));
            assertNotNull(initial.image);
            assertFalse(initial.loading);
            int[] sharp = sink.pixels.clone();
            int writes = sink.writes.get();

            // A style-only update must keep the decoded bitmap even when re-opening
            // its original path would now fail. This also detects needless workers.
            assertTrue(source.renameTo(moved));
            assertFalse(source.exists());
            for (float radius : new float[] {2f, 6f, 12f, 3f, 0f}) {
                JSONObject state = call(services, "image.surface.update", new JSONObject()
                        .put("id", id).put("blurPx", radius).put("blurEdgeMode", "clamp")
                        .put("brightnessBias", 0));
                assertEquals("radius=" + radius, "ready", state.getString("state"));
                assertTrue("radius=" + radius + " error=" + state.opt("error"), state.isNull("error"));
                assertEquals(ready.getInt("texture"), state.getInt("texture"));
                assertEquals("linear", state.getString("textureSampling"));
                Snapshot current = onMain(() -> new Snapshot(session));
                assertSame("blur must reuse decoded bitmap", initial.image, current.image);
                assertEquals("blur must not start another decode", initial.revision, current.revision);
                assertFalse(current.loading);
                assertFalse(initial.image.isRecycled());
                assertTrue("updated style must render", sink.writes.get() > writes);
                writes = sink.writes.get();
                assertSamplingWrites(sink, state.getInt("texture"), true, writes);
                if (radius > 0) assertFalse("nonzero blur must affect pixels", Arrays.equals(sharp, sink.pixels));
                else assertArrayEquals("removing blur restores the decoded image", sharp, sink.pixels);
            }
            JSONObject finalState = call(services, "image.surface.state", new JSONObject().put("id", id));
            assertEquals("ready", finalState.getString("state"));
            assertTrue(finalState.isNull("error"));
            assertEquals("linear", finalState.getString("textureSampling"));
            assertSamplingWrites(sink, finalState.getInt("texture"), true, 7);
        } finally {
            try { call(services, "image.surface.close", new JSONObject().put("id", id)); }
            finally {
                services.close();
                onMain(() -> null); // Drain posted disposal before deleting this test's directory.
                if (source.exists()) assertTrue(source.delete());
                if (moved.exists()) assertTrue(moved.delete());
                assertTrue(directory.delete());
            }
        }
    }

    private static void rejectStyle(String field, Object value) throws Exception {
        try {
            new PodMediaSurfaceServices.Style(new JSONObject().put(field, value));
            fail("accepted " + field + "=" + value);
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid_" + field, expected.getMessage());
        }
    }

    private static JSONObject frameArgs(double left, double top, int width, int height, double scale, double opacity)
            throws Exception {
        return new JSONObject().put("leftPx", left).put("topPx", top).put("widthPx", width)
                .put("heightPx", height).put("scale", scale).put("opacity", opacity);
    }

    private static void assertFrameState(JSONObject expected, JSONObject actual) throws Exception {
        assertEquals(6, actual.length());
        for (String field : new String[] {"leftPx", "topPx", "scale", "opacity"}) {
            assertTrue(field + " must be numeric", actual.get(field) instanceof Number);
            assertEquals(field, (float) expected.getDouble(field), (float) actual.getDouble(field), 0f);
        }
        for (String field : new String[] {"widthPx", "heightPx"}) {
            assertTrue(field + " must be an integer", actual.get(field) instanceof Integer);
            assertEquals(field, expected.getInt(field), actual.getInt(field));
        }
    }

    private static int[] renderPixels(Bitmap source, PodMediaSurfaceServices.Style style, int width, int height)
            throws Exception {
        return onMain(() -> {
            try (PodMediaSurfaceServices.SurfaceRenderer renderer =
                         new PodMediaSurfaceServices.SurfaceRenderer(width, height, width, height)) {
                int[] pixels = new int[width * height];
                renderer.render(source, null, 0, 0, style, pixels);
                return pixels;
            }
        });
    }

    private static int[] hardwarePixels(int width, int height, Consumer<RecordingCanvas> draw) {
        ImageReader images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer renderer = new HardwareRenderer();
        RenderNode root = new RenderNode("PresentationFrameReference");
        try {
            renderer.setOpaque(false);
            renderer.setSurface(images.getSurface());
            root.setPosition(0, 0, width, height);
            RecordingCanvas canvas = root.beginRecording(width, height);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);
            draw.accept(canvas);
            root.endRecording();
            renderer.setContentRoot(root);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (Image image = images.acquireLatestImage()) {
                assertNotNull("reference renderer produced no image", image);
                try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                    assertNotNull("reference renderer produced no hardware buffer", buffer);
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                    assertNotNull(hardware);
                    try {
                        Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        assertNotNull(software);
                        try {
                            int[] pixels = new int[width * height];
                            software.getPixels(pixels, 0, width, 0, 0, width, height);
                            return pixels;
                        } finally { software.recycle(); }
                    } finally { hardware.recycle(); }
                }
            }
        } finally {
            renderer.destroy();
            root.discardDisplayList();
            images.close();
        }
    }

    private static void assertRegionNear(int[] expected, int[] actual, int stride,
            int left, int top, int right, int bottom, int tolerance) {
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            assertPixelNear("at " + x + "," + y, expected[y * stride + x], actual[y * stride + x], tolerance);
        }
    }

    private static void assertPixelNear(String label, int expected, int actual, int tolerance) {
        assertEquals(label + " alpha", Color.alpha(expected), Color.alpha(actual), tolerance);
        assertEquals(label + " red", Color.red(expected), Color.red(actual), tolerance);
        assertEquals(label + " green", Color.green(expected), Color.green(actual), tolerance);
        assertEquals(label + " blue", Color.blue(expected), Color.blue(actual), tolerance);
    }

    private static void assertTransparentOutside(int[] pixels, int width, int height,
            int left, int top, int right, int bottom) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (x < left || x >= right || y < top || y >= bottom) {
                assertEquals("outside frame at " + x + "," + y, 0, Color.alpha(pixels[y * width + x]));
            }
        }
    }

    private static int[] filtered(ColorMatrix matrix, int... colors) {
        Bitmap source = Bitmap.createBitmap(colors, colors.length, 1, Bitmap.Config.ARGB_8888);
        Bitmap output = Bitmap.createBitmap(colors.length, 1, Bitmap.Config.ARGB_8888);
        try {
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            new Canvas(output).drawBitmap(source, 0, 0, paint);
            int[] pixels = new int[colors.length];
            output.getPixels(pixels, 0, colors.length, 0, 0, colors.length, 1);
            return pixels;
        } finally { source.recycle(); output.recycle(); }
    }

    private static void assertColorNear(int expected, int actual) {
        assertEquals("alpha", Color.alpha(expected), Color.alpha(actual));
        assertEquals("red", Color.red(expected), Color.red(actual), 1);
        assertEquals("green", Color.green(expected), Color.green(actual), 1);
        assertEquals("blue", Color.blue(expected), Color.blue(actual), 1);
    }

    private static void writeCheckerboard(File destination) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                bitmap.setPixel(x, y, ((x / 8 + y / 8) & 1) == 0 ? Color.BLACK : Color.WHITE);
            }
            try (FileOutputStream out = new FileOutputStream(destination)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        } finally { bitmap.recycle(); }
    }

    private static PodMediaSurfaceServices.Session session(PodMediaSurfaceServices service, String id) throws Exception {
        Field field = PodMediaSurfaceServices.class.getDeclaredField("sessions");
        field.setAccessible(true);
        PodMediaSurfaceServices.Session value = (PodMediaSurfaceServices.Session) ((Map<?, ?>) field.get(service)).get(id);
        assertNotNull(value);
        return value;
    }

    private static final class Snapshot {
        final Bitmap image;
        final long revision;
        final boolean loading;
        Snapshot(PodMediaSurfaceServices.Session session) {
            image = session.image; revision = session.revision; loading = session.imageLoading;
        }
    }

    private static final class RecordingSink implements PodMediaSurfaceServices.TextureSink {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger nextTexture = new AtomicInteger(73);
        final Map<Integer, List<Boolean>> samplingWrites = new HashMap<>();
        final Map<Integer, int[]> texturePixels = new HashMap<>();
        volatile int[] pixels;
        @Override public synchronized int write(int handle, int[] argb, int width, int height, boolean linear) {
            int texture = handle < 0 ? nextTexture.getAndIncrement() : handle;
            samplingWrites.computeIfAbsent(texture, ignored -> new ArrayList<>()).add(linear);
            pixels = argb.clone(); texturePixels.put(texture, pixels); writes.incrementAndGet(); return texture;
        }
        @Override public void free(int handle) { }
        synchronized List<Boolean> samplingFor(int texture) {
            List<Boolean> values = samplingWrites.get(texture);
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        }
        synchronized int[] pixelsFor(int texture) {
            int[] values = texturePixels.get(texture);
            assertNotNull("missing pixels for texture " + texture, values);
            return values.clone();
        }
    }

    private static int assertSamplingWrites(RecordingSink sink, int texture, boolean linear, int minimum) {
        List<Boolean> recorded = sink.samplingFor(texture);
        assertTrue("texture " + texture + " upload count=" + recorded.size(), recorded.size() >= minimum);
        for (int index = 0; index < recorded.size(); index++) {
            assertEquals("texture " + texture + " upload " + index, linear, recorded.get(index).booleanValue());
        }
        return recorded.size();
    }

    private static JSONObject awaitReady(PodMediaSurfaceServices services, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            JSONObject state = call(services, "image.surface.state", new JSONObject().put("id", id));
            assertTrue("decode/render error: " + state.opt("error"), state.isNull("error"));
            if (state.getString("state").equals("ready")) return state;
            assertEquals("loading", state.getString("state"));
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("image surface did not become ready");
    }

    private static JSONObject call(PodMediaSurfaceServices services, String method, JSONObject args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        services.dispatch(method, args, new PodMediaSurfaceServices.Callback() {
            @Override public void complete(JSONObject value) { result.set(value); calls.incrementAndGet(); done.countDown(); }
            @Override public void fail(String code, String message) { error.set(code + ": " + message); calls.incrementAndGet(); done.countDown(); }
        });
        assertTrue(method + " timed out", done.await(8, TimeUnit.SECONDS));
        assertEquals(method + " callback count", 1, calls.get());
        assertNull(method + " failed", error.get());
        assertNotNull(method + " missing state", result.get());
        return result.get();
    }

    private static void assertCallFails(PodMediaSurfaceServices services, String method, JSONObject args,
            String message) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        services.dispatch(method, args, new PodMediaSurfaceServices.Callback() {
            @Override public void complete(JSONObject value) { calls.incrementAndGet(); done.countDown(); }
            @Override public void fail(String code, String detail) {
                error.set(code + ": " + detail); calls.incrementAndGet(); done.countDown();
            }
        });
        assertTrue(method + " timed out", done.await(8, TimeUnit.SECONDS));
        assertEquals(method + " callback count", 1, calls.get());
        assertEquals("invalid_argument: " + message, error.get());
    }

    private static <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() instanceof Error) throw (Error) error.get();
        return result.get();
    }
}
