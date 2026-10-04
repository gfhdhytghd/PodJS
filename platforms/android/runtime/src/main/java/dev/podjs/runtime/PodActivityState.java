package dev.podjs.runtime;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Transient Activity state only. Neither channel writes application KV/files.
 * Saved state enters the Android Bundle; retained state crosses recreation
 * through onRetainNonConfigurationInstance and never enters that Bundle. */
final class PodActivityState {
    private static final int MAX_SAVED_BYTES = 64 * 1024;
    // Both channels return in one service.result event. Its envelope must fit
    // the runtime's 1 MiB host-event limit; retained never enters the Bundle.
    private static final int MAX_RETAINED_BYTES = 896 * 1024;
    private String saved = "{}", retained = "{}";
    private IllegalArgumentException restorationError;

    synchronized void restore(String saved, String retained) {
        this.saved = saved == null ? "{}" : saved;
        this.retained = retained == null ? "{}" : retained;
        try { validate(this.saved, this.retained); restorationError = null; }
        catch (IllegalArgumentException error) { restorationError = error; }
    }
    synchronized JSONObject read() throws Exception {
        if (restorationError != null) throw restorationError;
        return new JSONObject().put("saved", new JSONObject(saved)).put("retained", new JSONObject(retained));
    }
    synchronized void write(JSONObject saved, JSONObject retained) {
        if (saved == null || retained == null) throw new IllegalArgumentException("Activity state channels must be objects");
        String nextSaved = saved.toString(), nextRetained = retained.toString();
        validate(nextSaved, nextRetained);
        this.saved = nextSaved; this.retained = nextRetained; restorationError = null;
    }
    synchronized String saved() { return saved; }
    synchronized String retained() { return retained; }
    private static void validate(String saved, String retained) {
        if (saved.getBytes(StandardCharsets.UTF_8).length > MAX_SAVED_BYTES)
            throw new IllegalArgumentException("Saved Activity state exceeds 64 KiB");
        if (retained.getBytes(StandardCharsets.UTF_8).length > MAX_RETAINED_BYTES)
            throw new IllegalArgumentException("Retained Activity state exceeds 896 KiB");
        try { new JSONObject(saved); new JSONObject(retained); }
        catch (Exception error) { throw new IllegalArgumentException("Activity state is not valid JSON objects", error); }
    }
}
