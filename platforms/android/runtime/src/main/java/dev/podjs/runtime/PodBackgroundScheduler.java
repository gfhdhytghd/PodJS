package dev.podjs.runtime;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** Host-only one-shot WorkManager adapter. APIs block on persistence: call on an
 * IO worker. Never pass guest-provided source; the installer must verify the
 * background bundle/manifest and supply its expected SHA-256 independently.
 */
public final class PodBackgroundScheduler implements Closeable {
    private static final Object CONTROL_LOCK = new Object();
    private final SQLiteDatabase db;
    private final WorkManager work;
    private final String packageName;
    public PodBackgroundScheduler(Context context) throws IOException {
        PodTakeover.requireReady(context);
        packageName=context.getPackageName();
        File root = new File(PodTakeover.sandbox(context),"no_backup/podjs-background");
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Background storage unavailable");
        PodRoomV18Converter.checkDatabaseFamily(new File(root,"jobs.sqlite"));
        db = SQLiteDatabase.openOrCreateDatabase(new File(root,"jobs.sqlite"),null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, app TEXT NOT NULL, task TEXT NOT NULL, source TEXT NOT NULL, hash TEXT NOT NULL, budget INTEGER NOT NULL, state TEXT NOT NULL, result TEXT, attempt INTEGER NOT NULL DEFAULT 0)");
            synchronized (CONTROL_LOCK) {
                boolean hasPayload=false, hasMethods=false;
                try (Cursor columns=db.rawQuery("PRAGMA table_info(jobs)",null)) {
                    while (columns.moveToNext()) {
                        if ("payload".equals(columns.getString(1))) hasPayload=true;
                        if ("methods".equals(columns.getString(1))) hasMethods=true;
                    }
                }
                if (!hasPayload) db.execSQL("ALTER TABLE jobs ADD COLUMN payload TEXT NOT NULL DEFAULT 'null'");
                if (!hasMethods) db.execSQL("ALTER TABLE jobs ADD COLUMN methods TEXT NOT NULL DEFAULT '[]'");
            }
            long workBegan = PodStartupTrace.now();
            work = WorkManager.getInstance(context.getApplicationContext());
            PodStartupTrace.stage("workManagerAcquire", workBegan);
        } catch (RuntimeException|Error error) { PodTakeoverCoordinator.closeAfterFailure(db,error); throw error; }
    }
    static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid task identity");
    }
    public static String sha256(String source) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))
            hash.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return hash.toString();
    }
    private static String unique(String app, String task) { return "podjs-bg:"+app.length()+":"+app+":"+task; }
    public synchronized UUID scheduleVerified(String app, String task, String source, String expectedHash,
            long earliestAt, long budgetMs, boolean requiresNetwork) throws Exception {
        return scheduleVerified(app,task,source,expectedHash,earliestAt,budgetMs,requiresNetwork,JSONObject.NULL);
    }
    public synchronized UUID scheduleVerified(String app, String task, String source, String expectedHash,
            long earliestAt, long budgetMs, boolean requiresNetwork, Object payload) throws Exception {
        return scheduleVerified(app,task,source,expectedHash,earliestAt,budgetMs,requiresNetwork,payload,new String[0]);
    }
    public synchronized UUID scheduleVerified(String app, String task, String source, String expectedHash,
            long earliestAt, long budgetMs, boolean requiresNetwork, Object payload, String[] approvedMethods) throws Exception {
        JSONArray methods=new JSONArray(java.util.Arrays.asList(approvedMethods.clone()));
        PodBackgroundPackage.validateGrants(methods);
        if (methods.length()>0 && !packageName.equals(app)) throw new SecurityException("Background data belongs to the installed app");
        if (payload != null && payload != JSONObject.NULL && !(payload instanceof JSONObject)
                && !(payload instanceof JSONArray) && !(payload instanceof String)
                && !(payload instanceof Boolean) && !(payload instanceof Number))
            throw new IllegalArgumentException("Background payload must be JSON data");
        String wrapped=new JSONArray().put(payload == null ? JSONObject.NULL : payload).toString();
        if (wrapped == null) throw new IllegalArgumentException("Invalid background JSON payload");
        String encoded=wrapped.substring(1,wrapped.length()-1);
        if (encoded.getBytes(StandardCharsets.UTF_8).length>64*1024) throw new IllegalArgumentException("Background payload exceeds 64 KiB");
        synchronized (CONTROL_LOCK) { return scheduleLocked(app,task,source,expectedHash,earliestAt,budgetMs,requiresNetwork,encoded,methods.toString()); }
    }
    private UUID scheduleLocked(String app, String task, String source, String expectedHash,
            long earliestAt, long budgetMs, boolean requiresNetwork, String payload, String methods) throws Exception {
        id(app); id(task);
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > 1024*1024
                || budgetMs < 1 || budgetMs > 30000 || earliestAt < 0 || earliestAt > 9007199254740991L)
            throw new IllegalArgumentException("Invalid background task limits");
        if (!sha256(source).equals(expectedHash)) throw new SecurityException("Background bundle hash mismatch");
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PodBackgroundWorker.class)
            .setInputData(new Data.Builder().putString("app",app).putString("task",task).build())
            .setInitialDelay(Math.max(0,earliestAt-System.currentTimeMillis()),TimeUnit.MILLISECONDS)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(requiresNetwork ? NetworkType.CONNECTED : NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build();
        ContentValues values = new ContentValues(); values.put("id",request.getId().toString()); values.put("app",app);
        values.put("task",task); values.put("source",source); values.put("hash",expectedHash); values.put("budget",budgetMs); values.put("state","scheduled");
        values.put("payload",payload);
        values.put("methods",methods);
        db.beginTransaction();
        try {
            long count = android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM jobs WHERE app=?",new String[]{app});
            long used = android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(length(CAST(source AS BLOB))+length(CAST(payload AS BLOB))),0) FROM jobs WHERE app=?",new String[]{app});
            if (count >= 128 || used + source.getBytes(StandardCharsets.UTF_8).length + payload.getBytes(StandardCharsets.UTF_8).length > 16*1024*1024) throw new IllegalStateException("Background history quota exceeded");
            ContentValues superseded = new ContentValues(); superseded.put("state","cancelled");
            superseded.put("result",new JSONObject().put("status","failure").put("code","superseded").toString());
            db.update("jobs",superseded,"app=? AND task=? AND state IN ('scheduled','running','retry')",new String[]{app,task});
            db.insertOrThrow("jobs",null,values); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        try { work.enqueueUniqueWork(unique(app,task),ExistingWorkPolicy.REPLACE,request).getResult().get(10,TimeUnit.SECONDS); }
        catch (Exception error) { record(request.getId().toString(),"failed",new JSONObject().put("status","failure").put("code","enqueue_failed"),0); throw error; }
        return request.getId();
    }
    static final class Job {
        final String app, task, source, hash, payload, methods; final long budget;
        Job(Cursor row) { app=row.getString(0); task=row.getString(1); source=row.getString(2); hash=row.getString(3); budget=row.getLong(4); payload=row.getString(5); methods=row.getString(6); }
    }
    synchronized Job begin(String runId, String app, String task, int attempt) {
        ContentValues running = new ContentValues(); running.put("state","running"); running.put("attempt",attempt);
        if (db.update("jobs",running,"id=? AND app=? AND task=? AND state IN ('scheduled','retry','running')",new String[]{runId,app,task}) == 0) return null;
        try (Cursor row = db.rawQuery("SELECT app,task,source,hash,budget,payload,methods FROM jobs WHERE id=?",new String[]{runId})) {
            return row.moveToFirst() ? new Job(row) : null;
        }
    }
    synchronized void record(String runId, String state, JSONObject result, int attempt) {
        ContentValues values = new ContentValues(); values.put("state",state); values.put("result",result.toString()); values.put("attempt",attempt);
        db.update("jobs",values,"id=? AND state!='cancelled'",new String[]{runId});
    }
    public synchronized JSONObject status(UUID runId) throws Exception {
        try (Cursor row = db.rawQuery("SELECT app,task,state,result,attempt FROM jobs WHERE id=?",new String[]{runId.toString()})) {
            if (!row.moveToFirst()) throw new IllegalArgumentException("Unknown background run");
            JSONObject status = new JSONObject().put("runId",runId.toString()).put("appId",row.getString(0)).put("taskId",row.getString(1))
                .put("state",row.getString(2)).put("attempt",row.getInt(4));
            if (!row.isNull(3)) status.put("result",new JSONObject(row.getString(3)));
            androidx.work.WorkInfo info = work.getWorkInfoById(runId).get(10,TimeUnit.SECONDS);
            if (info != null) {
                status.put("systemState",info.getState().name());
                if (info.getState() == androidx.work.WorkInfo.State.CANCELLED) status.put("state","cancelled");
            }
            return status;
        }
    }
    public synchronized JSONObject status(String app, String task) throws Exception {
        id(app); id(task);
        try (Cursor row=db.rawQuery("SELECT id FROM jobs WHERE app=? AND task=? ORDER BY rowid DESC LIMIT 1",new String[]{app,task})) {
            if (!row.moveToFirst()) throw new IllegalArgumentException("Unknown background task");
            return status(UUID.fromString(row.getString(0)));
        }
    }
    public synchronized void cancel(String app, String task) throws Exception {
        synchronized (CONTROL_LOCK) { cancelLocked(app,task); }
    }
    private void cancelLocked(String app, String task) throws Exception {
        id(app); id(task);
        ContentValues values = new ContentValues(); values.put("state","cancelled");
        values.put("result",new JSONObject().put("status","failure").put("code","cancelled").toString());
        db.update("jobs",values,"app=? AND task=? AND state IN ('scheduled','running','retry')",new String[]{app,task});
        work.cancelUniqueWork(unique(app,task)).getResult().get(10,TimeUnit.SECONDS);
    }
    @Override public synchronized void close() { db.close(); }
}
