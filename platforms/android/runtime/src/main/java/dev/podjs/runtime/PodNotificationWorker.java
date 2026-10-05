package dev.podjs.runtime;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Best-effort one-shot notification timing; no exact-alarm permission claimed. */
public final class PodNotificationWorker extends Worker {
    public PodNotificationWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork() {
        if (!PodTakeover.ready(getApplicationContext())) return Result.retry();
        String id=getInputData().getString("notificationId"),run=getId().toString();
        if(id==null)return Result.failure();
        try(PodNotifications notifications=new PodNotifications(getApplicationContext())) {
            try {return notifications.deliverScheduled(id,run)?Result.success():Result.retry();}
            catch(Exception error){notifications.discardScheduled(id,run);android.util.Log.e("PodJS","Scheduled notification failed",error);return Result.failure();}
        } catch(Exception error){return Result.failure();}
    }
}
