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
    interface Sink { void complete(String event); }
    private final Context context;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Request> requests = new ConcurrentHashMap<>();
    private final PodDeviceServices device;
    private final PodMediaServices media;
    private final PodVideoServices video;
    private final PodImageServices images;
    private final PodBrowserAuthServices browserAuth;
    private final PodHttpServices http;
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
                installedSync=PodInstalledSync.open(context,target);
                if(installedSync!=null) { sync=installedSync.services; installedSync.connection.setForeground(syncForeground); }
            } catch(Exception error) { android.util.Log.e("PodJS","Installed sync owner unavailable",error); }
        });
    }
    private PodNotifications notifications;
    private final PodNotificationPermission notificationPermission;
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
    private volatile boolean closed;
    private static final class Request {
        final CancellationSignal cancellation = new CancellationSignal();
        volatile Future<?> future;
        AlertDialog dialog;
        Runnable timer;
        String method;
    }
    PodServices(Context context, Sink sink) { this.context = context; this.sink = sink; device = new PodDeviceServices(context); media = new PodMediaServices(context); video = new PodVideoServices(context); images = new PodImageServices(context); http = new PodHttpServices(context); browser = new PodBrowserServices(context); browserAuth = new PodBrowserAuthServices(context); notificationPermission=new PodNotificationPermission(context); }
    void notificationPermissionResult(int code,int[] results){notificationPermission.result(code,results);}
    PodServices(Context context, Sink sink, PodBackgroundServices approvedBackground) {
        this(context,sink); background=approvedBackground;
    }
    /** Host-only injection, ordered before later requests on the same worker.
     * This does not enable native capability/preflight declarations. */
    void attachApprovedSync(PodSyncServices approvedSync) {
        worker.execute(() -> { if (!closed) sync = approvedSync; });
    }
    void attachVideo(android.view.ViewGroup parent) { video.attachToParent(parent); }
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
    void pauseVideoForLifecycle() { video.pauseForLifecycle(); }
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
        if ("runtime.delay".equals(method)) {
            long delay = args.optLong("milliseconds", -1);
            if(delay < 1 || delay > 120000) { requests.remove(id,request); failure(id,"invalid_argument","Timer delay must be 1..120000ms"); return; }
            request.timer = () -> { if(requests.remove(id,request)) reply(id,true,JSONObject.NULL,null,null); };
            main.postDelayed(request.timer,delay);
            return;
        }
        if (method.startsWith("video.")) {
            video.dispatch(method, args, new PodVideoServices.Callback() {
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            });
            return;
        }
        if ("browser.authorize".equals(method)) {
            browserAuth.authorize(args, new PodBrowserAuthServices.Callback() {
                public void complete(JSONObject value) { if (requests.remove(id,request)) reply(id,true,value,null,null); }
                public void fail(String code,String message) { if (requests.remove(id,request)) failure(id,code,message); }
            });
            return;
        }
        if (method.startsWith("image.")) {
            request.future = worker.submit(() -> { try { Object value=images.execute(method,args); if(requests.remove(id,request)) reply(id,true,value,null,null); } catch(Exception error) { if(requests.remove(id,request)) failure(id,error instanceof SecurityException?"permission_denied":error instanceof IllegalArgumentException?"invalid_argument":"image_error",error.getMessage()); } });
            return;
        }
        if ("input.text".equals(method)) { prompt(id, request, args); return; }
        if ("browser.open".equals(method)) { openBrowser(id, request, args); return; }
        args.put("requestId", id);
        request.future = (method.startsWith("http.") ? transfers : worker).submit(() -> {
            try {
                request.cancellation.throwIfCanceled();
                Object value;
                if(method.startsWith("sync.")) {
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
                } else if (method.startsWith("background.")) {
                    if (background==null) throw new SecurityException("Background scheduling is not authorized");
                    value=background.execute(method,args);
                } else value = method.startsWith("sql.") ? sql(method, args, request.cancellation) : method.startsWith("http.") ? http.execute(method,args) : device.execute(method, args);
                if (requests.remove(id, request)) reply(id, true, value, null, null);
            } catch (Exception error) {
                if (requests.remove(id, request)) failure(id,
                    error instanceof SecurityException ? "permission_denied" : error instanceof UnsupportedOperationException ? "unsupported" : error instanceof IllegalArgumentException ? "invalid_argument" : "host_error",
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }
    private void openBrowser(int id, Request request, JSONObject args) {
        main.post(() -> {
            if (closed || !requests.containsKey(id)) return;
            try { request.cancellation.throwIfCanceled(); browser.open(args); if (requests.remove(id, request)) reply(id, true, JSONObject.NULL, null, null); }
            catch (Exception error) { if (requests.remove(id, request)) failure(id, error instanceof IllegalArgumentException ? "invalid_argument" : error instanceof IllegalStateException && "no_browser_handler".equals(error.getMessage()) ? "no_browser_handler" : "unavailable", error.getMessage()); }
        });
    }
    private void prompt(int id, Request request, JSONObject args) {
        if (!(context instanceof Activity)) { requests.remove(id); failure(id,"unavailable","Text input requires an Activity"); return; }
        EditText input = new EditText(context);
        input.setText(args.optString("value", ""));
        input.setSingleLine(false);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(args.optString("title", ""))
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d,w) -> {
                if (requests.remove(id,request)) reply(id,true,input.getText().toString(),null,null);
            })
            .setNegativeButton(android.R.string.cancel,(d,w) -> {
                if (requests.remove(id,request)) reply(id,true,JSONObject.NULL,null,null);
            }).create();
        dialog.setOnCancelListener(d -> { if(requests.remove(id,request)) reply(id,true,JSONObject.NULL,null,null); });
        request.dialog = dialog;
        dialog.show();
        input.requestFocus();
    }
    private SQLiteDatabase db() {
        if (database == null) {
            database = context.openOrCreateDatabase("podjs.sqlite", Context.MODE_PRIVATE, null);
            database.setForeignKeyConstraintsEnabled(true);
        }
        return database;
    }
    private Object sql(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        if ("sql.transaction".equals(method)) {
            JSONArray statements = args.getJSONArray("statements");
            if (statements.length() > 2048) throw new IllegalArgumentException("Transaction too large");
            db().beginTransaction();
            try {
                for (int i=0;i<statements.length();i++) { cancellation.throwIfCanceled(); execute(statements.getJSONObject(i)); }
                cancellation.throwIfCanceled();
                db().setTransactionSuccessful();
            } finally { db().endTransaction(); }
            return JSONObject.NULL;
        }
        if ("sql.execute".equals(method)) { execute(args); return JSONObject.NULL; }
        if (!"sql.query".equals(method)) throw new IllegalArgumentException("Unknown SQL method");
        String sql = args.getString("sql");
        JSONArray params = args.optJSONArray("params");
        String[] values = new String[params == null ? 0 : params.length()];
        for(int i=0;i<values.length;i++) values[i] = params.isNull(i) ? null : String.valueOf(params.get(i));
        JSONArray rows = new JSONArray();
        int total = 0;
        try(Cursor cursor = db().rawQuery(sql, values, cancellation)) {
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
            main.post(() -> { if(!closed) sink.complete(event.toString()); });
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private void cancel(int id) {
        Request request = requests.remove(id);
        if(request == null) return;
        request.cancellation.cancel();
        if(request.timer != null) main.removeCallbacks(request.timer);
        http.cancel(id);
        media.cancel(request.method);
        video.cancel(request.method);
        if ("browser.authorize".equals(request.method)) browserAuth.cancel();
        if("notifications.requestPermission".equals(request.method))main.post(notificationPermission::cancel);
        if(request.future != null) request.future.cancel(true);
        if(request.dialog != null) request.dialog.dismiss();
    }
    @Override public void close() {
        closed=true;
        main.post(notificationPermission::cancel);
        for(Integer id:requests.keySet()) cancel(id);
        media.close();
        video.close();
        images.close();
        browserAuth.close();
        http.close();
        transfers.shutdownNow();
        worker.execute(() -> {
            sync=null;
            if(installedSync!=null) try { installedSync.close(); } catch(Exception error) {android.util.Log.e("PodJS","Sync owner close failed",error);}
            if(database != null) database.close(); if(background!=null) background.close(); if(notifications!=null)notifications.close(); device.close();
        });
        worker.shutdown();
    }
}
