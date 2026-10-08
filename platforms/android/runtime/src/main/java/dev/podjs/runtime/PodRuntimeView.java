package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
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
    static {
        long began = PodStartupTrace.now();
        System.loadLibrary("podjs_android");
        PodStartupTrace.stage("libraryLoad", began);
    }
    private long host;
    private static final class HostLifetime {
        final long handle;
        HostLifetime(long handle) { this.handle = handle; }
    }
    private volatile HostLifetime hostLifetime;
    private HostLifetime rendererTimingOwner;
    private final Handler serviceHandler = new Handler(Looper.getMainLooper());
    private boolean serviceTurnRunning;
    private HostLifetime queuedServiceTurn;
    private boolean rootSwipeEnabled = true;
    private final PodAccessibility accessibility;
    private boolean accessibilityEnabled;
    private final long[] accessibilityMetadata = new long[3];
    private final long viewBegan = android.os.SystemClock.elapsedRealtime();
    private boolean firstTextureLogged;
    private long surfaceBegan;
    private boolean firstFrameLogged;
    private int bootGeneration;
    private boolean detached;
    private final ExecutorService boot = Executors.newSingleThreadExecutor();
    private final PodServices services;
    private final String targetId;
    private float rotaryRemainder;
    private boolean active = true;
    private int lifecycleState;
    private boolean screenCheckpointRegistered;
    private final android.content.BroadcastReceiver screenCheckpoint = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            if (!android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || host == 0) return;
            nativePostEvent(host, "{\"t\":\"checkpoint\",\"reason\":\"screen-off\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // SCREEN_OFF can precede Activity.onPause. Run a nonvisual guest turn
            // inline so durable checkpoints complete before this receiver returns.
            nativePumpServices(host);
            drainHostEffects(false);
        }
    };
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final Map<Integer, HttpURLConnection> requests = new ConcurrentHashMap<>();
    private final FrameTimingWindow javaFramePerf = new FrameTimingWindow();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTime) {
            if (host != 0 && active) {
                long frameBegan = android.os.SystemClock.elapsedRealtimeNanos();
                boolean enabled = accessibility.enabled();
                if (enabled != accessibilityEnabled) {
                    nativeAccessibilityEnabled(host, enabled); accessibilityEnabled = enabled;
                    if (!enabled) accessibility.clear();
                }
                nativeFrame(host, frameTime);
                long nativeDone = android.os.SystemClock.elapsedRealtimeNanos();
                if (enabled) {
                    byte[] semantics = nativeAccessibilitySnapshot(host, accessibilityMetadata);
                    if (semantics != null) accessibility.update(semantics, accessibilityMetadata);
                }
                long accessibilityDone = android.os.SystemClock.elapsedRealtimeNanos();
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    android.util.Log.i("PodJSPerf", "firstMainFrameMs=" + (android.os.SystemClock.elapsedRealtime() - surfaceBegan));
                }
                drainHostEffects(true);
                long effectsDone = android.os.SystemClock.elapsedRealtimeNanos();
                services.pumpNotifications();
                long frameDone = android.os.SystemClock.elapsedRealtimeNanos();
                FrameTimingWindow.Snapshot window = javaFramePerf.add(
                        frameBegan, nativeDone, accessibilityDone, effectsDone, frameDone);
                if (window != null) {
                    android.util.Log.i("PodJSPerf", "javaFrameWindow windowMs="
                        + nanosToMs(window.windowNanos) + " frames=" + window.frames
                        + " p50/p95/maxMs callbackTotal=" + distributionMs(window.callbackTotal)
                        + " native=" + distributionMs(window.nativeFrame)
                        + " accessibility=" + distributionMs(window.accessibility)
                        + " effects=" + distributionMs(window.effects)
                        + " notifications=" + distributionMs(window.notifications));
                }
                if (frameDone - frameBegan >= 50_000_000L) {
                    android.util.Log.i("PodJSPerf", "javaFrame totalMs=" + ((frameDone-frameBegan)/1_000_000.0)
                        + " nativeMs=" + ((nativeDone-frameBegan)/1_000_000.0)
                        + " accessibilityMs=" + ((accessibilityDone-nativeDone)/1_000_000.0)
                        + " effectsMs=" + ((effectsDone-accessibilityDone)/1_000_000.0)
                        + " notificationsMs=" + ((frameDone-effectsDone)/1_000_000.0));
                }
            }
            if (active) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static double nanosToMs(long nanos) { return nanos / 1_000_000.0; }
    private static String distributionMs(FrameTimingWindow.Distribution value) {
        return nanosToMs(value.p50) + "/" + nanosToMs(value.p95) + "/" + nanosToMs(value.max);
    }

    private void drainHostEffects(boolean foreground) {
        String effect;
        while ((effect = nativePollEffect(host)) != null) {
            try {
                JSONObject command = new JSONObject(effect);
                if ("haptic".equals(command.optString("t"))) { if (foreground) performHapticFeedback(6); }
                else if ("navigation".equals(command.optString("t"))) {
                    nativeNavigationState(host, command.optBoolean("canGoBack", false));
                    rootSwipeEnabled = command.optBoolean("rootSwipeEnabled", true);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(!canSwipeDismiss());
                }
                else if ("diagnostic.serviceWork".equals(command.optString("t"))) {
                    if(serviceResultTrace.enabled && command.optString("label").matches("[a-zA-Z][a-zA-Z0-9_.:-]{0,79}"))
                        android.util.Log.i("PodJSService", "guest label="+command.optString("label")+" ms="+command.optLong("elapsedMs")+" exclusiveMs="+command.optLong("exclusiveMs")+" beginEpochMs="+command.optLong("beginEpochMs")+" endEpochMs="+command.optLong("endEpochMs")+(command.has("units")?" units="+command.optLong("units"):""));
                }
                else if (command.optString("t").startsWith("service.")) {
                    if (serviceResultTrace.enabled) {
                        if ("service.request".equals(command.optString("t"))) serviceResultTrace.request(command.optInt("id"),command.optString("method"),command.optJSONObject("args")==null?null:command.optJSONObject("args").optString("traceLabel",null),command.optJSONObject("args")==null?null:command.optJSONObject("args").optString("sql",null));
                        else if ("service.cancel".equals(command.optString("t"))) serviceResultTrace.cancel(command.optInt("id"));
                    }
                    services.dispatch(command);
                }
                else if ("notification.ack".equals(command.optString("t"))) services.acknowledgeNotification(command.getString("eventId"));
            } catch (Exception error) { android.util.Log.e("PodJS", "Invalid host effect", error); }
        }
        String command;
        while ((command = nativePollNet(host)) != null) handleNetwork(command);
    }
    private void pumpServicesWhileSuspended() {
        if (host == 0 || lifecycleState == 0) return;
        nativePumpServices(host);
        drainHostEffects(false);
    }

    private void setHost(long handle) {
        host = handle;
        serviceTraceGuestEnabled=false;
        hostLifetime = handle == 0 ? null : new HostLifetime(handle);
    }
    private void destroyHost() {
        long previous = host;
        setHost(0);
        queuedServiceTurn = null;
        if (previous != 0) nativeDestroy(previous);
    }
    private boolean ownsHost(HostLifetime owner) { return owner != null && hostLifetime == owner; }

    /** Host-only diagnostic schema; CPU wall time includes waits, not display time. */
    static final class RendererTiming {
        static final int HEADER_SIZE=7,FIELD_COUNT=15,MAX_CAPACITY=4096;
        static final int H_VERSION=0,H_FIELDS=1,H_CAPACITY=2,H_COUNT=3,H_DROPPED=4,H_START_NS=5,H_STOP_NS=6;
        static final int SEQUENCE=0,HOST_FRAME=1,RENDERER_FRAME=2,STAGE=3,BEGIN_NS=4,END_NS=5,
            OUTCOME=6,RESULT_CODE=7,TEXTURE_KEY=8,REVISION=9,WIDTH=10,HEIGHT=11,BYTES=12,COUNT=13,FLAGS=14;
        static final int SUCCESS=1,FAILURE=-1;
        static final int HOST_FRAME_STAGE=1,GUEST=2,SNAPSHOT=3,DRAW_LIST=9,DECODE=10,UPLOAD_IMAGES=11,
            UPLOAD_PRIOR_FENCE_WAIT=12,CREATE_TEXTURE=13,STAGING_COPY=14,UPLOAD_RECORD=15,
            UPLOAD_SUBMIT=16,UPLOAD_FENCE_WAIT=17,FRAME_FENCE_WAIT=18,ACQUIRE_IMAGE=19,
            SCENE_RECORD=20,FRAME_SUBMIT=21,PRESENT=22,DESTROY_TEXTURE=23,CACHE_HIT=24,
            UPLOAD_COMMITTED=25,STAGING_CAPACITY=26,CPU_FRAME=27;
    }
    void beginRendererTiming(int capacity) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Renderer timing requires the main looper");
        if (capacity < 1 || capacity > RendererTiming.MAX_CAPACITY) throw new IllegalArgumentException("Invalid renderer timing capacity");
        HostLifetime owner = hostLifetime;
        if (!ownsHost(owner)) throw new IllegalStateException("Renderer timing requires a live host");
        if (rendererTimingOwner != null) throw new IllegalStateException("Renderer timing already owned");
        if (!nativeBeginRendererTiming(owner.handle, capacity)) throw new IllegalStateException("Renderer timing unavailable or already active");
        rendererTimingOwner = owner;
    }
    long[] endRendererTiming() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Renderer timing requires the main looper");
        HostLifetime owner = rendererTimingOwner;
        rendererTimingOwner = null;
        if (owner == null) throw new IllegalStateException("Renderer timing is not owned");
        // A destroyed renderer releases its trace. Never use the old native
        // pointer if this View has already acquired a different guest lifetime.
        if (!ownsHost(owner)) throw new IllegalStateException("Renderer timing host lifetime changed");
        // Native detaches/stops the trace before allocating the return array.
        // A failed Java allocation therefore cannot leave recording enabled.
        long[] snapshot = nativeEndRendererTiming(owner.handle);
        if (snapshot == null) throw new IllegalStateException("Renderer timing snapshot unavailable");
        return snapshot;
    }

    // Every completion enters through a new main-looper job. A token identifies
    // the guest lifetime even if the allocator later reuses its native address.
    private final ServiceResultTrace serviceResultTrace;
    private boolean serviceTraceGuestEnabled;
    private void postHostCompletion(HostLifetime owner, Runnable completion) {
        postHostCompletion(owner,completion,null);
    }
    private void postHostCompletion(HostLifetime owner, Runnable completion, ServiceResultTrace.Completion category) {
        if (owner == null) return;
        serviceHandler.post(() -> {
            if (!ownsHost(owner)) return;
            long began=category==null?0:System.nanoTime();
            try {
                completion.run();
                pumpServiceResults(owner);
            } finally {
                if(category!=null) {
                    String record=serviceResultTrace.finish(category,System.nanoTime()-began);
                    if(record!=null)android.util.Log.i("PodJSService",record);
                }
            }
        });
    }
    private void receiveServiceEvent(String event) {
        HostLifetime owner = hostLifetime;
        if (owner == null) return;
        byte[] bytes = event.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ServiceResultTrace.Completion category=null;
        if(serviceResultTrace.enabled)try {
            JSONObject value=new JSONObject(event);
            String type=value.optString("t");
            if("service.result".equals(type)||"browser.auth.status".equals(type))category=serviceResultTrace.completion(value.optInt("id"),"service.result".equals(type),bytes.length,value.optJSONArray("value")==null?-1:value.optJSONArray("value").length());
        } catch(Exception ignored) { /* Diagnostics never block delivery. */ }
        postHostCompletion(owner, () -> {
            if(serviceResultTrace.enabled&&!serviceTraceGuestEnabled) {
                nativePostEvent(owner.handle,"{\"t\":\"diagnostic.serviceTrace\",\"enabled\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                serviceTraceGuestEnabled=true;
            }
            nativePostEvent(owner.handle, bytes);
        },category);
    }
    private void pumpServiceResults(HostLifetime owner) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Service turns require the main looper");
        if (!ownsHost(owner)) return;
        if (serviceTurnRunning) {
            // A nested host callback may request one later turn, never recursively
            // enter QuickJS or wait for another operation to finish.
            if (queuedServiceTurn != owner) {
                queuedServiceTurn = owner;
                serviceHandler.post(() -> {
                    if (queuedServiceTurn != owner) return;
                    queuedServiceTurn = null;
                    pumpServiceResults(owner);
                });
            }
            return;
        }
        serviceTurnRunning = true;
        try {
            if (lifecycleState == 0) nativePumpServiceResults(owner.handle);
            else nativePumpServices(owner.handle);
            if (ownsHost(owner)) drainHostEffects(lifecycleState == 0);
        } finally { serviceTurnRunning = false; }
    }

    public PodRuntimeView(Context context, String targetId) {
        super(context);
        long began = PodStartupTrace.now();
        this.targetId = targetId;
        // Default log level is INFO. Only a debuggable host explicitly opted in
        // via log.tag.PodJSService=DEBUG collects/logs completion-turn timings.
        serviceResultTrace = new ServiceResultTrace(
            (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,
            android.util.Log.isLoggable("PodJSService",android.util.Log.DEBUG));
        accessibility = new PodAccessibility(this, (id, hash, action) -> host != 0 && nativeAccessibilityAction(host, id, hash, action));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        long servicesBegan = PodStartupTrace.now();
        services = new PodServices(context, this::receiveServiceEvent);
        services.enableDeviceServiceTrace(serviceResultTrace.enabled);
        if(PodServices.memoryEnabled(context))services.attachMemoryProvider(()->{
            if(host==0)return null;
            try {JSONObject stats=new JSONObject(nativeMemoryStats(host));stats.put("pid",android.os.Process.myPid());stats.put("networkRequests",requests.size());return stats;}
            catch(Exception error){return null;}
        });
        PodStartupTrace.stage("servicesCreate", servicesBegan);
        setSurfaceTextureListener(this);
        setOpaque(false);
        setFocusable(true);
        PodStartupTrace.stage("viewCreate", began);
    }
    public void captureNotificationIntent(android.content.Intent intent) {services.captureNotificationIntent(intent);}
    @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() { return accessibility; }
    @Override public boolean dispatchHoverEvent(MotionEvent event) { return accessibility.hover(event) || super.dispatchHoverEvent(event); }
    public void notificationPermissionResult(int requestCode,int[] results){services.notificationPermissionResult(requestCode,results);}

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        if (!screenCheckpointRegistered) {
            android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
            if (android.os.Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(screenCheckpoint, filter, Context.RECEIVER_NOT_EXPORTED);
            else getContext().registerReceiver(screenCheckpoint, filter);
            screenCheckpointRegistered = true;
        }
        if (getParent() instanceof ViewGroup) {
            services.attachVideo((ViewGroup) getParent());
            services.attachMediaSurfaces((ViewGroup)getParent(),new PodMediaSurfaceServices.TextureSink(){
                public int write(int handle,int[] pixels,int width,int height,boolean linear){return host==0?-1:nativeMediaTexture(host,handle,pixels,width,height,linear);}
                public void free(int handle){if(host!=0)nativeFreeMediaTexture(host,handle);}
            });
            services.attachInlineText((ViewGroup)getParent());
        }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) services.pauseVideoForLifecycle();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (host != 0) {
            Surface surface = new Surface(texture);
            try {
                nativeResize(host, surface, width, height);
                nativeTheme(host, currentTheme());
                nativeLifecycle(host, lifecycleState);
                pumpServicesWhileSuspended();
            } finally { surface.release(); }
            return;
        }
        surfaceBegan = android.os.SystemClock.elapsedRealtime();
        firstFrameLogged = false;
        android.util.Log.i("PodJSPerf", "startup stage=surfaceReady viewToSurfaceMs="
            + (surfaceBegan - viewBegan));
        File data = PodTakeover.dataRoot(getContext());
        if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("PodJS data directory");
        final Surface bootSurface = new Surface(texture);
        final int generation = ++bootGeneration;
        final AssetManager assets = getContext().getAssets();
        final float density = getResources().getDisplayMetrics().density;
        final android.view.ViewConfiguration inputConfig = android.view.ViewConfiguration.get(getContext());
        final int touchSlop = inputConfig.getScaledTouchSlop();
        final int minFling = inputConfig.getScaledMinimumFlingVelocity();
        final int maxFling = inputConfig.getScaledMaximumFlingVelocity();
        final String dataPath = data.getAbsolutePath();
        final long queued = PodStartupTrace.now();
        try {
            boot.execute(() -> {
                PodStartupTrace.stage("bootQueueWait", queued);
                long candidate = 0;
                Throwable failure = null;
                try {
                    long createBegan = PodStartupTrace.now();
                    candidate = nativeCreate(bootSurface, targetId, width, height, density, touchSlop, minFling, maxFling, dataPath);
                    if (candidate == 0) throw new IllegalStateException("PodJS native host creation failed");
                    PodStartupTrace.stage("nativeCreate", createBegan);
                    long assetsBegan = PodStartupTrace.now();
                    nativeBootAssets(candidate, assets);
                    PodStartupTrace.stage("nativeBootAssets", assetsBegan);
                } catch (RuntimeException | Error error) {
                    failure = error;
                } finally {
                    bootSurface.release();
                }
                final long booted = candidate;
                final Throwable bootFailure = failure;
                final long posted = PodStartupTrace.now();
                serviceHandler.post(() -> finishBoot(booted, generation, bootFailure, texture, width, height, posted));
            });
        } catch (RuntimeException | Error error) {
            bootSurface.release();
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException("PodJS boot failed", error);
        }
    }

    private void finishBoot(long candidate, int generation, Throwable failure,
            SurfaceTexture bootTexture, int bootWidth, int bootHeight, long posted) {
        PodStartupTrace.stage("finishBootQueueWait", posted);
        long began = PodStartupTrace.now();
        if (detached || generation != bootGeneration || !isAvailable()) {
            if (candidate != 0) nativeDestroy(candidate);
            return;
        }
        if (candidate == 0) {
            android.util.Log.e("PodJS", "Background boot failed before native host creation", failure);
            if (failure instanceof Error) throw (Error)failure;
            throw new IllegalStateException("PodJS boot failed", failure);
        }
        if (failure != null) {
            nativeDestroy(candidate);
            android.util.Log.e("PodJS", "Background boot failed", failure);
            throw new IllegalStateException("PodJS boot failed", failure);
        }
        setHost(candidate);
        startMemoryTrace(hostLifetime);
        services.resumeModalState();
        accessibilityEnabled = false;
        SurfaceTexture texture = getSurfaceTexture();
        long resizeBegan = PodStartupTrace.now();
        boolean resize = texture != null && getWidth() > 0 && getHeight() > 0
            && BootSurface.needsResize(bootTexture, bootWidth, bootHeight,
                texture, getWidth(), getHeight(), nativeRendererReady(host));
        if (resize) {
            Surface surface = new Surface(texture);
            try { nativeResize(host, surface, getWidth(), getHeight()); }
            finally { surface.release(); }
        }
        android.util.Log.i("PodJSPerf", "startup stage=finishBootResize resize=" + resize
            + " elapsedMs=" + PodStartupTrace.ms(PodStartupTrace.now() - resizeBegan));
        long backgroundBegan = PodStartupTrace.now();
        services.approveInstalledBackground(targetId);
        services.approveInstalledSync(targetId);
        PodStartupTrace.stage("backgroundEnqueue", backgroundBegan);
        if(companionSettingsRequested) {companionSettingsRequested=false;showCompanionSettings();}
        long themeBegan = PodStartupTrace.now();
        nativeTheme(host, currentTheme());
        nativeLifecycle(host, lifecycleState);
        PodStartupTrace.stage("themeLifecycle", themeBegan);
        // The worker prepared and presented frame zero, including its first
        // mapped font uploads. Continue guest/service turns on the main loop.
        long pumpBegan = PodStartupTrace.now();
        pumpServiceResults(hostLifetime);
        PodStartupTrace.stage("firstServiceResults", pumpBegan);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (active) Choreographer.getInstance().postFrameCallback(frameCallback);
        PodStartupTrace.stage("finishBoot", began);
    }

    /** Explicit trace opt-in only; normal debug installs have no periodic heap walk. */
    private void startMemoryTrace(final HostLifetime owner) {
        final MemoryTraceScheduler trace = new MemoryTraceScheduler(PodServices.memoryTraceEnabled(getContext()));
        trace.schedule(serviceHandler::postDelayed, new Runnable(){
            @Override public void run(){
                if(owner!=hostLifetime||detached)return;
                try {services.collectMemoryStats(value->{
                    if(owner==hostLifetime&&!detached) {
                        if(value!=null)android.util.Log.i("PodJSMem",value.toString());
                        // Schedule after delivery so a busy text worker cannot
                        // accumulate samples or starve paragraph/raster jobs.
                        trace.schedule(serviceHandler::postDelayed,this);
                    }
                });}catch(RuntimeException unavailable){
                    android.util.Log.w("PodJSMem","sample unavailable");
                    if(owner==hostLifetime&&!detached)trace.schedule(serviceHandler::postDelayed,this);
                }
            }
        });
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        bootGeneration++;
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
            if (getContext() instanceof android.app.Activity) {
                ((android.app.Activity)getContext()).getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }
            android.util.Log.i("PodJSPerf", "firstFrame surfaceToFirstFrameMs="
                + (android.os.SystemClock.elapsedRealtime() - surfaceBegan)
                + " viewToFirstTextureMs=" + (android.os.SystemClock.elapsedRealtime() - viewBegan));
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (screenCheckpointRegistered) {
            getContext().unregisterReceiver(screenCheckpoint);
            screenCheckpointRegistered = false;
        }
        detached = true;
        bootGeneration++;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        accessibility.clear();
        destroyHost();
        for (HttpURLConnection connection : requests.values()) connection.disconnect();
        requests.clear();
        network.shutdownNow();
        boot.shutdown();
        services.close();
        serviceResultTrace.clear();
        super.onDetachedFromWindow();
    }

    private String currentTheme() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (host != 0) nativeTheme(host, currentTheme());
            nativeLifecycle(host, lifecycleState);
            pumpServicesWhileSuspended();
    }

    private int tracedInputs;
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (serviceResultTrace.enabled && event.getActionMasked() != MotionEvent.ACTION_MOVE && tracedInputs++ < 128) {
            android.util.Log.i("PodJSService", "input action=" + event.getActionMasked()
                + " eventMs=" + event.getEventTime() + " receivedMs=" + android.os.SystemClock.uptimeMillis()
                + " epochMs=" + System.currentTimeMillis());
        }
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
    static float rawScrollDelta(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) return Float.NaN;
        float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (vertical == 0f) vertical = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        return vertical == 0f || !Float.isFinite(vertical) ? Float.NaN : vertical;
    }

    static float scrollDegrees(MotionEvent event) {
        float vertical = rawScrollDelta(event);
        if (Float.isNaN(vertical)) return Float.NaN;
        float scale = event.isFromSource(InputDevice.SOURCE_MOUSE)
            ? ANDROID_MOUSE_WHEEL_SCALE
            : 1f;
        // PodJS RelativeAxis follows the browser/dev host contract: downward
        // motion is positive. Android scroll axes are positive upward, so invert at the boundary.
        return -vertical * DEGREES_PER_SCROLL_UNIT * scale;
    }

    public boolean handleScrollMotion(MotionEvent event) {
        if (services.handleInlineCrownScroll(event)) return true;
        float degrees = scrollDegrees(event);
        if (Float.isNaN(degrees)) return false;
        // Retain unscaled Android motion for applications matching physical device policies.
        // The existing normalized axis is still delivered for scrolling/navigation.
        if (host != 0) {
            String eventJson = "{\"t\":\"input.scroll\",\"vertical\":" + rawScrollDelta(event)
                    + ",\"uptimeMs\":" + event.getEventTime() + ",\"source\":" + event.getSource() + "}";
            nativePostEvent(host, eventJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
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
            HostLifetime owner = hostLifetime;
            if (owner != null) network.execute(() -> executeRequest(owner, handle, command));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid PodJS network command", error);
        }
    }

    private void executeRequest(HostLifetime owner, int handle, JSONObject command) {
        HttpURLConnection connection = null;
        try {
            if (!ownsHost(owner)) return;
            connection = (HttpURLConnection) new URL(command.getString("url")).openConnection();
            requests.put(handle, connection);
            if (!ownsHost(owner)) return;
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
            postHostCompletion(owner, () -> nativeCompleteHttp(owner.handle, handle, status, finalUrl, encodedHeaders, body));
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            postHostCompletion(owner, () -> nativeFailHttp(owner.handle, handle, "network_error", message));
        } finally {
            if (connection != null) { requests.remove(handle, connection); connection.disconnect(); }
        }
    }

    /** Wear OS sends fractional scroll units; only integral millidegrees cross the ABI. */
    public void addRotaryDegrees(float degrees) {
        rotaryRemainder += degrees * 1000f;
        int whole = (int) rotaryRemainder;
        rotaryRemainder -= whole;
        if (whole != 0 && host != 0) nativeInput(host, new int[0], new float[0], 0, whole);
    }

    @Override public void onWindowFocusChanged(boolean focused) {super.onWindowFocusChanged(focused);if(services!=null)services.acousticFocus(focused);}

    public void setLifecycle(int state) {
        lifecycleState=state;
        services.acousticLifecycle(state);
        services.screenLifecycle(state==0);
        if(state!=0)services.pauseVideoForLifecycle();
        if(state!=0)companionSettingsRequested=false;
        if(state!=0 && companionSettings!=null) {companionSettings.close();companionSettings=null;}
        services.syncForeground(state==0);
        active = state != 2;
        if (host != 0) nativeLifecycle(host, state);
        pumpServicesWhileSuspended();
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
    public void restoreModalState(String saved,String retained) { services.restoreModalState(saved,retained); }
    public String savedModalState() { flushActivityState(); return services.savedModalState(); }
    public String retainedModalState() { flushActivityState(); return services.retainedModalState(); }
    public void restoreActivityState(String saved, String retained) { services.activityState.restore(saved, retained); }
    private void flushActivityState() { if (host != 0) { nativePumpServices(host); drainHostEffects(false); } }
    public String savedActivityState() { flushActivityState(); return services.activityState.saved(); }
    public String retainedActivityState() { flushActivityState(); return services.activityState.retained(); }
    public boolean canNavigateBack() { return host != 0 && nativeCanGoBack(host); }
    /** Independent from system back: consent screens can allow root back while
     * preventing the platform or host from recognizing a dismissing swipe. */
    public boolean canSwipeDismiss() { return rootSwipeEnabled && !canNavigateBack(); }
    @Override public boolean canScrollHorizontally(int direction) {
        // Platform SwipeDismissLayout checks whether its child owns horizontal
        // movement. Keep internal navigation and explicit swipe locks in-app.
        return !canSwipeDismiss() || super.canScrollHorizontally(direction);
    }

    private static native String nativeMemoryStats(long host);
    private static native void nativePostEvent(long host, byte[] event);
    private static native void nativeAccessibilityEnabled(long host, boolean enabled);
    private static native byte[] nativeAccessibilitySnapshot(long host, long[] metadata);
    private static native boolean nativeAccessibilityAction(long host, int id, long hash, int action);
    private static native void nativeNavigationState(long host, boolean canGoBack);
    private static native boolean nativeCanGoBack(long host);
    private static native void nativeBootAssets(long host, AssetManager assets);
    private static native long nativeCreate(Surface surface, String target, int width, int height, float density, int touchSlop, int minFling, int maxFling, String dataDir);
    private static native void nativeBoot(long host, byte[] pak, byte[] js, byte[] manifest);
    private static native boolean nativeRendererReady(long host);
    private static native void nativeResize(long host, Surface surface, int width, int height);
    private static native void nativeDetachSurface(long host);
    private static native void nativeInput(long host, int[] ids, float[] xy, int count, int rotaryMilliDegrees);
    private static native void nativePumpServices(long host);
    private static native void nativePumpServiceResults(long host);
    private static native void nativeFrame(long host, long frameTimeNanos);
    private static native boolean nativeBeginRendererTiming(long host,int capacity);
    private static native long[] nativeEndRendererTiming(long host);
    private static native int nativeMediaTexture(long host,int handle,int[] argb,int width,int height,boolean linear);
    private static native void nativeFreeMediaTexture(long host,int handle);
    private static native String nativePollEffect(long host);
    private static native String nativePollNet(long host);
    private static native void nativeCompleteHttp(long host, int handle, int status, String url, String headers, byte[] body);
    private static native void nativeFailHttp(long host, int handle, String code, String message);
    private static native void nativeLifecycle(long host, int state);
    private static native void nativeTheme(long host, String theme);
    private static native boolean nativeBack(long host);
    private static native void nativeDestroy(long host);
}
