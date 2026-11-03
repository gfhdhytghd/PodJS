package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One-shot headless worker. No Activity, View, Surface or renderer is created.
 * The host must verify the installed background bundle before constructing this
 * object. close cancels immediately and defers native destruction until run exits.
 */
public final class PodBackgroundRun implements AutoCloseable {
    static { System.loadLibrary("podjs_android"); }
    private long handle;
    private boolean running, started, closed;
    private int waiters;
    public PodBackgroundRun(String appId, String taskId, String verifiedSource, long budgetMs, int memoryBytes) throws org.json.JSONException {
        this(appId,taskId,verifiedSource,budgetMs,memoryBytes,JSONObject.NULL);
    }
    public PodBackgroundRun(String appId, String taskId, String verifiedSource, long budgetMs, int memoryBytes, Object payload) throws org.json.JSONException {
        this(appId,taskId,verifiedSource,budgetMs,memoryBytes,payload,new String[0]);
    }
    /** Host-approved method names only; never forward a guest allowlist. */
    public PodBackgroundRun(String appId, String taskId, String verifiedSource, long budgetMs, int memoryBytes, Object payload, String[] allowedMethods) throws org.json.JSONException {
        this(appId,taskId,verifiedSource,budgetMs,memoryBytes,payload,allowedMethods,null);
    }
    /** Optional private KV root belongs to the host, never to task arguments. */
    public PodBackgroundRun(String appId, String taskId, String verifiedSource, long budgetMs, int memoryBytes, Object payload, String[] allowedMethods, String privateKvRoot) throws org.json.JSONException {
        if (verifiedSource == null || verifiedSource.length() > 1024*1024) throw new IllegalArgumentException("Invalid background source");
        JSONObject config = new JSONObject().put("app_id",appId).put("task_id",taskId).put("source",verifiedSource)
            .put("budget_ms",budgetMs).put("memory_bytes",memoryBytes).put("payload",payload == null ? JSONObject.NULL : payload)
            .put("allowed_methods",new org.json.JSONArray(java.util.Arrays.asList(allowedMethods.clone())));
        if (privateKvRoot!=null) config.put("kv_root",privateKvRoot);
        byte[] bytes = config.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2*1024*1024) throw new IllegalArgumentException("Background config too large");
        handle = nativeOpen(bytes);
        if (handle == 0) throw new IllegalStateException("Cannot create background task");
    }
    public JSONObject run() throws org.json.JSONException {
        final long current;
        synchronized (this) {
            if (closed || started) throw new IllegalStateException("Background task is one-shot");
            started = true; running = true; current = handle;
        }
        try { return new JSONObject(nativeExecute(current)); }
        finally {
            synchronized (this) { running = false; if (closed && waiters==0) destroy(); }
        }
    }
    public synchronized void cancel() { if (handle != 0) nativeCancel(handle); }
    public synchronized boolean isRunning() { return running; }
    /** Nonblocking; call from a host IO consumer, not the executing thread. */
    public synchronized JSONObject pollRequest() throws org.json.JSONException {
        if (closed || handle==0) return null;
        if(waiters!=0)throw new IllegalStateException("background_single_consumer");
        byte[] bytes=nativePoll(handle);
        return bytes==null ? null : new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    /** One host consumer. Never hold the monitor while waiting: reply/cancel
     * must wake this wait, and close defers destruction until it has returned. */
    public JSONObject waitRequest(int timeoutMillis) throws org.json.JSONException {
        if(timeoutMillis<0||timeoutMillis>100)throw new IllegalArgumentException("invalid_wait_timeout");
        final long current;
        synchronized(this){if(closed||handle==0)return null;if(waiters!=0)throw new IllegalStateException("background_single_consumer");current=handle;waiters++;}
        try{byte[] bytes=nativeWait(current,timeoutMillis);return bytes==null?null:new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
        finally{synchronized(this){waiters--;if(closed&&!running&&waiters==0)destroy();}}
    }
    /** Returns false for late/duplicate replies or after close. */
    public synchronized boolean reply(JSONObject response) {
        if (closed || handle==0) return false;
        byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length>1024*1024) throw new IllegalArgumentException("Background reply too large");
        int result=nativeReply(handle,bytes);
        if (result==-1) throw new IllegalArgumentException("Invalid background reply");
        return result==0;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (handle != 0) nativeCancel(handle);
        if (!running && waiters==0) destroy();
    }
    private void destroy() { if (handle != 0) { nativeClose(handle); handle = 0; } }
    private static native long nativeOpen(byte[] config);
    private static native String nativeExecute(long handle);
    private static native void nativeCancel(long handle);
    private static native byte[] nativePoll(long handle);
    private static native byte[] nativeWait(long handle,int timeoutMillis);
    private static native int nativeReply(long handle, byte[] response);
    private static native void nativeClose(long handle);
}
