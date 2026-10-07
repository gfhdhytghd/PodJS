package dev.podjs.runtime;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded, atomic QR/page snapshots; contains no cookies, URLs or page text. */
final class PodBrowserAuthStatus {
    // Leave room for JSON escaping and the event envelope under the 1 MiB transport limit.
    static final int MAX_IMAGE = 256 * 1024;
    final JSONObject qr;
    final boolean verifying;
    final JSONObject page;

    private PodBrowserAuthStatus(JSONObject qr, boolean verifying, JSONObject page) {
        this.qr = verifying ? null : qr;
        this.verifying = verifying;
        this.page = page;
    }

    static PodBrowserAuthStatus parse(String result) {
        JSONObject qr = null, page = null;
        boolean verifying = false;
        try {
            if (result == null || result.length() > 2 * MAX_IMAGE + 1024) return new PodBrowserAuthStatus(null, false, null);
            Object raw = new JSONTokener(result).nextValue();
            if (raw instanceof String) {
                String image = (String) raw;
                String mime = image.startsWith("data:image/png;base64,") ? "image/png"
                        : image.startsWith("data:image/jpeg;base64,") ? "image/jpeg" : null;
                if (mime != null) qr = qr(mime.equals("image/png") ? "login" : "secondVerify", mime,
                        image.substring(image.indexOf(',') + 1));
            } else if (raw instanceof JSONObject) {
                JSONObject snapshot = (JSONObject) raw;
                verifying = Boolean.TRUE.equals(snapshot.opt("verifying"));
                JSONObject image = snapshot.optJSONObject("qr");
                if (image != null) qr = qr(image.optString("kind"), image.optString("mime"), image.optString("imageBase64"));
                if (snapshot.opt("loading") instanceof Boolean) {
                    page = new JSONObject().put("type", "pageState").put("loading", snapshot.getBoolean("loading"));
                    if (snapshot.opt("loginPanelVisible") instanceof Boolean)
                        page.put("loginPanelVisible", snapshot.getBoolean("loginPanelVisible"));
                }
            }
        } catch (Exception ignored) { /* Malformed results clear an obsolete overlay. */ }
        return new PodBrowserAuthStatus(qr, verifying, page);
    }

    private static JSONObject qr(String kind, String mime, String base64) throws Exception {
        if (!("login".equals(kind) && "image/png".equals(mime)
                || "secondVerify".equals(kind) && "image/jpeg".equals(mime))) return null;
        if (base64.isEmpty() || base64.length() > MAX_IMAGE || base64.length() % 4 != 0) return null;
        // Iterate instead of regex recursion over potentially large image data.
        int padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
        for (int i = 0; i < base64.length() - padding; i++) {
            char c = base64.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/')) return null;
        }
        return new JSONObject().put("type", "qr").put("kind", kind).put("mime", mime).put("imageBase64", base64);
    }

    String dataImage() {
        return qr == null ? null : "data:" + qr.optString("mime") + ";base64," + qr.optString("imageBase64");
    }

    static JSONObject event(String type) {
        try { return new JSONObject().put("type", type); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** One stream per authorization; deduplicates state and closes before terminal settlement. */
    static final class Stream {
        private final Consumer<JSONObject> sink;
        private final Map<String, String> previous = new HashMap<>();
        private boolean ended;
        private boolean hasQr, verifying;
        private final JSONObject qrWait;
        /** Host UI refresh after any state change (main thread). */
        Runnable changed;
        Stream(Consumer<JSONObject> sink) { this(sink, null); }
        Stream(Consumer<JSONObject> sink, JSONObject qrWait) { this.sink = sink; this.qrWait = qrWait; }
        /** Data for the host-owned waiting UI; page loading never controls visibility. */
        JSONObject qrWaitStatus() {
            try { return new JSONObject().put("visible", qrWait != null && !hasQr && !verifying && !ended)
                    .put("message", qrWait == null ? "" : qrWait.optString("message", "")); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
        void emit(JSONObject event) {
            if (ended) return;
            String type = event.optString("type");
            if ("qr".equals(type) && !verifying) hasQr = true;
            else if ("qrCleared".equals(type)) hasQr = false;
            else if ("verifying".equals(type)) { verifying = event.optBoolean("active"); if (verifying) hasQr = false; }
            else if ("cancelled".equals(type)) { ended = true; hasQr = false; }
            String key = "qrCleared".equals(type) ? "qr" : type;
            String encoded = event.toString();
            if (!encoded.equals(previous.put(key, encoded))) sink.accept(event);
            if (changed != null) changed.run();
        }
        void accept(PodBrowserAuthStatus snapshot) {
            try {
                if (snapshot.page != null) emit(snapshot.page);
                emit(event("verifying").put("active", snapshot.verifying));
                emit(snapshot.qr == null ? event("qrCleared") : snapshot.qr);
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
        void end(boolean cancelled) {
            if (ended) return;
            if (cancelled) emit(event("cancelled"));
            ended = true;
            previous.clear();
            if (changed != null) changed.run();
        }
    }
}
