package dev.podjs.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.UUID;

/** Installed-app local notifications and durable click inbox. All disk work
 * belongs on host IO workers. No guest-selected Intent/component is accepted. */
public final class PodNotifications implements Closeable {
    private static final Object LOCK=new Object();
    private static final String CHANNEL="podjs.local.v1", TOKEN="dev.podjs.notification.token";
    private final Context context;
    private final NotificationManager manager;
    private final SQLiteDatabase db;
    public PodNotifications(Context context) throws Exception {
        PodTakeover.requireReady(context);
        this.context=context.getApplicationContext();manager=context.getSystemService(NotificationManager.class);
        java.io.File root=new java.io.File(context.getNoBackupFilesDir(),"podjs-notifications");
        if (!root.mkdirs()&&!root.isDirectory()) throw new java.io.IOException("Notification storage unavailable");
        db=SQLiteDatabase.openOrCreateDatabase(new java.io.File(root,"inbox.sqlite"),null);
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS notifications (id TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL, descriptor TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS action_tokens (token TEXT PRIMARY KEY, notification_id TEXT NOT NULL, action_id TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled (id TEXT PRIMARY KEY, run TEXT NOT NULL, descriptor TEXT NOT NULL)");
    }
    public boolean permitted() {
        if (Build.VERSION.SDK_INT>=33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return channel==null || channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static JSONObject descriptor(JSONObject value) throws Exception {
        JSONObject copy=new JSONObject(value.toString());PodBackgroundScheduler.id(copy.getString("id"));
        if (!(copy.get("title") instanceof String) || !(copy.get("body") instanceof String)
                || copy.getString("title").length()>256 || copy.getString("body").length()>4096
                || copy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32*1024)
            throw new IllegalArgumentException("Invalid notification content");
        if (copy.has("actions")) {
            org.json.JSONArray actions=copy.getJSONArray("actions");
            if (actions.length()>3) throw new IllegalArgumentException("At most three notification actions");
            java.util.HashSet<String> ids=new java.util.HashSet<>();
            for(int i=0;i<actions.length();i++) {
                JSONObject action=actions.getJSONObject(i);PodBackgroundScheduler.id(action.getString("id"));
                if (!ids.add(action.getString("id")) || !(action.get("title") instanceof String) || action.getString("title").isEmpty() || action.getString("title").length()>64)
                    throw new IllegalArgumentException("Invalid notification action");
            }
        }
        if (copy.has("at") && (!(copy.get("at") instanceof Number) || copy.getDouble("at")!=copy.getLong("at") || copy.getLong("at")<0 || copy.getLong("at")>9007199254740991L))
            throw new IllegalArgumentException("Invalid notification time");
        if(copy.has("category") && (!(copy.get("category") instanceof String) || copy.getString("category").length()>64))throw new IllegalArgumentException("Invalid notification category");
        return copy;
    }
    private String workName(String id) {return "podjs-notification:"+id;}
    public void schedule(JSONObject value) throws Exception { schedule(value,null); }
    /** Package-private fault boundary for process-death regression tests. */
    void schedule(JSONObject value,Runnable afterEnqueue) throws Exception {
        JSONObject copy=descriptor(value);String id=copy.getString("id");
        if(!permitted())throw new SecurityException("Notification permission denied");
        synchronized(LOCK) {
            androidx.work.WorkManager work=androidx.work.WorkManager.getInstance(context);
            if(copy.optLong("at",0)<=System.currentTimeMillis()) {
                db.delete("scheduled","id=?",new String[]{id});
                work.cancelAllWorkByTag(workName(id)).getResult().get(10,java.util.concurrent.TimeUnit.SECONDS);
                work.cancelUniqueWork(workName(id)).getResult().get(10,java.util.concurrent.TimeUnit.SECONDS);
                publish(copy);return;
            }
            long exists=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM scheduled WHERE id=?",new String[]{id});
            if(exists==0 && android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM scheduled",null)>=128)throw new IllegalStateException("Scheduled notification quota exceeded");
            String previousRun=null;
            try(Cursor previous=db.rawQuery("SELECT run FROM scheduled WHERE id=?",new String[]{id})) {
                if(previous.moveToFirst())previousRun=previous.getString(0);
            }
            androidx.work.OneTimeWorkRequest request=new androidx.work.OneTimeWorkRequest.Builder(PodNotificationWorker.class)
                .setInputData(new androidx.work.Data.Builder().putString("notificationId",id).build())
                .addTag(workName(id))
                .setInitialDelay(Math.max(0,copy.getLong("at")-System.currentTimeMillis()),java.util.concurrent.TimeUnit.MILLISECONDS).build();
            // Persist system work first without replacing the previous generation.
            // Its Worker takes LOCK before reading the authoritative record, so it
            // cannot observe the in-process prepare/commit gap. After process death
            // an uncommitted generation is a no-op and the old one remains runnable.
            work.enqueue(request).getResult().get(10,java.util.concurrent.TimeUnit.SECONDS);
            if(afterEnqueue!=null)afterEnqueue.run();
            try {
                db.beginTransaction();
                try {
                    db.execSQL("INSERT OR REPLACE INTO scheduled(id,run,descriptor) VALUES(?,?,?)",new Object[]{id,request.getId().toString(),copy.toString()});
                    db.delete("action_tokens","notification_id=?",new String[]{id});db.delete("notifications","id=?",new String[]{id});
                    db.setTransactionSuccessful();
                } finally {db.endTransaction();}
            } catch(Exception error){work.cancelWorkById(request.getId());throw error;}
            manager.cancel("podjs:"+id,0);
            if(previousRun!=null)work.cancelWorkById(UUID.fromString(previousRun));
        }
    }
    public org.json.JSONArray listPending() throws Exception {
        synchronized(LOCK) {
            org.json.JSONArray result=new org.json.JSONArray();
            try(Cursor rows=db.rawQuery("SELECT descriptor FROM scheduled ORDER BY rowid",null)) {while(rows.moveToNext())result.put(new JSONObject(rows.getString(0)));}
            return result;
        }
    }
    /** False means wall time moved backwards: WorkManager should retry. */
    boolean deliverScheduled(String id,String run) throws Exception {
        synchronized(LOCK) {
            try(Cursor row=db.rawQuery("SELECT descriptor FROM scheduled WHERE id=? AND run=?",new String[]{id,run})) {
                if(!row.moveToFirst())return true;
                JSONObject value=new JSONObject(row.getString(0));
                if(value.getLong("at")>System.currentTimeMillis())return false;
                publish(value);discardScheduled(id,run);return true;
            }
        }
    }
    void discardScheduled(String id,String run) {synchronized(LOCK){db.delete("scheduled","id=? AND run=?",new String[]{id,run});}}
    public void show(JSONObject value) throws Exception {schedule(value);}
    private void publish(JSONObject value) throws Exception {
        JSONObject notification=descriptor(value);
        if (!permitted()) throw new SecurityException("Notification permission denied");
        String id=notification.getString("id"), token=UUID.randomUUID().toString();
        PendingIntent open=click(token);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"App notifications",NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder builder=new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.getString("title")).setContentText(notification.getString("body"))
            .setStyle(new Notification.BigTextStyle().bigText(notification.getString("body")))
            .setContentIntent(open).setAutoCancel(true);
        if(notification.has("category"))builder.setCategory(notification.getString("category"));
        java.util.LinkedHashMap<String,String> actionTokens=new java.util.LinkedHashMap<>();
        org.json.JSONArray actions=notification.optJSONArray("actions");
        if (actions!=null) for(int i=0;i<actions.length();i++) {
            JSONObject action=actions.getJSONObject(i);String actionToken=UUID.randomUUID().toString();
            actionTokens.put(actionToken,action.getString("id"));
            builder.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon)null,action.getString("title"),click(actionToken)).build());
        }
        Notification built=builder.build();
        synchronized (LOCK) {
            long exists=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM notifications WHERE id=?",new String[]{id});
            if (exists==0 && android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM notifications",null)>=128)
                throw new IllegalStateException("Notification quota exceeded");
            android.content.ContentValues row=new android.content.ContentValues();row.put("id",id);row.put("token",token);row.put("descriptor",notification.toString());row.put("consumed",0);
            db.beginTransaction();
            try {
                if (db.insertWithOnConflict("notifications",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1) throw new IllegalStateException("Notification persistence failed");
                db.delete("action_tokens","notification_id=?",new String[]{id});
                for(java.util.Map.Entry<String,String> action:actionTokens.entrySet())
                    db.execSQL("INSERT INTO action_tokens(token,notification_id,action_id) VALUES(?,?,?)",new Object[]{action.getKey(),id,action.getValue()});
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            manager.notify("podjs:"+id,0,built);
        }
    }
    private PendingIntent click(String token) {
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch==null) throw new IllegalStateException("Notification launch Activity unavailable");
        launch.setData(new android.net.Uri.Builder().scheme("podjs-notification").authority(context.getPackageName()).appendPath(token).build());
        launch.putExtra(TOKEN,token).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    /** Called by the launch Activity before frames begin. Only a persisted,
     * unpredictable notification token can create an event; extras are not data. */
    public boolean captureOpen(Intent intent) throws Exception {
        String token=intent==null?null:intent.getStringExtra(TOKEN);
        if (token==null) return false;
        synchronized (LOCK) {
            db.beginTransaction();
            try (Cursor row=db.rawQuery("SELECT n.id,n.descriptor,n.consumed,a.action_id FROM notifications n LEFT JOIN action_tokens a ON a.notification_id=n.id AND a.token=? WHERE n.token=? OR a.token IS NOT NULL",new String[]{token,token})) {
                if (!row.moveToFirst() || row.getInt(2)!=0) return false;
                if (android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM events",null)>=256) throw new IllegalStateException("Notification inbox full");
                JSONObject descriptor=new JSONObject(row.getString(1));String eventId=UUID.randomUUID().toString();
                JSONObject body=new JSONObject().put("t",row.isNull(3)?"notification.open":"notification.action").put("value",new JSONObject().put("eventId",eventId)
                    .put("notificationId",row.getString(0)).put("payload",descriptor.opt("payload")==null?JSONObject.NULL:descriptor.opt("payload")));
                if (!row.isNull(3)) body.getJSONObject("value").put("actionId",row.getString(3));
                db.execSQL("INSERT INTO events(id,body) VALUES(?,?)",new Object[]{eventId,body.toString()});
                db.execSQL("UPDATE notifications SET consumed=1 WHERE id=?",new Object[]{row.getString(0)});
                db.setTransactionSuccessful();manager.cancel("podjs:"+row.getString(0),0);return true;
            } finally {db.endTransaction();}
        }
    }
    public org.json.JSONArray pendingEvents() throws Exception {
        synchronized (LOCK) {
            org.json.JSONArray result=new org.json.JSONArray();
            try (Cursor rows=db.rawQuery("SELECT body FROM events ORDER BY rowid LIMIT 32",null)) {
                while(rows.moveToNext())result.put(new JSONObject(rows.getString(0)));
            }
            return result;
        }
    }
    public void acknowledge(String eventId) {
        synchronized(LOCK) {
            db.beginTransaction();
            try(Cursor event=db.rawQuery("SELECT body FROM events WHERE id=?",new String[]{eventId})) {
                if(event.moveToFirst()) {
                    String id;
                    try {id=new JSONObject(event.getString(0)).getJSONObject("value").getString("notificationId");}
                    catch(org.json.JSONException error){throw new IllegalStateException("Invalid stored notification event",error);}
                    db.delete("events","id=?",new String[]{eventId});
                    // Only remove a consumed publication, never a newer live replacement.
                    if(db.delete("notifications","id=? AND consumed=1",new String[]{id})>0)db.delete("action_tokens","notification_id=?",new String[]{id});
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    public void cancel(String id) {PodBackgroundScheduler.id(id);synchronized(LOCK){db.delete("scheduled","id=?",new String[]{id});androidx.work.WorkManager work=androidx.work.WorkManager.getInstance(context);work.cancelAllWorkByTag(workName(id));work.cancelUniqueWork(workName(id));manager.cancel("podjs:"+id,0);db.delete("action_tokens","notification_id=?",new String[]{id});db.delete("notifications","id=?",new String[]{id});}}
    @Override public void close() {synchronized(LOCK){db.close();}}
}
