package dev.podjs.runtime;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** State-boundary tests; these do not claim Activity or process recreation. */
public class PodActivityStateTest {
    private static final int SAVED_BYTES = 64 * 1024;
    private static final int RETAINED_BYTES = 896 * 1024;

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static JSONObject objectOfBytes(int size, String unit) throws Exception {
        int overhead = bytes(new JSONObject().put("value", "").toString());
        int unitBytes = bytes(unit);
        StringBuilder text = new StringBuilder();
        for (int remaining = size - overhead; remaining >= unitBytes; remaining -= unitBytes) text.append(unit);
        for (int remaining = (size - overhead) % unitBytes; remaining > 0; remaining--) text.append('x');
        JSONObject result = new JSONObject().put("value", text.toString());
        assertEquals("Fixture must have the requested serialized UTF-8 size", size, bytes(result.toString()));
        return result;
    }

    private static void expectRejected(PodActivityState state, JSONObject saved, JSONObject retained, String channel) throws Exception {
        String oldSaved = state.saved(), oldRetained = state.retained();
        try { state.write(saved, retained); fail("Expected " + channel + " rejection"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(channel)); }
        assertEquals("Saved state changed after rejection", oldSaved, state.saved());
        assertEquals("Retained state changed after rejection", oldRetained, state.retained());
    }

    @Test public void independentExactLimitsFitOneServiceReplyWithoutDoubleEscaping() throws Exception {
        PodActivityState state = new PodActivityState();
        JSONObject saved = objectOfBytes(SAVED_BYTES, "中");
        JSONObject retained = objectOfBytes(RETAINED_BYTES, "\ud83d\ude00");
        state.write(saved, retained);
        assertEquals(SAVED_BYTES, bytes(state.saved()));
        assertEquals(RETAINED_BYTES, bytes(state.retained()));
        JSONObject reply = new JSONObject().put("t", "service.result").put("id", Integer.MAX_VALUE)
            .put("ok", true).put("value", state.read());
        String wire = reply.toString();
        assertTrue("Both channels and envelope must fit the Rust host event budget", bytes(wire) < 1024 * 1024);
        assertTrue("Envelope has no additional escaping layer", bytes(wire) < SAVED_BYTES + RETAINED_BYTES + 128);
        JSONObject recovered = new JSONObject(wire).getJSONObject("value");
        assertEquals(saved.getString("value"), recovered.getJSONObject("saved").getString("value"));
        assertEquals(retained.getString("value"), recovered.getJSONObject("retained").getString("value"));
    }

    @Test public void savedOneByteOverLimitCannotBorrowRetainedSpace() throws Exception {
        PodActivityState state = new PodActivityState();
        state.write(new JSONObject().put("keep", true), new JSONObject().put("page", 2));
        expectRejected(state, objectOfBytes(SAVED_BYTES + 1, "中"), new JSONObject(), "Saved");
        assertTrue(state.read().getJSONObject("saved").getBoolean("keep"));
        assertEquals(2, state.read().getJSONObject("retained").getInt("page"));
    }

    @Test public void retainedOneByteOverLimitCannotBorrowSavedSpace() throws Exception {
        PodActivityState state = new PodActivityState();
        state.write(new JSONObject().put("keep", true), new JSONObject().put("page", 2));
        expectRejected(state, new JSONObject(), objectOfBytes(RETAINED_BYTES + 1, "\ud83d\ude00"), "Retained");
        assertTrue(state.read().getJSONObject("saved").getBoolean("keep"));
        assertEquals(2, state.read().getJSONObject("retained").getInt("page"));
    }

    @Test public void escapedJsonBytesCountTowardsTheRetainedLimit() throws Exception {
        PodActivityState state = new PodActivityState();
        state.write(new JSONObject().put("keep", true), new JSONObject().put("page", 2));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 160000; i++) text.append('\u0000');
        JSONObject escaped = new JSONObject().put("value", text.toString());
        assertTrue(bytes(text.toString()) < RETAINED_BYTES);
        assertTrue(bytes(escaped.toString()) > RETAINED_BYTES);
        expectRejected(state, new JSONObject(), escaped, "Retained");
    }

    @Test public void writesAndReadsSnapshotLargeRetainedObjects() throws Exception {
        PodActivityState state = new PodActivityState();
        JSONObject saved = new JSONObject().put("keep", true);
        JSONObject retained = objectOfBytes(700 * 1024, "中");
        String original = retained.getString("value");
        state.write(saved, retained);
        saved.put("keep", false); retained.put("value", "changed input");
        JSONObject read = state.read();
        assertTrue(read.getJSONObject("saved").getBoolean("keep"));
        assertEquals(original, read.getJSONObject("retained").getString("value"));
        read.getJSONObject("saved").put("keep", false);
        read.getJSONObject("retained").put("value", "changed output");
        assertTrue(state.read().getJSONObject("saved").getBoolean("keep"));
        assertEquals(original, state.read().getJSONObject("retained").getString("value"));
        expectRejected(state, null, new JSONObject(), "objects");
        expectRejected(state, new JSONObject(), null, "objects");
    }

    @Test public void restoringBothIndependentLimitsAndOnlyBundleState() throws Exception {
        PodActivityState state = new PodActivityState();
        String saved = objectOfBytes(SAVED_BYTES, "中").toString();
        String retained = objectOfBytes(RETAINED_BYTES, "\ud83d\ude00").toString();
        state.restore(saved, retained);
        assertEquals(saved, state.read().getJSONObject("saved").toString());
        assertEquals(retained, state.read().getJSONObject("retained").toString());
        state.restore(saved, null);
        assertEquals(saved, state.saved());
        assertEquals(0, state.read().getJSONObject("retained").length());
        state.restore(null, null);
        assertEquals(0, state.read().getJSONObject("saved").length());
        assertEquals(0, state.read().getJSONObject("retained").length());
    }

    @Test public void oversizeRestorationRemainsAnExplicitErrorUntilValidReplacement() throws Exception {
        PodActivityState state = new PodActivityState();
        for (boolean oversizedSaved : new boolean[] { true, false }) {
            String saved = oversizedSaved ? objectOfBytes(SAVED_BYTES + 1, "中").toString() : "{}";
            String retained = oversizedSaved ? "{}" : objectOfBytes(RETAINED_BYTES + 1, "\ud83d\ude00").toString();
            state.restore(saved, retained);
            try { state.read(); fail("Expected corrupt restoration to remain visible"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains(oversizedSaved ? "Saved" : "Retained")); }
            assertEquals(saved, state.saved()); assertEquals(retained, state.retained());
            state.write(new JSONObject().put("replaced", true), new JSONObject());
            assertTrue(state.read().getJSONObject("saved").getBoolean("replaced"));
        }
        state.restore("[1]", null);
        try { state.read(); fail("Expected malformed restoration error"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("JSON objects")); }
        assertEquals("[1]", state.saved());
    }
}
