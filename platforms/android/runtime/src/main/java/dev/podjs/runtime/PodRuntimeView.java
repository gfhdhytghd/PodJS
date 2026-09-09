package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.Window;
import android.view.Choreographer;
import android.util.Base64;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Native full-screen PodJS surface. Production assets are read only from the APK. */
public final class PodRuntimeView extends TextureView implements TextureView.SurfaceTextureListener {
    static { System.loadLibrary("podjs_android"); }
    private long host;
    private final PodAccessibility accessibility;
    private boolean accessibilityEnabled;
    private final long[] accessibilityMetadata = new long[3];
    private final long viewBegan = android.os.SystemClock.elapsedRealtime();
    private boolean firstTextureLogged;
    private long surfaceBegan;
    private boolean firstFrameLogged;
    private final PodServices services;
    private final String targetId;
    private float rotaryRemainder;
    private boolean active = true;
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final Map<Integer, HttpURLConnection> requests = new ConcurrentHashMap<>();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTime) {
            if (host != 0 && active) {
                boolean enabled = accessibility.enabled();
                if (enabled != accessibilityEnabled) {
                    nativeAccessibilityEnabled(host, enabled); accessibilityEnabled = enabled;
                    if (!enabled) accessibility.clear();
                }
                nativeFrame(host);
                if (enabled) {
                    byte[] semantics = nativeAccessibilitySnapshot(host, accessibilityMetadata);
                    if (semantics != null) accessibility.update(semantics, accessibilityMetadata);
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    android.util.Log.i("PodJSPerf", "surfaceToFirstFrameMs=" + (android.os.SystemClock.elapsedRealtime() - surfaceBegan));
                }
                String effect;
                while ((effect = nativePollEffect(host)) != null) {
                    try {
                        JSONObject command = new JSONObject(effect);
                        if ("haptic".equals(command.optString("t"))) performHapticFeedback(6);
                        else if ("navigation".equals(command.optString("t"))) {
                            nativeNavigationState(host, command.optBoolean("canGoBack", false));
                        }
                        else if (command.optString("t").startsWith("service.")) services.dispatch(command);
                        else if ("notification.ack".equals(command.optString("t"))) services.acknowledgeNotification(command.getString("eventId"));
                    } catch (Exception error) { android.util.Log.e("PodJS", "Invalid host effect", error); }
                }
                String command;
                while ((command = nativePollNet(host)) != null) handleNetwork(command);
                services.pumpNotifications();
            }
            if (active) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PodRuntimeView(Context context, String targetId) {
        super(context);
        this.targetId = targetId;
        accessibility = new PodAccessibility(this, (id, hash, action) -> host != 0 && nativeAccessibilityAction(host, id, hash, action));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        services = new PodServices(context, event -> post(() -> {
            if (host != 0) nativePostEvent(host, event.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }));
        setSurfaceTextureListener(this);
        setOpaque(false);
        setFocusable(true);
    }
    public void captureNotificationIntent(android.content.Intent intent) {services.captureNotificationIntent(intent);}
    @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() { return accessibility; }
    @Override public boolean dispatchHoverEvent(MotionEvent event) { return accessibility.hover(event) || super.dispatchHoverEvent(event); }
    public void notificationPermissionResult(int requestCode,int[] results){services.notificationPermissionResult(requestCode,results);}

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getParent() instanceof ViewGroup) services.attachVideo((ViewGroup) getParent());
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) services.pauseVideoForLifecycle();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        Surface surface = new Surface(texture);
        try {
            if (host != 0) {
                nativeResize(host, surface, width, height);
                nativeTheme(host, currentTheme());
                return;
            }
            surfaceBegan = android.os.SystemClock.elapsedRealtime();
            firstFrameLogged = false;
            File data = new File(getContext().getFilesDir(), "podjs");
            if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("PodJS data directory");
            host = nativeCreate(surface, targetId, width, height,
                getResources().getDisplayMetrics().density, data.getAbsolutePath());
            accessibilityEnabled = false;
            nativeBootAssets(host, getContext().getAssets());
            services.approveInstalledBackground(targetId);
            services.approveInstalledSync(targetId);
            if(companionSettingsRequested) {companionSettingsRequested=false;showCompanionSettings();}
            nativeTheme(host, currentTheme());
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            if (active) Choreographer.getInstance().postFrameCallback(frameCallback);
        } catch (RuntimeException error) {
            if (host != 0) { nativeDestroy(host); host = 0; }
            throw new IllegalStateException("PodJS boot failed", error);
        } finally { surface.release(); }
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (host != 0) nativeDetachSurface(host);
        return true;
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
        Surface surface = new Surface(t);
        try { if (host != 0) nativeResize(host, surface, w, h); } finally { surface.release(); }
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (!firstTextureLogged) {
            firstTextureLogged = true;
            android.util.Log.i("PodJSPerf", "viewToFirstTextureMs=" + (android.os.SystemClock.elapsedRealtime() - viewBegan));
        }
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        accessibility.clear();
        if (host != 0) { nativeDestroy(host); host = 0; }
        for (HttpURLConnection connection : requests.values()) connection.disconnect();
        requests.clear();
        network.shutdownNow();
        services.close();
        super.onDetachedFromWindow();
    }

    private String currentTheme() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (host != 0) nativeTheme(host, currentTheme());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (host == 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            nativePostEvent(host, "{\"t\":\"touchCancel\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int count = Math.min(event.getPointerCount(), 8);
        int[] ids = new int[count]; float[] xy = new float[count * 2];
        if (event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < count; i++) {
                ids[i] = event.getPointerId(i);
                xy[i * 2] = event.getX(i);
                xy[i * 2 + 1] = event.getY(i);
            }
        } else count = 0;
        nativeInput(host, ids, xy, count, 0);
        return true;
    }

    private static final float DEGREES_PER_SCROLL_UNIT = 15f;
    private static final float ANDROID_MOUSE_WHEEL_SCALE = 1f / 48f;

    /** OPPO/OPlus watches provide the native interactive rightward dismiss. */
    public static boolean requestPlatformSwipeDismiss(android.app.Activity activity) {
        if (!isOppoWatch(activity)) return false;
        try {
            return activity.requestWindowFeature(Window.FEATURE_SWIPE_TO_DISMISS);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isOppoWatch(Context context) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) return false;
        return isOppoMarker(android.os.Build.MANUFACTURER)
            || isOppoMarker(android.os.Build.BRAND)
            || isOppoMarker(android.os.Build.PRODUCT)
            || isOppoMarker(android.os.Build.DEVICE)
            || isOppoMarker(android.os.Build.MODEL)
            || isOppoMarker(android.os.Build.FINGERPRINT);
    }

    static boolean isOppoMarker(String value) {
        return value != null && (value.toLowerCase(java.util.Locale.ROOT).contains("oppo")
            || value.toLowerCase(java.util.Locale.ROOT).contains("oplus"));
    }

    /** Translate physical crowns and simulated mouse wheels to the watch axis ABI. */
    static float scrollDegrees(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) return Float.NaN;
        float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (vertical == 0f) vertical = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (vertical == 0f) return Float.NaN;
        float scale = event.isFromSource(InputDevice.SOURCE_MOUSE)
            ? ANDROID_MOUSE_WHEEL_SCALE
            : 1f;
        // PodJS RelativeAxis follows the browser/dev host contract: downward
        // motion is positive. Android scroll axes are positive upward, so invert at the boundary.
        return -vertical * DEGREES_PER_SCROLL_UNIT * scale;
    }

    public boolean handleScrollMotion(MotionEvent event) {
        float degrees = scrollDegrees(event);
        if (Float.isNaN(degrees)) return false;
        addRotaryDegrees(degrees);
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (handleScrollMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    private void handleNetwork(String line) {
        try {
            JSONObject command = new JSONObject(line);
            int handle = command.getInt("handle");
            if ("cancel".equals(command.getString("t"))) {
                HttpURLConnection connection = requests.remove(handle);
                if (connection != null) connection.disconnect();
                return;
            }
            long requestHost = host;
            network.execute(() -> executeRequest(requestHost, handle, command));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid PodJS network command", error);
        }
    }

    private void executeRequest(long requestHost, int handle, JSONObject command) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(command.getString("url")).openConnection();
            requests.put(handle, connection);
            connection.setRequestMethod(command.getString("method"));
            int timeout = command.getInt("timeoutMs");
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            JSONObject headers = command.getJSONObject("headers");
            for (Iterator<String> it = headers.keys(); it.hasNext();) { String key = it.next(); connection.setRequestProperty(key, headers.getString(key)); }
            byte[] requestBody = Base64.decode(command.getString("bodyBase64"), Base64.DEFAULT);
            if (requestBody.length > 0) { connection.setDoOutput(true); connection.getOutputStream().write(requestBody); }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            int limit = command.getInt("maxBytes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int total = 0, count;
            while (input != null && (count = input.read(chunk)) >= 0) { total += count; if (total > limit) throw new IOException("response_too_large"); output.write(chunk, 0, count); }
            JSONObject responseHeaders = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) if (entry.getKey() != null && !entry.getValue().isEmpty()) responseHeaders.put(entry.getKey(), entry.getValue().get(0));
            byte[] body = output.toByteArray(); String finalUrl = connection.getURL().toString(); String encodedHeaders = responseHeaders.toString();
            post(() -> { if (host == requestHost) nativeCompleteHttp(requestHost, handle, status, finalUrl, encodedHeaders, body); });
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            post(() -> { if (host == requestHost) nativeFailHttp(requestHost, handle, "network_error", message); });
        } finally {
            requests.remove(handle); if (connection != null) connection.disconnect();
        }
    }

    /** Wear OS sends fractional scroll units; only integral millidegrees cross the ABI. */
    public void addRotaryDegrees(float degrees) {
        rotaryRemainder += degrees * 1000f;
        int whole = (int) rotaryRemainder;
        rotaryRemainder -= whole;
        if (whole != 0 && host != 0) nativeInput(host, new int[0], new float[0], 0, whole);
    }

    public void setLifecycle(int state) {
        if(state!=0)companionSettingsRequested=false;
        if(state!=0 && companionSettings!=null) {companionSettings.close();companionSettings=null;}
        services.syncForeground(state==0);
        active = state != 2;
        if (host != 0) nativeLifecycle(host, state);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (active) Choreographer.getInstance().postFrameCallback(frameCallback);
    }
    /** Native host UI only; pairing approval is a separate prerequisite. */
    public void connectCompanion(String peer,java.net.InetSocketAddress address,boolean listen,PodSyncHostConnection.Listener listener) {
        services.connectSync(peer,address,listen,listener);
    }
    public static final String ACTION_COMPANION_SETTINGS="dev.podjs.action.COMPANION_SETTINGS";
    /** Native host only: caller obtains radio permissions and explicit device selection.
     * A null device is allowed only when listening; radio identity never grants pairing. */
    public void connectCompanionBle(String peer,android.bluetooth.BluetoothDevice selected,boolean listen,PodSyncHostConnection.Listener listener) {
        services.connectSyncBle(peer,selected,listen,listener);
    }
    private PodSyncSettingsDialog companionSettings;
    private boolean companionSettingsRequested;
    public void showCompanionSettings() {
        if(host==0){companionSettingsRequested=true;return;}
        if(companionSettings!=null)companionSettings.close();
        companionSettings=new PodSyncSettingsDialog(getContext(),services);companionSettings.show();
    }
    public boolean sendBack() { return host != 0 && nativeBack(host); }
    public boolean canNavigateBack() { return host != 0 && nativeCanGoBack(host); }

    private static native void nativePostEvent(long host, byte[] event);
    private static native void nativeAccessibilityEnabled(long host, boolean enabled);
    private static native byte[] nativeAccessibilitySnapshot(long host, long[] metadata);
    private static native boolean nativeAccessibilityAction(long host, int id, long hash, int action);
    private static native void nativeNavigationState(long host, boolean canGoBack);
    private static native boolean nativeCanGoBack(long host);
    private static native void nativeBootAssets(long host, AssetManager assets);
    private static native long nativeCreate(Surface surface, String target, int width, int height, float density, String dataDir);
    private static native void nativeBoot(long host, byte[] pak, byte[] js, byte[] manifest);
    private static native void nativeResize(long host, Surface surface, int width, int height);
    private static native void nativeDetachSurface(long host);
    private static native void nativeInput(long host, int[] ids, float[] xy, int count, int rotaryMilliDegrees);
    private static native void nativeFrame(long host);
    private static native String nativePollEffect(long host);
    private static native String nativePollNet(long host);
    private static native void nativeCompleteHttp(long host, int handle, int status, String url, String headers, byte[] body);
    private static native void nativeFailHttp(long host, int handle, String code, String message);
    private static native void nativeLifecycle(long host, int state);
    private static native void nativeTheme(long host, String theme);
    private static native boolean nativeBack(long host);
    private static native void nativeDestroy(long host);
}
