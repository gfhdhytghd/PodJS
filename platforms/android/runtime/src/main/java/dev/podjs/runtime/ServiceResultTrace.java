package dev.podjs.runtime;

import java.util.LinkedHashMap;

/** Per-host, default-off service completion accounting. Logs only allowlisted
 * methods, developer code labels/query fingerprints and numeric metadata.
 * Never logs arguments, SQL, values, error messages or user IDs. Completion wall
 * time includes dependent QuickJS jobs and host effects; nested guest spans must
 * not be added to it. */
final class ServiceResultTrace {
    static final int MAX_PENDING = 64, MAX_LOGS = 4096;
    static final long SLOW_NS = 16_000_000;
    static final boolean PERF_TRACE=false;
    final boolean enabled;
    private final LinkedHashMap<Integer,String> pending = new LinkedHashMap<>();
    private long completed, slow, dropped;
    private int logs;
    ServiceResultTrace(boolean debugBuild, boolean optedIn) { enabled = (debugBuild || PERF_TRACE) && optedIn; }
    static String category(String method) {
        // Return literals, never substrings from guest-supplied method names.
        switch (method) {
            case "file.read": return "file.read";
            case "http.download": return "http.download";
            case "runtime.delay": return "runtime.delay";
            case "secure.get": return "secure.get";
            case "sql.query": return "sql.query";
            case "sql.gzipJson": return "sql.gzipJson";
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
    void cancel(int id) { if(enabled) {pending.remove(id);exact.remove(id);} }
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
    static final class Completion {
        final int id, bytes, rows;
        final String method, label;
        final long requestedNs;
        Completion(int id,String method,String label,int bytes,int rows,long requestedNs) {
            this.id=id;this.method=method;this.label=label;this.bytes=bytes;this.rows=rows;this.requestedNs=requestedNs;
        }
    }
    private final LinkedHashMap<Integer,String[]> exact = new LinkedHashMap<>();
    private static final java.util.Set<String> METHODS = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
        "acoustic.gunzip", "acoustic.gzip", "acoustic.pcm.drain", "acoustic.pcm.open", "acoustic.pcm.read",
        "acoustic.pcm.stop", "acoustic.pcm.write", "acoustic.requestPermission", "acoustic.wifi.close", "acoustic.wifi.connect",
        "acoustic.wifi.request", "app.activityState.read", "app.activityState.write", "app.buildInfo", "app.finishTask",
        "audio.pause", "audio.resume", "audio.setSpeed", "audio.setVolumeIndex", "audio.silenceEnabled",
        "audio.silenceLease", "audio.silenceState", "audio.start", "audio.state", "audio.stop",
        "audio.volumeState", "background.cancel", "background.register", "background.status", "browser.authorize",
        "browser.authorizeWithStatus", "browser.availability", "browser.clearSiteData", "browser.open", "browser.openInApp",
        "cloud.enqueue", "cloud.localTransfer", "cloud.workState", "cloud.acquire", "cloud.cancel", "cloud.release", "cloud.schedule", "crypto.aesGcmDecrypt",
        "crypto.aesGcmEncrypt", "crypto.decrypt", "crypto.ecdhPublicKey", "crypto.ecdhUnwrap", "crypto.encrypt",
        "crypto.gunzip", "crypto.gzip", "crypto.hash", "crypto.hkdfSha256", "crypto.hmacSha256",
        "crypto.gunzipSealedParts","crypto.closeSealedGzip","crypto.openSealedParts","crypto.largeAesGcmDecrypt", "crypto.largeAesGcmEncrypt", "crypto.random", "crypto.rsaOaepSha256", "debug.memoryStats",
        "debug.performance.read", "debug.performance.start", "debug.performance.stop", "discovery.bleReadCharacteristic", "discovery.mdns",
        "discovery.networkSnapshot", "discovery.networkSubscribe", "discovery.networkUnsubscribe", "discovery.requestPermission", "discovery.status",
        "display.keepAwake", "display.state", "file.closeText", "file.commit", "file.compose", "file.createTemp",
        "file.delete", "file.gunzip", "file.gzip", "file.gzipBytes", "file.hash",
        "file.htmlText", "file.list", "file.openText", "file.read", "file.readTextChunk",
        "file.stat", "file.touch", "file.write", "http.download", "image.decode",
        "image.decodeRegion", "image.release", "image.surface.close", "image.surface.open", "image.surface.state",
        "image.surface.update", "input.inline.close", "input.inline.open", "input.inline.set", "input.text",
        "input.text.dismiss", "input.text.pending", "input.time", "location.current", "notifications.cancel",
        "notifications.listPending", "notifications.registerRemote", "notifications.requestPermission", "notifications.schedule", "notifications.status",
        "notifications.unregisterRemote", "rfcomm.accept", "rfcomm.close", "rfcomm.listen", "rfcomm.requestPermission",
        "rfcomm.status", "runtime.delay", "secure.delete", "secure.get", "secure.set",
        "share.environment", "share.text", "sql.execute", "sql.query", "sql.transaction", "sql.gzipJson",
        "stream.close", "stream.read", "stream.write", "sync.files.accept", "sync.files.cancel",
        "sync.files.offer", "sync.files.save", "sync.files.status", "sync.messages.ack", "sync.messages.send",
        "sync.state.delete", "sync.state.get", "sync.state.set", "sync.state.synchronize", "takeover.status",
        "tcp.accept", "tcp.addresses", "tcp.close", "tcp.listen", "text.environment",
        "text.layout", "text.layoutBatch", "text.measure", "text.raster", "tts.init",
        "tts.speak", "tts.spectrum", "tts.state", "tts.stop", "ui.toast",
        "uri.parse", "video.captureBackdrop", "video.pause", "video.play", "video.prepare",
        "video.seek", "video.session.capabilities", "video.session.pause", "video.session.play", "video.session.prepare",
        "video.session.promote", "video.session.release", "video.session.seek", "video.session.setBounds", "video.session.setTransform",
        "video.frame.clear", "video.frame.capture", "video.frame.restore", "video.session.captureFrame", "video.session.setLoop", "video.session.setSpeed", "video.session.setVolume", "video.session.state", "video.session.stop", "video.session.transition", "video.setBounds",
        "video.setTransform", "video.setLoop", "video.setSpeed", "video.setVolume", "video.state", "video.stop", "video.surface.close",
        "video.surface.open", "video.surface.state", "video.surface.update", "websocket.close", "websocket.connect",
        "websocket.readText", "websocket.writeText"
    )));
    static String exactMethod(String method) { return METHODS.contains(method)?method:category(method); }
    void request(int id,String method,String label,String query) {
        request(id,method);
        if(!enabled)return;
        if(!exact.containsKey(id)&&exact.size()==MAX_PENDING)exact.remove(exact.keySet().iterator().next());
        // Caller-authored code label, never SQL, arguments, results or account IDs.
        String safe=label!=null&&label.matches("[a-zA-Z][a-zA-Z0-9_.:-]{0,79}")?label:"unlabelled";
        if("unlabelled".equals(safe)&&method.startsWith("sql.")&&query!=null) {
            java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            safe="sql-"+Long.toHexString(crc.getValue());
        }
        exact.put(id,new String[]{exactMethod(method),safe,Long.toString(System.nanoTime())});
    }
    Completion completion(int id,boolean terminal,int bytes,int rows) {
        if(!enabled)return null;
        if(terminal)pending.remove(id);
        String[] meta=terminal?exact.remove(id):exact.get(id);
        return new Completion(id,meta==null?"other":meta[0],meta==null?"unlabelled":meta[1],bytes,rows,meta==null?0:Long.parseLong(meta[2]));
    }
    String finish(Completion result,long elapsedNs) {
        if(!enabled||result==null)return null;
        completed++;if(elapsedNs>SLOW_NS)slow++;
        if(logs>=MAX_LOGS)return null;
        logs++;
        return "completion id="+result.id+" method="+result.method+" label="+result.label+" bytes="+result.bytes+" rows="+result.rows+" ns="+elapsedNs+" roundTripNs="+(result.requestedNs==0?-1:System.nanoTime()-result.requestedNs)+" completed="+completed+" slow="+slow+" logs="+logs+(logs==MAX_LOGS?" capped=true":"");
    }
    int pendingCount() { return pending.size(); }
    long completedCount() { return completed; }
    long slowCount() { return slow; }
    void clear() { pending.clear(); exact.clear(); completed=slow=dropped=0; logs=0; }
}
