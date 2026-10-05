package dev.podjs.runtime;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;
import org.json.JSONObject;

/** System-instantiated worker; has no View/Activity or render-loop dependency. */
public final class PodBackgroundWorker extends Worker {
    private PodBackgroundRun active;
    public PodBackgroundWorker(Context context, WorkerParameters params) { super(context,params); }
    @Override public Result doWork() {
        if (!PodTakeover.ready(getApplicationContext())) return Result.retry();
        String app = getInputData().getString("app"), task = getInputData().getString("task");
        try (PodBackgroundScheduler scheduler = new PodBackgroundScheduler(getApplicationContext())) {
            PodBackgroundScheduler.Job job = scheduler.begin(getId().toString(),app,task,getRunAttemptCount());
            if (job == null) return Result.failure();
            JSONObject result;
            try {
                if (!PodBackgroundScheduler.sha256(job.source).equals(job.hash)) throw new SecurityException("Background bundle changed");
                Object payload=new org.json.JSONTokener(job.payload).nextValue();
                String[] methods=PodBackgroundPackage.validateGrants(new org.json.JSONArray(job.methods));
                String kvRoot=null;
                if (methods.length>0) {
                    if (!getApplicationContext().getPackageName().equals(job.app)) throw new SecurityException("Background app mismatch");
                    // Re-check installed grants/source on every system wake, so
                    // an app update cannot retain revoked data access.
                    PodBackgroundPackage installed;
                    try { installed=PodBackgroundPackage.installed(getApplicationContext(),"android-watch"); }
                    catch (SecurityException wrongTarget) { installed=PodBackgroundPackage.installed(getApplicationContext(),"wearos-watch"); }
                    if (!installed.permitsSource(job.hash,methods)) throw new SecurityException("Background data grant revoked");
                    kvRoot=PodTakeover.dataRoot(getApplicationContext()).getAbsolutePath();
                }
                try (PodBackgroundRun run = new PodBackgroundRun(job.app,job.task,job.source,job.budget,16*1024*1024,payload,methods,kvRoot)) {
                    synchronized (this) { active=run; if (isStopped()) run.cancel(); }
                    try { result=run.run(); }
                    finally { synchronized (this) { active=null; } }
                }
            } catch (Exception error) { result=new JSONObject().put("status","failure").put("code",error instanceof SecurityException?"background_permission_denied":"worker_execution_failed"); }
            // A constraint/system stop may be rescheduled by WorkManager. Explicit
            // cancellation already marks the row cancelled and cannot be overwritten.
            if (isStopped()) result=new JSONObject().put("status","retry").put("code","system_stopped");
            String status=result.getString("status");
            scheduler.record(getId().toString(),"success".equals(status)?"completed":"retry".equals(status)?"retry":"failed",result,getRunAttemptCount());
            android.util.Log.i("PodJSBackground","run="+getId()+" status="+status+" code="+result.optString("code"));
            Data output=new Data.Builder().putString("result",result.toString()).build();
            return "success".equals(status)?Result.success(output):"retry".equals(status)?Result.retry():Result.failure(output);
        } catch (Exception error) { return Result.failure(new Data.Builder().putString("code","background_storage_unavailable").build()); }
    }
    @Override public synchronized void onStopped() { if (active != null) active.cancel(); }
}
