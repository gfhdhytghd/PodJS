package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Uses isolated resources only; never changes the device's density or font settings. */
public class PodTextEnvironmentServicesTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Context configured(Context base, int densityDpi, float fontScale) {
        Configuration override = new Configuration();
        override.densityDpi = densityDpi;
        override.fontScale = fontScale;
        Context configured = base.createConfigurationContext(override);
        assertEquals(densityDpi, configured.getResources().getConfiguration().densityDpi);
        assertEquals(fontScale, configured.getResources().getConfiguration().fontScale, 0.00001f);
        return configured;
    }

    private JSONObject request(JSONArray values) throws Exception {
        return new JSONObject().put("spValues", values);
    }

    private void assertEnvironment(Context context, JSONArray values, JSONObject result) throws Exception {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        assertEquals(metrics.density, result.getDouble("density"), 0.00001);
        assertEquals(resources.getConfiguration().fontScale, result.getDouble("fontScale"), 0.00001);
        JSONArray pixels = result.getJSONArray("spValuesPx");
        assertEquals(values.length(), pixels.length());
        for (int i = 0; i < values.length(); i++) {
            float expected = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, (float) values.getDouble(i), metrics);
            assertEquals("SP conversion at index " + i, expected, pixels.getDouble(i), 0.00001);
        }
    }

    private void expectRejected(Context context, Object values) throws Exception {
        JSONObject args = new JSONObject().put("spValues", values);
        try {
            PodTextEnvironmentServices.read(context, args);
            fail("Expected invalid spValues to be rejected: " + values);
        } catch (IllegalArgumentException expected) {
            // Reject the input itself rather than coercing JSON strings, booleans or nulls.
        }
    }

    @Test public void absentAndEmptyValuesReturnCurrentEnvironmentWithoutChangingRequest() throws Exception {
        Context configured = configured(context(), 240, 1.3f);
        JSONObject absent = new JSONObject();
        assertEnvironment(configured, new JSONArray(), PodTextEnvironmentServices.read(configured, absent));
        assertFalse("The optional array must not be inserted into the caller's object", absent.has("spValues"));
        JSONArray empty = new JSONArray();
        JSONObject args = request(empty);
        assertEnvironment(configured, empty, PodTextEnvironmentServices.read(configured, args));
        assertSame(empty, args.getJSONArray("spValues"));
        assertEquals(0, empty.length());
    }

    @Test public void isolatedDensityAndFontScaleUseAndroidSpConversionAndPreserveConfiguration() throws Exception {
        Context base = context();
        Configuration baseConfiguration = new Configuration(base.getResources().getConfiguration());
        DisplayMetrics baseMetrics = new DisplayMetrics();
        baseMetrics.setTo(base.getResources().getDisplayMetrics());
        JSONArray values = new JSONArray().put(0).put(1).put(9).put(12).put(16.333333333)
                .put(40).put(100).put(8192).put(12).put(0);
        JSONObject args = request(values);
        String originalRequest = args.toString();
        for (int densityDpi : new int[] { 160, 320 }) {
            for (float fontScale : new float[] { 0.85f, 1.3f, 2.0f }) {
                Context configured = configured(base, densityDpi, fontScale);
                Configuration before = new Configuration(configured.getResources().getConfiguration());
                DisplayMetrics metricsBefore = new DisplayMetrics();
                metricsBefore.setTo(configured.getResources().getDisplayMetrics());
                assertEnvironment(configured, values, PodTextEnvironmentServices.read(configured, args));
                assertEquals(before, configured.getResources().getConfiguration());
                assertEquals(metricsBefore, configured.getResources().getDisplayMetrics());
                assertEquals(originalRequest, args.toString());
            }
        }
        assertEquals(baseConfiguration, base.getResources().getConfiguration());
        assertEquals(baseMetrics, base.getResources().getDisplayMetrics());
    }

    @Test public void sameContextRereadsItsCurrentResourcesAndDoesNotReuseReturnedArrays() throws Exception {
        Context base = context();
        Context first = configured(base, 160, 1.0f);
        Context second = configured(base, 320, 2.0f);
        ObservedContext observed = new ObservedContext(base, first.getResources(), Thread.currentThread());
        JSONArray values = new JSONArray().put(12).put(40).put(12);
        JSONObject initial = PodTextEnvironmentServices.read(observed, request(values));
        assertEnvironment(first, values, initial);
        int firstReads = observed.resourceReads;
        assertTrue("Each request must read the passed Context", firstReads > 0);

        observed.resources = second.getResources();
        JSONObject changed = PodTextEnvironmentServices.read(observed, request(values));
        assertTrue(observed.resourceReads > firstReads);
        assertEnvironment(second, values, changed);
        assertTrue(initial.getDouble("density") != changed.getDouble("density"));
        assertTrue(initial.getDouble("fontScale") != changed.getDouble("fontScale"));
        assertTrue(initial.getJSONArray("spValuesPx").getDouble(0)
                != changed.getJSONArray("spValuesPx").getDouble(0));

        changed.getJSONArray("spValuesPx").put(0, -123);
        observed.resources = first.getResources();
        int secondReads = observed.resourceReads;
        assertEnvironment(first, values, PodTextEnvironmentServices.read(observed, request(values)));
        assertTrue(observed.resourceReads > secondReads);
        assertEnvironment(first, values, initial);
    }

    @Test public void acceptsExactValueAndLengthLimitsAndRejectsOneElementTooMany() throws Exception {
        Context configured = configured(context(), 240, 1.5f);
        JSONArray values = new JSONArray();
        for (int i = 0; i < 128; i++) values.put(i % 3 == 0 ? 0 : i % 3 == 1 ? 8192 : 12.5);
        assertEnvironment(configured, values, PodTextEnvironmentServices.read(configured, request(values)));
        values.put(0);
        expectRejected(configured, values);
        assertEquals("Validation must not remove the excess element", 129, values.length());
    }

    @Test public void rejectsNonArraysMixedTypesOutOfRangeAndNonFiniteNumbers() throws Exception {
        Context configured = configured(context(), 160, 1.0f);
        for (Object invalid : new Object[] { JSONObject.NULL, "12", 12, true, new JSONObject() }) {
            expectRejected(configured, invalid);
        }
        for (Object invalid : new Object[] {
                JSONObject.NULL, "12", true, new JSONArray(), new JSONObject(),
                -0.001, -Double.MIN_VALUE, 8192.001, Math.nextUp(8192.0),
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            // Object overload deliberately permits non-finite fixtures into Android's JSONArray.
            JSONArray mixed = new JSONArray().put(12).put(invalid).put(40);
            expectRejected(configured, mixed);
            assertEquals(3, mixed.length());
            assertEquals(12, mixed.getInt(0));
            assertEquals(40, mixed.getInt(2));
        }
        for (String number : new String[] { "1e309", "-1e309" }) {
            JSONObject parsed = new JSONObject("{\"spValues\":[" + number + "]}");
            JSONArray overflow = parsed.getJSONArray("spValues");
            assertTrue("Valid JSON exponent fixture must overflow to infinity", Double.isInfinite(overflow.getDouble(0)));
            expectRejected(configured, overflow);
        }
    }

    @Test public void readsResourcesSynchronouslyOnTheCallingMainThread() throws Exception {
        Context configured = configured(context(), 320, 1.8f);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                assertSame(Looper.getMainLooper(), Looper.myLooper());
                ObservedContext observed = new ObservedContext(
                        configured, configured.getResources(), Thread.currentThread());
                JSONArray values = new JSONArray().put(14).put(28);
                JSONObject result = PodTextEnvironmentServices.read(observed, request(values));
                assertTrue(observed.resourceReads > 0);
                assertEnvironment(configured, values, result);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError("Main-thread read failed", failure.get());
    }

    private static final class ObservedContext extends ContextWrapper {
        private Resources resources;
        private final Thread caller;
        private int resourceReads;

        ObservedContext(Context base, Resources resources, Thread caller) {
            super(base);
            this.resources = resources;
            this.caller = caller;
        }

        @Override public Resources getResources() {
            assertSame("Resource access moved away from the caller thread", caller, Thread.currentThread());
            resourceReads++;
            return resources;
        }
    }
}
