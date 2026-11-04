package dev.podjs.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Version 1 asynchronous service transport, scoped to one guest lifetime. */
final class PodServices implements Closeable {
    private java.util.function.Consumer<String> debugExternalSink;
    interface Sink { void complete(String event); }
    private final Context context;
    final PodActivityState activityState = new PodActivityState();
    final PodModalState modalState = new PodModalState();
    private EditText modalInput;
    private AlertDialog modalDialog;
    private String modalKey, modalTitle;
    String savedModalState() { return browser.savedState(); }
    String retainedModalState() {
        if (modalInput != null && modalKey != null) modalState.update(modalKey,modalTitle,modalInput.getText().toString(),modalInput.getSelectionStart(),modalInput.getSelectionEnd());
        return modalState.retained();
    }
    void restoreModalState(String saved,String retained) { browser.restoreState(saved); modalState.restore(retained); }
    void resumeModalState() { if(!closed) browser.resumeRestored(); }
    private final Sink sink;
    /** Logs every service request and replies over 64 KiB (tag PodJSPerf); for startup profiling. */
    private static final boolean PERF_TRACE = false;
    private static final boolean MEMORY_TRACE = false;
    static boolean memoryEnabled(Context context) {
        return MEMORY_TRACE || PERF_TRACE || (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
    }
    /** adb shell setprop log.tag.PodJSMem DEBUG before launch opts in.
     * Release hosts still require the explicit source PERF_TRACE opt-in. */
    static boolean memoryTraceEnabled(Context context) {
        return MEMORY_TRACE || PERF_TRACE || (memoryEnabled(context) && android.util.Log.isLoggable("PodJSMem", android.util.Log.DEBUG));
    }
    private final PodTextRasterServices.Stats textBitmapStats;
    private java.util.function.Supplier<JSONObject> memoryProvider;
    void attachMemoryProvider(java.util.function.Supplier<JSONObject> provider){memoryProvider=provider;}
    /** Native/QuickJS on main, paragraph data on its serial text worker. The
     * timestamps expose lane delay rather than claiming an atomic JVM snapshot. */
    void collectMemoryStats(java.util.function.Consumer<JSONObject> done) {
        if(!memoryEnabled(context))throw new SecurityException("Memory tools require a debug build");
        if(closed)return;
        final long nativeAt=android.os.SystemClock.elapsedRealtime();
        final long nativeBegan=android.os.SystemClock.elapsedRealtimeNanos();
        final JSONObject value=memoryProvider==null?new JSONObject():memoryProvider.get();
        if(value==null)throw new IllegalStateException("Runtime memory unavailable");
        final double nativeCollectMs=(android.os.SystemClock.elapsedRealtimeNanos()-nativeBegan)/1_000_000.0;
        textWorker.execute(()->{
            try {
                JSONObject text=new JSONObject().put("paragraphEntries",paragraphLayouts.entries.size())
                    .put("paragraphUtf16Units",paragraphLayouts.units).put("paragraphBuilds",paragraphLayouts.builds)
                    .put("textBitmaps",textBitmapStats==null?JSONObject.NULL:textBitmapStats.snapshot());
                long textAt=android.os.SystemClock.elapsedRealtime();
                main.post(()->{
                    if(closed)return;
                    try {
                        Runtime jvm=Runtime.getRuntime();
                        value.put("nativeAtMs",nativeAt).put("textAtMs",textAt).put("nativeCollectMs",nativeCollectMs)
                            .put("sampleAtMs",android.os.SystemClock.elapsedRealtime()).put("text",text)
                            .put("videoFrames",videoSessions.memoryStats()).put("mediaPrefix",mediaPrefixCache.memoryStats())
                            .put("java",new JSONObject().put("nativeHeapAllocatedBytes",android.os.Debug.getNativeHeapAllocatedSize())
                                .put("nativeHeapSizeBytes",android.os.Debug.getNativeHeapSize())
                                .put("heapUsedBytes",jvm.totalMemory()-jvm.freeMemory()).put("heapCapacityBytes",jvm.totalMemory())
                                .put("serviceRequests",requests.size())
                                .put("idleImageDecoders",images.idleDecoderCount()).put("allProcessBitmaps",JSONObject.NULL));
                        done.accept(value);
                    }catch(Exception error){android.util.Log.w("PodJSMem","sample unavailable");done.accept(null);}
                });
            }catch(Exception error){main.post(()->done.accept(null));}
        });
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    interface PaintAwaiter {Runnable await(java.util.function.Consumer<Boolean> complete);}
    private PaintAwaiter paintAwaiter;
    void attachPaintAwaiter(PaintAwaiter awaiter){paintAwaiter=awaiter;}
    private final PodParagraphLayout.Cache paragraphLayouts=new PodParagraphLayout.Cache();
    private final ServiceWorkStats workStats=new ServiceWorkStats();
    private ExecutorService serviceExecutor(String lane){return Executors.newSingleThreadExecutor(r->new Thread(()->{
        if(headlessWork)android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        android.util.Log.i("PodJSService","executor="+lane+" lane="+(headlessWork?"background":"foreground")+" tid="+android.os.Process.myTid());r.run();
    },"Pod"+lane+(headlessWork?"BG":"FG")));}
    private final ExecutorService worker = serviceExecutor("Device");
    // Secure UI actions bypass this guest's file/cache queue as well as headless work.
    private final ExecutorService secureWorker = serviceExecutor("Secure");
    private volatile boolean headlessWork;
    private final Runnable unsubscribeData;
    void headlessWork() { headlessWork = true; }
    /** SQLite owns one connection and must stay serial, but must not queue behind file IO,
     * log writes or cache scans on `worker`; text layout/raster likewise gets its own lane so
     * reader pagination never blocks (or is blocked by) storage. Per-lane order is preserved. */
    private final ExecutorService sqlWorker = serviceExecutor("SQL");
    private final ExecutorService textWorker = serviceExecutor("Text");
    private final ConcurrentHashMap<Integer, Request> requests = new ConcurrentHashMap<>();
    private final PodDeviceServices device;
    void enableDeviceServiceTrace(boolean enabled){device.enableServiceTrace(enabled);}
    private final PodMediaServices media;
    private PodScreenServices screen;
    private final PodDebugPerformance debugPerformance = new PodDebugPerformance();
    private boolean displayResumed=true;
    void screenLifecycle(boolean resumed){displayResumed=resumed;if(!resumed)main.post(()->{if(mediaPrefixCache.startupPreview!=null)mediaPrefixCache.startupPreview.hide(0);});if(!resumed)provisioning.closeSession();mediaSurfaces.lifecycle(resumed);videoSessions.lifecycle(resumed);main.post(()->{if(!closed){debugPerformance.lifecycle(resumed);if(screen!=null)screen.lifecycle(resumed);}});}
    private final PodVideoDecoderBudget videoDecoderBudget;
    private final PodVideoServices video;
    private final PodMediaPrefixCache mediaPrefixCache;
    private final PodVideoSessionServices videoSessions;
    private final PodMediaSurfaceServices mediaSurfaces;
    private final PodInlineTextInput inlineText;
    private final PodImageServices images;
    private final PodBrowserAuthServices browserAuth;
    private final PodSiteChallengeServices siteChallenge;
    private final PodHttpServices http;
    private final PodByteStreamServices byteStreams;
    private final PodWebSocketServices webSockets;
    private final PodDiscoveryServices discovery;
    private final PodBleDiscoveryServices bleDiscovery;
    private final PodBleProvisionServices provisioning;
    private final PodBluetoothPermission provisionPermission;
    private final PodNetworkServices networks;
    private final ExecutorService streamWorkers=Executors.newFixedThreadPool(9);
    private final ExecutorService imageWorkers=Executors.newFixedThreadPool(4);
    private final PodBrowserServices browser;
    private PodBackgroundServices background;
    private volatile PodSyncServices sync;
    private volatile PodInstalledSync installedSync;
    private volatile boolean syncForeground;
    void syncForeground(boolean active) {
        syncForeground=active;
        PodInstalledSync owner=installedSync;
        if(owner!=null) owner.connection.setForeground(active);
    }
    void connectSync(String peer,java.net.InetSocketAddress address,boolean listen,PodSyncHostConnection.Listener listener) {
        worker.execute(()->{
            try {
                if(closed || installedSync==null) throw new IllegalStateException("Installed sync owner unavailable");
                installedSync.connection.start(peer,address,listen,listener);
            } catch(Exception unavailable) { main.post(()->{if(!closed)listener.changed("failed","Approved sync connection unavailable");}); }
        });
    }
    void connectSyncBle(String peer,android.bluetooth.BluetoothDevice selected,boolean listen,PodSyncHostConnection.Listener listener) {
        java.util.Objects.requireNonNull(listener);
        worker.execute(()->{
            try {
                if(closed || installedSync==null) throw new IllegalStateException("Installed sync owner unavailable");
                installedSync.connection.startBle(context,peer,selected,listen,listener);
            } catch(Exception unavailable) { main.post(()->{if(!closed)listener.changed("failed","Approved sync connection unavailable");}); }
        });
    }
    void syncIdentity(java.util.function.Consumer<String> callback) {
        worker.execute(()->{ PodInstalledSync owner=installedSync; String identity=owner==null?null:context.getPackageName()+"\n"+owner.client.localDeviceId();
            main.post(()->{if(!closed)callback.accept(identity);}); });
    }
    void approveSyncPair(String peer,byte[] key,java.util.function.Consumer<Boolean> callback) {
        byte[] stable=key.clone();
        try { worker.execute(()->{
            boolean approved=false;
            try { if(closed || !syncForeground || installedSync==null) throw new IllegalStateException("Sync unavailable");
                installedSync.client.authorizeAfterUserApproval(peer,stable); approved=true;
            } catch(Exception rejected) { /* UI receives no credential-bearing error. */ }
            finally { java.util.Arrays.fill(stable,(byte)0); }
            boolean result=approved; main.post(()->{if(!closed)callback.accept(result);});
        }); } catch(RuntimeException rejected) { java.util.Arrays.fill(stable,(byte)0); throw rejected; }
    }
    void disconnectSync() { PodInstalledSync owner=installedSync; if(owner!=null) owner.connection.disconnect(); }
    void approveInstalledSync(String target) {
        worker.execute(() -> {
            if(closed || installedSync!=null) return;
            try {
                PodInstalledSync owner=PodInstalledSync.afterOwnerRelease(()->PodInstalledSync.open(context,target),()->closed);
                if(closed){if(owner!=null)owner.close();return;}
                installedSync=owner;
                if(installedSync!=null) { sync=installedSync.services; installedSync.connection.setForeground(syncForeground); }
            } catch(Exception error) { if(!closed)android.util.Log.e("PodJS","Installed sync owner unavailable",error); }
        });
    }
    private PodNotifications notifications;
    private final PodAcousticServices acoustic;
    private final PodGuidedWifiServices guidedWifi;
    private final PodBluetoothPermission acousticPermission, guidedPermission;
    private final java.util.concurrent.ExecutorService acousticWorkers = java.util.concurrent.Executors.newFixedThreadPool(2);
    void acousticLifecycle(int state){acoustic.lifecycle(state==0);if(state==2)guidedWifi.cancel();}
    void acousticFocus(boolean focused){acoustic.focus(focused);}
    private final PodNotificationPermission notificationPermission;
    private final PodBluetoothPermission bluetoothPermission;
    private final PodBluetoothPermission discoveryPermission;
    private final PodLocationServices location;
    private long notificationPollAt;
    private boolean notificationPollQueued;
    private PodNotifications notifications() throws Exception {
        if (notifications==null) notifications=new PodNotifications(context);
        return notifications;
    }
    void captureNotificationIntent(android.content.Intent intent) {
        if (intent==null || closed) return;
        android.content.Intent snapshot=new android.content.Intent(intent);
        worker.execute(() -> {try {if(!closed) notifications().captureOpen(snapshot);} catch(Exception error){android.util.Log.e("PodJS","Notification capture failed",error);}});
    }
    void acknowledgeNotification(String eventId) {
        if(closed)return;
        worker.execute(() -> {try {if(!closed)notifications().acknowledge(eventId);} catch(Exception error){android.util.Log.e("PodJS","Notification acknowledgement failed",error);}});
    }
    synchronized void pumpNotifications() {
        long now=android.os.SystemClock.elapsedRealtime();
        if(closed || notificationPollQueued || now<notificationPollAt)return;
        notificationPollAt=now+1000;notificationPollQueued=true;
        worker.execute(() -> {
            try {
                if(!closed) {
                    if(sync!=null) {
                        PodSyncServices owner=sync;
                        org.json.JSONArray states=owner.stateEvents();
                        for(int i=0;i<states.length();i++) {
                            String event=states.getJSONObject(i).toString();
                            main.post(() -> {if(!closed && sync==owner)sink.complete(event);});
                        }
                        org.json.JSONArray messages=owner.messageEvents(System.currentTimeMillis());
                        for(int i=0;i<messages.length();i++) {
                            String event=messages.getJSONObject(i).toString();
                            main.post(() -> {if(!closed && sync==owner)sink.complete(event);});
                        }
                        org.json.JSONArray files=owner.fileEvents();
                        for(int i=0;i<files.length();i++) {
                            String event=files.getJSONObject(i).toString();
                            main.post(() -> {if(!closed && sync==owner)sink.complete(event);});
                        }
                    }
                    org.json.JSONArray batch=notifications().pendingEvents();
                    for(int i=0;i<batch.length();i++) {
                        String event=batch.getJSONObject(i).toString();
                        main.post(() -> {if(!closed)sink.complete(event);});
                    }
                }
            } catch(Exception error){android.util.Log.e("PodJS","Notification inbox unavailable",error);}
            finally {synchronized(PodServices.this){notificationPollQueued=false;}}
        });
    }
    private final ExecutorService transfers = Executors.newFixedThreadPool(4);
    private SQLiteDatabase database;
    private PodCloudWork cloudWork;
    private volatile boolean closed;
    private static final class Request {
        final CancellationSignal cancellation = new CancellationSignal();
        volatile Future<?> future;
        HostWorkPriority.Ticket priority;
        AlertDialog dialog;
        Runnable timer;
        Runnable paintCancel;
        String method;
        volatile PodBrowserAuthServices.Callback authCallback;
        volatile PodSiteChallengeServices.Callback challengeCallback;
        String videoSessionId;
    }
    PodServices(Context context, Sink sink) { long step=PodStartupTrace.now(); PodTakeover.requireReady(context); this.context = context; this.sink = sink; unsubscribeData=ProcessDataSignals.subscribe(context.getPackageName(),topic->main.post(()->{if(!closed&&!headlessWork)try{sink.complete(new JSONObject().put("t","sql.changed").put("topic",topic).toString());}catch(Exception ignored){}})); textBitmapStats=memoryEnabled(context)?new PodTextRasterServices.Stats():null; device = new PodDeviceServices(context); PodStartupTrace.stage("servicesDevice",step);step=PodStartupTrace.now(); media = new PodMediaServices(context, event -> { if (!closed) sink.complete(event); }); PodStartupTrace.stage("servicesAudio",step);step=PodStartupTrace.now(); videoDecoderBudget=new PodVideoDecoderBudget(PodVideoSessionServices.MAX_SESSIONS); mediaPrefixCache=new PodMediaPrefixCache(new java.io.File(context.getCacheDir(),"podjs-media-prefix"));mediaPrefixCache.frameRoot=PodTakeover.filesRoot(context);mediaPrefixCache.batteryPercent=()->{android.os.BatteryManager b=(android.os.BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);return b==null?-1:b.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);};mediaPrefixCache.setResultSink(event->main.post(()->{if(!closed)sink.complete(event.toString());})); PodStartupTrace.stage("servicesCache",step);step=PodStartupTrace.now();video = new PodVideoServices(context,videoDecoderBudget,mediaPrefixCache); videoSessions=new PodVideoSessionServices(context,videoDecoderBudget,mediaPrefixCache); PodStartupTrace.stage("servicesVideo",step);step=PodStartupTrace.now(); mediaSurfaces = new PodMediaSurfaceServices(context); inlineText = new PodInlineTextInput(context,event->{if(!closed)sink.complete(event);}); images = new PodImageServices(context); http = new PodHttpServices(context); byteStreams = new PodByteStreamServices(context); webSockets = new PodWebSocketServices(context,byteStreams); discovery = new PodDiscoveryServices(context); bleDiscovery = new PodBleDiscoveryServices(context); provisioning=new PodBleProvisionServices(context); provisionPermission=new PodBluetoothPermission(context,0x5056,PodBlePermissions.required(android.os.Build.VERSION.SDK_INT,false,true)); PodStartupTrace.stage("servicesIo",step);step=PodStartupTrace.now(); networks = new PodNetworkServices(context,event->{if(!closed)sink.complete(event);}); browser = new PodBrowserServices(context); browserAuth = new PodBrowserAuthServices(context); siteChallenge = new PodSiteChallengeServices(context);  PodStartupTrace.stage("servicesNetwork",step);step=PodStartupTrace.now();acoustic=new PodAcousticServices(context);guidedWifi=new PodGuidedWifiServices(context,acoustic); PodStartupTrace.stage("servicesAcoustic",step);step=PodStartupTrace.now();
        acousticPermission=new PodBluetoothPermission(context,0x5045,new String[]{android.Manifest.permission.RECORD_AUDIO});
        guidedPermission=new PodBluetoothPermission(context,0x5046,android.os.Build.VERSION.SDK_INT>=33?new String[]{android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.NEARBY_WIFI_DEVICES}:new String[]{android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.ACCESS_FINE_LOCATION});
        notificationPermission=new PodNotificationPermission(context); bluetoothPermission=new PodBluetoothPermission(context); discoveryPermission=new PodBluetoothPermission(context,0x5044,PodBleDiscoveryServices.permissions()); location=new PodLocationServices(context); }
    void notificationPermissionResult(int code,int[] results){notificationPermission.result(code,results);acousticPermission.result(code);guidedPermission.result(code);location.permissionResult(code);bluetoothPermission.result(code); discoveryPermission.result(code); provisionPermission.result(code);}
    PodServices(Context context, Sink sink, PodBackgroundServices approvedBackground) {
        this(context,sink); background=approvedBackground;
    }
    /** Host-only injection, ordered before later requests on the same worker.
     * This does not enable native capability/preflight declarations. */
    void attachApprovedSync(PodSyncServices approvedSync) {
        worker.execute(() -> { if (!closed) sync = approvedSync; });
    }
    void attachBenchmark(android.view.View view){
        java.lang.ref.WeakReference<PodServices> owner=new java.lang.ref.WeakReference<>(this);
        PodBenchmark.attach(view,seq->{PodServices current=owner.get();if(current!=null&&!current.closed)current.mediaPrefixCache.logFirstCardStats(seq);});
    }
    void videoPagerInput(android.view.MotionEvent event) { videoSessions.pagerInput(event); }
    void attachVideo(android.view.ViewGroup parent) { video.attachToParent(parent); videoSessions.attachToParent(parent);mediaPrefixCache.startupPreview=new PodMediaStartupPreview(context,main,mediaPrefixCache);mediaPrefixCache.startupPreview.attach(parent); }
    void attachMediaSurfaces(android.view.ViewGroup parent,PodMediaSurfaceServices.TextureSink sink){mediaSurfaces.attach(parent,sink);}
    void attachInlineText(android.view.ViewGroup parent){inlineText.attach(parent);}
    boolean handleInlineCrownScroll(android.view.MotionEvent event){return inlineText.handleCrownScroll(event);}
    /** Called only after native package validation and boot succeeded. Ordered
     * before later service requests on the same IO executor. */
    void approveInstalledBackground(String target) {
        worker.execute(() -> {
            if (closed) return;
            try {
                PodBackgroundServices next=new PodBackgroundServices(context,PodBackgroundPackage.installed(context,target));
                if (background!=null) background.close();
                background=next;
            } catch (Exception error) {
                if (background!=null) background.close();
                background=null;
                android.util.Log.e("PodJS","Installed background package rejected",error);
            }
        });
    }
    void pauseVideoForLifecycle() { video.pauseForLifecycle(); videoSessions.lifecycle(false); }
    void dispatch(JSONObject command) throws Exception {
        int id = command.getInt("id");
        if ("service.cancel".equals(command.getString("t"))) { cancel(id); return; }
        if (closed) return;
        if (command.optInt("version") != 1) { failure(id,"unsupported","Unsupported service version"); return; }
        if (id <= 0 || requests.containsKey(id)) { failure(id,"invalid_argument","Invalid request id"); return; }
        if (requests.size() >= 64) { failure(id,"busy","Too many requests"); return; }
        String method = command.getString("method");
        JSONObject args = command.getJSONObject("args");
        Request request = new Request();
        request.method = method;
        requests.put(id, request);
        if(method.startsWith("acoustic.")) {
            try {acoustic.requireDebug();}catch(Exception error){requests.remove(id,request);failure(id,"permission_denied",error.getMessage());return;}
            if("acoustic.requestPermission".equals(method)) {
                main.post(()->{if(closed||!requests.containsKey(id))return;
                    try{acoustic.requireActive();}catch(Exception error){if(requests.remove(id,request))failure(id,"permission_denied",error.getMessage());return;}
                    (args.optBoolean("guided")?guidedPermission:acousticPermission).request(id,new PodBluetoothPermission.Callback(){
                        public void complete(String state){if(requests.remove(id,request))reply(id,true,state,null,null);}
                        public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
                    });
                });return;
            }
            request.future=acousticWorkers.submit(()->{
                try {request.cancellation.throwIfCanceled();Object value=method.startsWith("acoustic.wifi.")?guidedWifi.execute(method,args):acoustic.execute(method,args);
                    if(requests.remove(id,request))reply(id,true,value,null,null);
                    else if(value instanceof JSONObject && ((JSONObject)value).has("session")){
                        long session=((JSONObject)value).getLong("session");
                        if(method.equals("acoustic.wifi.connect"))guidedWifi.stop(session);
                        else acoustic.execute("acoustic.pcm.stop",new JSONObject().put("session",session));
                    }
                }catch(Exception error){if(requests.remove(id,request))failure(id,error instanceof SecurityException?"permission_denied":error instanceof IllegalArgumentException?"invalid_argument":"acoustic_error",error.getMessage());}
            });return;
        }
        if("location.current".equals(method)) {
            main.post(()->{
                if(closed || !requests.containsKey(id))return;
                location.current(new PodLocationServices.Callback(){
                    public void complete(JSONObject value){if(requests.remove(id,request))reply(id,true,value,null,null);}
                    public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
                });
            });
            return;
        }
        if("rfcomm.requestPermission".equals(method)||"discovery.requestPermission".equals(method)||"provision.requestPermission".equals(method)) {
            main.post(()->{
                if(closed || !requests.containsKey(id))return;
                ("provision.requestPermission".equals(method)?provisionPermission:"discovery.requestPermission".equals(method)?discoveryPermission:bluetoothPermission).request(id,new PodBluetoothPermission.Callback(){
                    public void complete(String state){if(requests.remove(id,request))reply(id,true,state,null,null);}
                    public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
                });
            });
            return;
        }
        if("notifications.requestPermission".equals(method)) {
            main.post(() -> {
                if(closed || !requests.containsKey(id))return;
                notificationPermission.request(new PodNotificationPermission.Callback(){
                    public void complete(String state){if(requests.remove(id,request))reply(id,true,state,null,null);}
                    public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
                });
            });
            return;
        }
        if (method.startsWith("audio.") || method.startsWith("tts.")) {
            media.dispatch(method, args, new PodMediaServices.Callback() {
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            });
            return;
        }
        if(method.startsWith("display.")){
            main.post(()->{
                if(closed||!requests.containsKey(id))return;
                try{
                    if(screen==null)screen=new PodScreenServices(context,displayResumed,value->{try{if(!closed)sink.complete(new JSONObject().put("t","display.state").put("value",value).toString());}catch(Exception error){android.util.Log.w("PodJS","Display event failed",error);}});
                    JSONObject value=screen.execute(method,args);if(requests.remove(id,request))reply(id,true,value,null,null);
                }catch(Exception error){if(requests.remove(id,request))failure(id,error instanceof IllegalArgumentException?"invalid_argument":"display_error",error.getMessage());}
            });return;
        }
        if ("text.environment".equals(method)) {
            main.post(() -> {
                if (closed || !requests.containsKey(id)) return;
                try {
                    JSONObject value = PodTextEnvironmentServices.read(context, args);
                    if (requests.remove(id, request)) reply(id, true, value, null, null);
                } catch (Exception error) {
                    if (requests.remove(id, request)) failure(id,
                            error instanceof IllegalArgumentException ? "invalid_argument" : "text_environment_error", error.getMessage());
                }
            });
            return;
        }
        if("runtime.afterPaint".equals(method)){
            main.post(()->{
                if(closed||!requests.containsKey(id))return;
                java.util.function.Consumer<Boolean> finish=committed->{
                    if(requests.remove(id,request))try{reply(id,true,new JSONObject().put("committed",committed),null,null);}catch(Exception error){failure(id,"paint_error","Paint acknowledgement failed");}
                };
                if(paintAwaiter==null)finish.accept(false);else request.paintCancel=paintAwaiter.await(finish);
            });return;
        }
        if ("runtime.delay".equals(method)) {
            long delay = args.optLong("milliseconds", -1);
            if(delay < 1 || delay > 120000) { requests.remove(id,request); failure(id,"invalid_argument","Timer delay must be 1..120000ms"); return; }
            request.timer = () -> { if(requests.remove(id,request)) reply(id,true,JSONObject.NULL,null,null); };
            main.postDelayed(request.timer,delay);
            return;
        }
        if(method.startsWith("image.surface.")||method.startsWith("video.surface.")) {
            mediaSurfaces.dispatch(method,args,new PodMediaSurfaceServices.Callback(){
                public void complete(JSONObject value){if(requests.remove(id,request))reply(id,true,value,null,null);}
                public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
            });return;
        }
        if (method.startsWith("video.session.")) {
            request.videoSessionId=args.optString("sessionId",null);
            videoSessions.dispatch(method,args,new PodVideoSessionServices.Callback(){
                public void volumeChanged(JSONObject value) { try { if(!closed)sink.complete(new JSONObject().put("t","video.audioChanged").put("value",value).toString()); } catch(Exception ignored) {} }
                public void firstFrame(JSONObject value) { try { if(!closed)sink.complete(new JSONObject().put("t","video.firstFrame").put("value",value).toString()); } catch(Exception error) { android.util.Log.w("PodJS","Video first frame event unavailable"); } }
                public void complete(JSONObject value){if(requests.remove(id,request))reply(id,true,value,null,null);}
                public void fail(String code,String message){if(requests.remove(id,request))failure(id,code,message);}
            });
            return;
        }
        if (method.equals("video.cache.local.present")) {
            streamWorkers.execute(()->{try{mediaPrefixCache.startupPreview.present(args,()->!closed&&requests.get(id)==request,value->{if(requests.remove(id,request))reply(id,true,value,null,null);});}catch(Exception error){if(requests.remove(id,request))failure(id,"media_preview_error","Stored preview unavailable");}});return;
        }
        if (method.equals("video.cache.local.hide")) {main.post(()->{if(mediaPrefixCache.startupPreview!=null)mediaPrefixCache.startupPreview.hide(args.optLong("token"));if(requests.remove(id,request))reply(id,true,new JSONObject(),null,null);});return;}
        if (method.startsWith("video.cache.")) {
            streamWorkers.execute(()->{try{JSONObject result=mediaPrefixCache.execute(method,args);if(requests.remove(id,request))reply(id,true,result,null,null);}catch(Exception error){if(requests.remove(id,request))failure(id,mediaPrefixCache.trafficStopped()?"media_traffic_stopped":"media_cache_error","Media prefix operation failed");}});return;
        }
        if (method.startsWith("video.")) {
            video.dispatch(method, args, new PodVideoServices.Callback() {
                public void volumeChanged(JSONObject value) { try { if(!closed)sink.complete(new JSONObject().put("t","video.audioChanged").put("value",value).toString()); } catch(Exception ignored) {} }
                public void firstFrame(JSONObject value) { try { if(!closed)sink.complete(new JSONObject().put("t","video.firstFrame").put("value",value).toString()); } catch(Exception error) { android.util.Log.w("PodJS","Video first frame event unavailable"); } }
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            });
            return;
        }
        if ("browser.challengeCookies".equals(method) || "browser.acquireChallengeCookies".equals(method)) {
            request.challengeCallback = new PodSiteChallengeServices.Callback() {
                public boolean isActive() { return !closed && requests.get(id) == request; }
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            };
            siteChallenge.dispatch(args,"browser.acquireChallengeCookies".equals(method),request.challengeCallback);
            return;
        }
        if ("browser.authorize".equals(method) || "browser.authorizeWithStatus".equals(method)) {
            request.authCallback = new PodBrowserAuthServices.Callback() {
                public boolean isActive() { return !closed && requests.get(id) == request; }
                public void status(JSONObject value) {
                    if (!isActive()) return;
                    try {
                        String event = new JSONObject().put("t", "browser.auth.status").put("id", id).put("value", value).toString();
                        // Same main-thread queue as terminal replies: preserve status-before-result order.
                        main.post(() -> { if (!closed) sink.complete(event); });
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            };
            browserAuth.authorize(args, request.authCallback, "browser.authorizeWithStatus".equals(method));
            return;
        }
        if (method.startsWith("image.")) {
            request.future = imageWorkers.submit(() -> { try { JSONObject value=images.execute(method,args); if(requests.remove(id,request)) reply(id,true,value,null,null); else images.discard(value); } catch(Exception error) { if(requests.remove(id,request)) failure(id,error instanceof SecurityException?"permission_denied":error instanceof IllegalArgumentException?"invalid_argument":"image_error",error.getMessage()); } });
            return;
        }
        if ("debug.memoryStats".equals(method)) {
            main.post(()->{
                if(closed||!requests.containsKey(id))return;
                try {
                    request.cancellation.throwIfCanceled();
                    collectMemoryStats(value->{if(requests.remove(id,request)){if(value==null)failure(id,"memory_error","Memory sample unavailable");else reply(id,true,value,null,null);}});
                }catch(Exception error){if(requests.remove(id,request))failure(id,error instanceof SecurityException?"permission_denied":"memory_error",error.getMessage());}
            });
            return;
        }
        if (method.startsWith("debug.performance.")) {
            main.post(() -> {
                if (closed || !requests.remove(id, request)) return;
                try { request.cancellation.throwIfCanceled();reply(id,true,debugPerformance.execute(context,method,args),null,null); }
                catch(Exception error){failure(id,error instanceof SecurityException?"permission_denied":"performance_error",error.getMessage());}
            });
            return;
        }
        if ("takeover.status".equals(method)) {
            if (requests.remove(id,request)) reply(id,true,PodTakeover.status(context),null,null);
            return;
        }
        if (method.startsWith("debug.external.")) {
            if (!requests.remove(id,request)) return;
            try {
                if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0) throw new SecurityException();
                Class<?> bridge=Class.forName("dev.podjs.debug.PodDebugExternal");
                if(debugExternalSink==null)debugExternalSink=value->main.post(()->{if(!closed)sink.complete(value);});
                Object value=bridge.getMethod("execute",Context.class,String.class,JSONObject.class,java.util.function.Consumer.class).invoke(null,context,method,args,debugExternalSink);
                reply(id,true,value,null,null);
            } catch(Exception error) { failure(id,"unavailable","Debug external interface unavailable"); }
            return;
        }
        if ("battery.percent".equals(method)) {
            try {
                android.os.BatteryManager manager = (android.os.BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);
                int level = manager == null ? -1 : manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if(requests.remove(id,request))reply(id,true,level >= 0 && level <= 100 ? level : JSONObject.NULL,null,null);
            } catch(Exception error) { if(requests.remove(id,request))reply(id,true,JSONObject.NULL,null,null); }
            return;
        }
        if ("splash.hold".equals(method)||"splash.release".equals(method)) {
            Runnable operation=()->{
                if(!requests.remove(id,request))return;
                try {
                    String owner=args.getString("owner");if(!owner.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid splash owner");
                    PodSplash splash=PodSplash.of(context);
                    if(splash==null){failure(id,"unsupported","No configured Activity splash");return;}
                    if("splash.hold".equals(method))splash.hold(owner);else splash.release(owner);
                    reply(id,true,JSONObject.NULL,null,null);
                }catch(Exception error){failure(id,"invalid_argument","Invalid splash operation");}
            };
            if(Looper.myLooper()==Looper.getMainLooper())operation.run();else main.post(operation);return;
        }
        if ("app.buildInfo".equals(method)) {
            if (!requests.remove(id, request)) return;
            try {
                request.cancellation.throwIfCanceled();
                boolean debug = (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
                reply(id, true, new JSONObject().put("debugBuild", debug).put("buildIdentity", info.lastUpdateTime+":"+info.versionCode), null, null);
            } catch (Exception error) { failure(id, "lifecycle_error", error.getMessage()); }
            return;
        }
        if ("app.activityState.read".equals(method) || "app.activityState.write".equals(method)) {
            if (!requests.remove(id, request)) return;
            try {
                request.cancellation.throwIfCanceled();
                if ("app.activityState.write".equals(method)) {
                    activityState.write(args.optJSONObject("saved"), args.optJSONObject("retained"));
                    reply(id, true, JSONObject.NULL, null, null);
                } else reply(id, true, activityState.read(), null, null);
            } catch (Exception error) { failure(id, "lifecycle_error", error.getMessage()); }
            return;
        }
        if ("app.finishTask".equals(method)) {
            main.post(() -> {
                if (closed || !requests.remove(id, request)) return;
                try {
                    request.cancellation.throwIfCanceled();
                    Context owner = context;
                    java.util.Set<Context> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    while (!(owner instanceof android.app.Activity) && owner instanceof android.content.ContextWrapper && seen.add(owner)) owner = ((android.content.ContextWrapper) owner).getBaseContext();
                    if (!(owner instanceof android.app.Activity)) throw new IllegalStateException("Application activity unavailable");
                    ((android.app.Activity) owner).finishAffinity();
                    reply(id, true, JSONObject.NULL, null, null);
                } catch (Exception error) { failure(id, "lifecycle_error", error.getMessage()); }
            });
            return;
        }
        if ("ui.toast".equals(method)) {
            String message = args.optString("message", "");
            main.post(() -> {
                if (closed || !requests.remove(id, request)) return;
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
                reply(id, true, JSONObject.NULL, null, null);
            });
            return;
        }
        if ("input.text.dismiss".equals(method)) {
            main.post(() -> {
                if(closed || !requests.remove(id,request))return;
                try {
                    String key=PodModalState.key(args.opt("restorationKey"));
                    modalState.clear(key);
                    if(key.equals(modalKey) && modalDialog!=null)modalDialog.dismiss();
                    reply(id,true,JSONObject.NULL,null,null);
                } catch (IllegalArgumentException invalid) { failure(id,"invalid_argument","Invalid input restoration key"); }
            });
            return;
        }
        if ("input.text.pending".equals(method)) {
            try { String key=PodModalState.key(args.opt("restorationKey")); if(requests.remove(id,request)) reply(id,true,modalState.pending(key)!=null,null,null); }
            catch (IllegalArgumentException invalid) { requests.remove(id,request); failure(id,"invalid_argument","Invalid input restoration key"); }
            return;
        }
        if ("input.text".equals(method)) { prompt(id, request, args); return; }
        if ("input.inline.open".equals(method) || "input.inline.close".equals(method) || "input.inline.set".equals(method)) {
            main.post(()->{
                if(closed || !requests.remove(id,request))return;
                try {
                    if("input.inline.open".equals(method)) {inlineText.open(id,args);reply(id,true,id,null,null);}
                    else if("input.inline.close".equals(method)) {inlineText.close(args.getInt("session"));reply(id,true,JSONObject.NULL,null,null);}
                    else {inlineText.setValue(args.getInt("session"),args.getString("value"));reply(id,true,JSONObject.NULL,null,null);}
                } catch(Exception error) {failure(id,error instanceof IllegalArgumentException?"invalid_argument":"unavailable",error.getMessage());}
            });
            return;
        }
        if ("input.time".equals(method)) { promptTime(id, request, args); return; }
        if ("browser.availability".equals(method) || "browser.clearSiteData".equals(method)) {
            main.post(() -> {
                if (closed || !requests.containsKey(id)) return;
                try {
                    request.cancellation.throwIfCanceled();
                    PodWebViewServices web = new PodWebViewServices(context);
                    Object value = "browser.availability".equals(method) ? web.availability() : web.clearSiteData(args);
                    if (requests.remove(id, request)) reply(id, true, value, null, null);
                } catch (Exception error) {
                    if (requests.remove(id, request)) failure(id, error instanceof IllegalArgumentException ? "invalid_argument" : "unavailable", error.getMessage());
                }
            });
            return;
        }
        if ("browser.updateTheme".equals(method)) {
            main.post(() -> { try {
                request.cancellation.throwIfCanceled();
                JSONObject value = new JSONObject().put("updated", browser.updateTheme(args));
                if (requests.remove(id, request)) reply(id, true, value, null, null);
            } catch (Exception error) { if (requests.remove(id, request)) failure(id,"invalid_argument",error.getMessage()); } });
            return;
        }
        if ("browser.open".equals(method) || "browser.openInApp".equals(method)) { openBrowser(id, request, args); return; }
        if ("share.environment".equals(method) || "share.text".equals(method)) {
            main.post(() -> {
                if (closed || !requests.containsKey(id)) return;
                try {
                    request.cancellation.throwIfCanceled();PodSharingServices sharing=new PodSharingServices(context);
                    Object value;if ("share.environment".equals(method)) value=sharing.environment();else {sharing.text(args);value=JSONObject.NULL;}
                    if(requests.remove(id,request))reply(id,true,value,null,null);
                }catch(Exception error){if(requests.remove(id,request))failure(id,error instanceof IllegalArgumentException?"invalid_argument":"unavailable",error.getMessage());}
            });
            return;
        }
        args.put("requestId", id);
        // Only shared device/secure work uses this gate. Network transfers and
        // main-thread dialogs never hold an admission ticket while awaiting IO.
        if (!("secure.get".equals(method)&&!headlessWork&&device.hasWarmSecureStore()) && !method.endsWith(".release") && (method.startsWith("secure.") || method.startsWith("crypto.") || method.startsWith("file.") || "log.append".equals(method) || "uri.parse".equals(method)))
            request.priority = HostWorkPriority.DEVICE.reserve(headlessWork);
        final long queuedAt=System.nanoTime();
        try { request.future = (method.startsWith("provision.") || method.startsWith("rfcomm.") || method.startsWith("tcp.") || method.startsWith("stream.") || method.startsWith("websocket.") || method.startsWith("discovery.") ? streamWorkers : method.startsWith("http.") ? transfers : method.startsWith("sql.") ? sqlWorker : method.startsWith("text.") ? textWorker : method.startsWith("secure.") ? secureWorker : worker).submit(() -> {
            long workAt=0,cpuAt=android.os.Debug.threadCpuTimeNanos();boolean workSuccess=false;
            try {
                if (request.priority != null) request.priority.enter();
                else if(method.startsWith("http.")&&args.optBoolean("background",false)){
                    try(HostWorkPriority.Ticket admission=HostWorkPriority.DEVICE.reserve(true)){admission.enter(()->!request.cancellation.isCanceled());}
                }
                else if(headlessWork&&!method.endsWith(".release"))HostWorkPriority.DEVICE.awaitBackground();
                request.cancellation.throwIfCanceled();
                workAt=System.nanoTime();
                Object value;
                if (method.startsWith("rfcomm.") || method.startsWith("tcp.") || method.startsWith("stream.") || method.startsWith("websocket.")) {
                    value=method.startsWith("websocket.")?webSockets.execute(method,args,request.cancellation):byteStreams.execute(method,args,request.cancellation);
                } else if (method.startsWith("provision.")) {
                    if(!displayResumed)throw new IllegalStateException("配对需要保持前台");
                    request.cancellation.setOnCancelListener(()->{if("provision.start".equals(method))provisioning.cancelStart(Integer.toString(id));else provisioning.cancel(args.optString("sessionId"));});
                    value=provisioning.execute(method,args,request.cancellation);
                } else if (method.startsWith("discovery.")) {
                    value = method.startsWith("discovery.network")?networks.execute(method,args,request.cancellation):"discovery.mdns".equals(method)?discovery.execute(method,args,request.cancellation):bleDiscovery.execute(method,args,request.cancellation);
                } else if ("text.raster".equals(method)) {
                    value = PodTextRasterServices.raster(context,args,request.cancellation,paragraphLayouts,textBitmapStats);
                } else if ("text.layout".equals(method)) {
                    value = PodTextLayoutServices.layout(context,args, request.cancellation,paragraphLayouts);
                } else if ("text.layoutBatch".equals(method)) {
                    value = PodTextLayoutServices.layoutBatch(context,args, request.cancellation,paragraphLayouts);
                } else if ("text.measure".equals(method)) {
                    value = PodTextLayoutServices.measure(context,args,request.cancellation);
                } else if(method.startsWith("sync.")) {
                    if (sync == null) throw new UnsupportedOperationException("Sync host owner unavailable");
                    value = sync.execute(method,args,request.cancellation);
                } else if(method.startsWith("notifications.")) {
                    switch(method) {
                        case "notifications.status": value=notificationPermission.status();break;
                        case "notifications.schedule": notifications().schedule(args.getJSONObject("notification"));value=JSONObject.NULL;break;
                        case "notifications.cancel": notifications().cancel(args.getString("id"));value=JSONObject.NULL;break;
                        case "notifications.listPending": value=notifications().listPending();break;
                        default: throw new UnsupportedOperationException("Remote notification provider unavailable");
                    }
                } else if (method.startsWith("cloud.")) {
                    if(cloudWork==null)cloudWork=new PodCloudWork(context);
                    value=cloudWork.execute(method,args);
                } else if (method.startsWith("background.")) {
                    if (background==null) throw new SecurityException("Background scheduling is not authorized");
                    value=background.execute(method,args);
                } else value = method.startsWith("sql.") ? sql(method, args, request.cancellation) : method.startsWith("http.") ? http.execute(method,args) : device.execute(method, args);
                workSuccess=true;
                if (requests.remove(id, request)) reply(id, true, value, null, null);
                else if ("provision.start".equals(method) && value instanceof JSONObject) provisioning.cancel(((JSONObject)value).optString("sessionId"));
                else if ("discovery.networkSubscribe".equals(method)) networks.discard(args.getString("subscriptionId"));
                else if ("websocket.connect".equals(method) && value instanceof JSONObject) webSockets.discard((JSONObject)value);
                else if (("rfcomm.listen".equals(method) || "rfcomm.accept".equals(method) || "tcp.listen".equals(method) || "tcp.accept".equals(method)) && value instanceof JSONObject) byteStreams.discard((JSONObject)value);
                else if ("file.openText".equals(method) && value instanceof JSONObject) device.discardTextReader((JSONObject)value);
                else if (("crypto.openSealedParts".equals(method) || "crypto.largeAesGcmEncrypt".equals(method) || "crypto.largeAesGcmDecrypt".equals(method) || "crypto.gzip".equals(method) || "crypto.gunzip".equals(method) || "file.htmlText".equals(method) || "file.createTemp".equals(method) || "file.gzip".equals(method) || "file.gunzip".equals(method)) && value instanceof JSONObject) device.discardTemporary((JSONObject)value);
                else if ("text.raster".equals(method)) PodTextRasterServices.discard(context,value);
                else if ("http.download".equals(method) && value instanceof JSONObject) http.discard((JSONObject)value);
            } catch (Exception error) {
                if (requests.remove(id, request)) failure(id,
                    error instanceof SecureStoreLimits.Failure ? ((SecureStoreLimits.Failure)error).code : error instanceof SecurityException ? "permission_denied" : error instanceof ProvisioningSession.Failure ? "provision_"+((ProvisioningSession.Failure)error).reason : error instanceof UnsupportedOperationException ? "unsupported" : error instanceof IllegalArgumentException ? "invalid_argument" : "host_error",
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                if (request.priority != null) request.priority.close();
                long ended=System.nanoTime(),chars=0;
                if(workAt==0)workAt=ended;
                for(String field:new String[]{"dataBase64","ciphertextBase64","value"}){Object v=args.opt(field);if(v instanceof String)chars+=((String)v).length();}
                JSONArray statements=args.optJSONArray("statements");
                if(method.startsWith("sql."))workStats.sqlSite(args.optInt("diagnosticSite",0),ended-workAt,android.os.Debug.threadCpuTimeNanos()-cpuAt,statements==null?1:statements.length());
                workStats.add(method,workAt-queuedAt,ended-workAt,android.os.Debug.threadCpuTimeNanos()-cpuAt,chars,statements==null?1:statements.length(),workSuccess);
                workStats.emit(ended,closed,headlessWork,line->android.util.Log.i("PodJSService",line));
            }
        }); } catch (java.util.concurrent.RejectedExecutionException closedWorker) {
            if (request.priority != null) request.priority.cancel();
            if (requests.remove(id,request)) failure(id,"service_closed","Host service worker is closed");
        }
    }
    private void openBrowser(int id, Request request, JSONObject args) {
        main.post(() -> {
            if (closed || !requests.containsKey(id)) return;
            try { request.cancellation.throwIfCanceled(); if ("browser.openInApp".equals(request.method)) browser.openInApp(args); else browser.open(args); if (requests.remove(id, request)) reply(id, true, JSONObject.NULL, null, null); }
            catch (Exception error) { if (requests.remove(id, request)) failure(id, error instanceof PodBrowserServices.BrowserFailure ? ((PodBrowserServices.BrowserFailure)error).code : error instanceof IllegalArgumentException ? "invalid_argument" : error instanceof IllegalStateException && "no_browser_handler".equals(error.getMessage()) ? "no_browser_handler" : "unavailable", error.getMessage()); }
        });
    }
    private void promptTime(int id, Request request, JSONObject args) {
        Object raw=args.opt("minutes");
        if(!(raw instanceof Number) || !Double.isFinite(((Number)raw).doubleValue()) || ((Number)raw).doubleValue()!=((Number)raw).intValue() || ((Number)raw).intValue()<0 || ((Number)raw).intValue()>1439) {
            requests.remove(id,request);failure(id,"invalid_argument","Time must be integer minutes from 0 to 1439");return;
        }
        int minutes=((Number)raw).intValue();
        main.post(()->{
            if(closed || !requests.containsKey(id))return;
            if(!(context instanceof Activity) || ((Activity)context).isFinishing() || ((Activity)context).isDestroyed()) {
                if(requests.remove(id,request))failure(id,"unavailable","Time input requires an active Activity");return;
            }
            for(Request other:requests.values()) {
                if(other!=request && other.dialog!=null && other.dialog.isShowing()) {
                    if(requests.remove(id,request))failure(id,"busy","Another input dialog is active");return;
                }
            }
            try {
                android.app.TimePickerDialog dialog=new android.app.TimePickerDialog(context, (view,hour,minute)->{
                    if(requests.remove(id,request))reply(id,true,hour*60+minute,null,null);
                },minutes/60,minutes%60,true);
                dialog.setOnDismissListener(view->{if(requests.remove(id,request))reply(id,true,JSONObject.NULL,null,null);});
                request.dialog=dialog;
                dialog.show();
            } catch(RuntimeException error) {
                if(requests.remove(id,request))failure(id,"unavailable","Cannot show time input");
            }
        });
    }
    /** Shared by the actual prompt and JVM Android tests; ordinary input is unchanged. */
    static void configureSensitiveNumber(EditText input,JSONObject args,String key) {
        // One-time approval codes: digits only, bounded, never restorable or offered to Autofill.
        if("number".equals(args.optString("inputType")) && args.optBoolean("sensitive",false)) {
            if(key!=null) throw new IllegalArgumentException("sensitive_input_not_restorable");
            int limit=args.optInt("maxLength",8);
            if(limit<1||limit>32)throw new IllegalArgumentException("invalid_input_limit");
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setSingleLine(true);
            input.setFilters(new android.text.InputFilter[]{android.text.method.DigitsKeyListener.getInstance("0123456789"),new android.text.InputFilter.LengthFilter(limit)});
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
            input.setGravity(android.view.Gravity.CENTER);
            input.setTextSize(22);
        }
    }
    private void prompt(int id, Request request, JSONObject args) {
        if (!(context instanceof Activity)) { requests.remove(id); failure(id,"unavailable","Text input requires an Activity"); return; }
        main.post(() -> {
            if(closed || !requests.containsKey(id)) return;
            try {
                if(modalInput != null) throw new PodBrowserServices.BrowserFailure("busy","Text input is already open");
                String key=args.has("restorationKey")?PodModalState.key(args.opt("restorationKey")):null;
                String title=args.optString("title","");
                JSONObject draft=key==null?null:modalState.pending(key);
                if(draft!=null && !title.equals(draft.optString("title"))) draft=null;
                EditText input = new EditText(context);
                input.setSingleLine(false);
                configureSensitiveNumber(input,args,key);
                input.setText(draft==null?args.optString("value", ""):draft.optString("text"));
                if(draft!=null) input.setSelection(draft.optInt("start",0),draft.optInt("end",0));
                Runnable finished=()->{
                    if(modalInput==input){modalInput=null;modalDialog=null;modalKey=null;modalTitle=null;if(key!=null)modalState.clear(key);}
                };
                AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d,w) -> {
                        boolean completed=requests.remove(id,request);String value=input.getText().toString();finished.run();d.dismiss();if(completed)reply(id,true,value,null,null);
                    })
                    .setNegativeButton(android.R.string.cancel,(d,w) -> {
                        boolean completed=requests.remove(id,request);finished.run();d.dismiss();if(completed)reply(id,true,JSONObject.NULL,null,null);
                    }).create();
                dialog.setOnCancelListener(d -> { boolean completed=requests.remove(id,request);finished.run();d.dismiss();if(completed)reply(id,true,JSONObject.NULL,null,null); });
                dialog.setOnDismissListener(d -> {
                    finished.run();
                    if(requests.remove(id,request))reply(id,true,JSONObject.NULL,null,null);
                });
                request.dialog = dialog;
                modalInput=input;modalDialog=dialog;modalKey=key;modalTitle=title;
                dialog.show();input.requestFocus();
            } catch (PodBrowserServices.BrowserFailure busy) { requests.remove(id,request); failure(id,busy.code,busy.getMessage()); }
            catch (IllegalArgumentException invalid) { requests.remove(id,request); failure(id,"invalid_argument","Invalid input restoration key"); }
            catch (RuntimeException unavailable) { if(modalDialog!=null)modalDialog.dismiss();modalInput=null;modalDialog=null;modalKey=null;modalTitle=null;requests.remove(id,request);failure(id,"unavailable","Cannot show text input"); }
        });
    }
    private SQLiteDatabase db() {
        if (database == null) {
            java.io.File path = PodTakeover.database(context);
            database = path.equals(context.getDatabasePath("podjs.sqlite"))
                ? context.openOrCreateDatabase("podjs.sqlite", Context.MODE_PRIVATE, null)
                : SQLiteDatabase.openOrCreateDatabase(path, null);
            database.setForeignKeyConstraintsEnabled(true);
            // Sync transactions touch more than the default 25 distinct SQL
            // shapes. Keep SQLite's bounded per-connection prepared cache warm.
            database.setMaxSqlCacheSize(100);
        }
        return database;
    }
    private Object sql(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        try(AutoCloseable foreground=PodActiveValidationAndroid.workload(context).foreground()) {
            return sqlForeground(method,args,cancellation);
        }
    }
    private Object sqlForeground(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        if ("sql.notify".equals(method)) {ProcessDataSignals.publish(context.getPackageName(),args.getString("topic"));return JSONObject.NULL;}
        if ("sql.transaction".equals(method)) {
            JSONArray statements = args.getJSONArray("statements");
            if (statements.length() > 2048) throw new IllegalArgumentException("Transaction too large");
            boolean reportChanges = args.optBoolean("reportChanges", false);
            JSONArray changes = new JSONArray();
            db().beginTransaction();
            try {
                for (int i=0;i<statements.length();i++) {
                    cancellation.throwIfCanceled();
                    execute(statements.getJSONObject(i));
                    if (reportChanges) {
                        try (Cursor cursor = db().rawQuery("SELECT changes()", null)) {
                            if (!cursor.moveToFirst()) throw new IllegalStateException("Missing SQLite change count");
                            changes.put(cursor.getLong(0));
                        }
                    }
                }
                cancellation.throwIfCanceled();
                db().setTransactionSuccessful();
            } finally { db().endTransaction(); }
            return reportChanges ? changes : JSONObject.NULL;
        }
        if ("sql.execute".equals(method)) { execute(args); return JSONObject.NULL; }
        if ("sql.gzipJson".equals(method)) return PodSqlGzipJson.project(db(),PodTakeover.filesRoot(context),args,cancellation);
        if (!"sql.query".equals(method)) throw new IllegalArgumentException("Unknown SQL method");
        String sql = args.getString("sql");
        JSONArray params = args.optJSONArray("params");
        JSONArray rows = new JSONArray();
        int total = 0;
        // rawQuery(String[]) coerces numeric parameters to TEXT. Aggregate
        // comparisons such as HAVING MIN(seq)>? then have different semantics
        // from typed writes. Bind the original JSON scalar types to the query.
        try(Cursor cursor = db().rawQueryWithFactory((database, driver, editTable, query) -> {
            if (params != null) for (int i=0;i<params.length();i++) {
                Object value = params.opt(i);
                if (value == null || value == JSONObject.NULL) query.bindNull(i+1);
                else if (value instanceof Float || value instanceof Double) query.bindDouble(i+1,((Number)value).doubleValue());
                else if (value instanceof Number) query.bindLong(i+1,((Number)value).longValue());
                else query.bindString(i+1,String.valueOf(value));
            }
            return new android.database.sqlite.SQLiteCursor(driver, editTable, query);
        }, sql, null, null, cancellation)) {
            while(cursor.moveToNext()) {
                cancellation.throwIfCanceled();
                if(rows.length() >= 1000) throw new IllegalArgumentException("Query exceeds 1000 rows; use LIMIT and pagination");
                JSONObject row = new JSONObject();
                for(int c=0;c<cursor.getColumnCount();c++) {
                    Object value;
                    switch(cursor.getType(c)) {
                        case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                        case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(c); break;
                        case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(c); break;
                        case Cursor.FIELD_TYPE_BLOB: throw new IllegalArgumentException("Use file chunks for binary data");
                        default: value = cursor.getString(c);
                    }
                    row.put(cursor.getColumnName(c), value);
                }
                total += row.toString().length();
                if(total > 512*1024) throw new IllegalArgumentException("Query result too large; use paging");
                rows.put(row);
            }
        }
        return rows;
    }
    private void execute(JSONObject args) throws Exception {
        String sql = args.getString("sql");
        String head = sql.trim().toUpperCase(java.util.Locale.ROOT);
        if(head.startsWith("BEGIN") || head.startsWith("COMMIT") || head.startsWith("ROLLBACK") || head.startsWith("ATTACH"))
            throw new IllegalArgumentException("Use sql.transaction; attached databases are not supported");
        try(SQLiteStatement statement = db().compileStatement(sql)) {
            JSONArray params = args.optJSONArray("params");
            if(params != null) for(int i=0;i<params.length();i++) {
                Object value = params.get(i);
                if(value == JSONObject.NULL) statement.bindNull(i+1);
                else if(value instanceof Float || value instanceof Double) statement.bindDouble(i+1,((Number)value).doubleValue());
                else if(value instanceof Number) statement.bindLong(i+1,((Number)value).longValue());
                else if(value instanceof String) statement.bindString(i+1,(String)value);
                else throw new IllegalArgumentException("SQL parameters must be string, number or null");
            }
            statement.execute();
        }
    }
    private void failure(int id,String code,String message) { reply(id,false,null,code,message); }
    private void reply(int id,boolean ok,Object value,String code,String message) {
        try {
            JSONObject event = new JSONObject().put("t","service.result").put("id",id).put("ok",ok);
            if(ok) event.put("value",value == null ? JSONObject.NULL : value);
            else event.put("code",code).put("message",message);
            // Serialize on the calling (worker) thread; the main thread only hands the
            // finished string to the guest so large replies cannot stall frames.
            final String json = event.toString();
            if (PERF_TRACE && json.length() > 65536) android.util.Log.i("PodJSPerf", "large service reply id=" + id + " chars=" + json.length());
            main.post(() -> { if(!closed) sink.complete(json); });
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private void cancel(int id) {
        Request request = requests.remove(id);
        if(request == null) return;
        request.cancellation.cancel();
        if (request.priority != null) request.priority.cancel();
        if(request.timer != null) main.removeCallbacks(request.timer);
        if(request.paintCancel!=null)request.paintCancel.run();
        if(request.method!=null&&request.method.startsWith("acoustic.")){
            acoustic.cancel();guidedWifi.cancel();main.post(()->{acousticPermission.cancel(id);guidedPermission.cancel(id);});
        }
        http.cancel(id);
        media.cancel(request.method);
        video.cancel(request.method);
        if(request.method!=null&&request.method.startsWith("video.session."))videoSessions.cancel(request.videoSessionId);
        if (request.authCallback != null) browserAuth.cancel(request.authCallback);
        if (request.challengeCallback != null) siteChallenge.cancel(request.challengeCallback);
        if("notifications.requestPermission".equals(request.method))main.post(notificationPermission::cancel);
        if("location.current".equals(request.method))main.post(location::cancel);
        if("rfcomm.requestPermission".equals(request.method))main.post(()->bluetoothPermission.cancel(id));
        if("discovery.requestPermission".equals(request.method))main.post(()->discoveryPermission.cancel(id));
        if("provision.requestPermission".equals(request.method))main.post(()->provisionPermission.cancel(id));
        if(request.future != null) request.future.cancel(true);
        if(request.dialog != null) request.dialog.dismiss();
    }
    @Override public void close() {
        closed=true;unsubscribeData.run();
        if(debugExternalSink!=null)try {Class.forName("dev.podjs.debug.PodDebugExternal").getMethod("close",java.util.function.Consumer.class).invoke(null,debugExternalSink);}catch(Exception ignored){} finally{debugExternalSink=null;}
        acoustic.close();guidedWifi.close();acousticWorkers.shutdownNow();
        main.post(()->{acousticPermission.cancel();guidedPermission.cancel();});
        main.post(notificationPermission::cancel);
        main.post(location::cancel);
        main.post(bluetoothPermission::cancel);
        main.post(provisionPermission::cancel);
        main.post(discoveryPermission::cancel);
        for(Integer id:requests.keySet()) cancel(id);
        main.post(inlineText::closeAll);
        main.post(()->{debugPerformance.close();if(screen!=null){screen.close();screen=null;}});
        media.close();
        main.post(()->{if(mediaPrefixCache.startupPreview!=null)mediaPrefixCache.startupPreview.hide(0);});mediaPrefixCache.close();video.close();
        videoSessions.close();
        mediaSurfaces.close();
        imageWorkers.shutdownNow();
        images.close();
        browserAuth.close();
        siteChallenge.close();
        main.post(browser::close);
        http.close();
        provisioning.close(); networks.close(); bleDiscovery.close(); discovery.close(); webSockets.close(); byteStreams.close();
        streamWorkers.shutdownNow();
        transfers.shutdownNow();
        textWorker.execute(paragraphLayouts::clear);
        textWorker.shutdown();
        sqlWorker.execute(() -> { if(database != null) database.close(); });
        sqlWorker.shutdown();
        worker.execute(() -> {
            sync=null;
            if(installedSync!=null) try { installedSync.close(); } catch(Exception error) {android.util.Log.e("PodJS","Sync owner close failed",error);}
            if(cloudWork!=null)cloudWork.close(); if(background!=null) background.close(); if(notifications!=null)notifications.close(); device.close();
            workStats.emit(System.nanoTime(),true,headlessWork,line->android.util.Log.i("PodJSService",line));
        });
        secureWorker.shutdown();
        worker.shutdown();
    }
}
