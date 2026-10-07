package dev.podjs.runtime;

import java.util.LinkedHashMap;

/** Per-host, opt-in service completion-turn accounting. Contains only a fixed
 * method category and counters, never arguments, values, errors or user IDs.
 * The measured turn includes the result callback's dependent QuickJS jobs. */
final class ServiceResultTrace {
    static final int MAX_PENDING = 64, MAX_LOGS = 128;
    static final long SLOW_NS = 16_000_000;
    final boolean enabled;
    private final LinkedHashMap<Integer,String> pending = new LinkedHashMap<>();
    private long completed, slow, dropped;
    private int logs;
    ServiceResultTrace(boolean debugBuild, boolean optedIn) { enabled = debugBuild && optedIn; }
    static String category(String method) {
        // Return literals, never substrings from guest-supplied method names.
        switch (method) {
            case "file.read": return "file.read";
            case "http.download": return "http.download";
            case "runtime.delay": return "runtime.delay";
            case "secure.get": return "secure.get";
            case "sql.query": return "sql.query";
            case "browser.authorize": case "browser.authorizeWithStatus": return "browser.auth";
        }
        if (method.startsWith("file.")) return "file";
        if (method.startsWith("secure.")) return "secure";
        if (method.startsWith("sql.")) return "sql";
        if (method.startsWith("text.")) return "text";
        if (method.startsWith("image.")) return "image";
        if (method.startsWith("video.")) return "video";
        if (method.startsWith("browser.")) return "browser";
        return "other";
    }
    void request(int id, String method) {
        if (!enabled) return;
        if (!pending.containsKey(id) && pending.size() == MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next()); dropped++;
        }
        pending.put(id, category(method));
    }
    void cancel(int id) { if(enabled) pending.remove(id); }
    String result(int id, boolean terminal) {
        if (!enabled) return null;
        String category = terminal ? pending.remove(id) : pending.get(id);
        return category == null ? "other" : category;
    }
    private static String safeCategory(String value) {
        switch(value) {
            case "file.read": case "http.download": case "runtime.delay": case "secure.get": case "sql.query":
            case "browser.auth": case "file": case "secure": case "sql": case "text": case "image": case "video": case "browser": return value;
            default: return "other";
        }
    }
    String finish(String category, long elapsedNs) {
        if (!enabled || category == null) return null;
        completed++;
        if (elapsedNs <= SLOW_NS) return null;
        slow++;
        if (logs >= MAX_LOGS) return null;
        logs++;
        // Reclassify the literal too, so callers cannot accidentally log data.
        return "result category=" + safeCategory(category)
            + " ns=" + elapsedNs + " completed=" + completed + " slow=" + slow + " dropped=" + dropped
            + " logs=" + logs + (logs == MAX_LOGS ? " capped=true" : "");
    }
    int pendingCount() { return pending.size(); }
    long completedCount() { return completed; }
    long slowCount() { return slow; }
    void clear() { pending.clear(); completed=slow=dropped=0; logs=0; }
}
