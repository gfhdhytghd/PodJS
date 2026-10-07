package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.work.*;
import java.io.Closeable;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Debug-only cloud scheduling and one process-independent writer lease. No
 * credentials, account identity or app code are accepted in scheduling args. */
final class PodCloudWork implements Closeable {
    static final String NAME="podjs-cloud-sync-6h";
    static final String HANDLER="watchrss-cloud-sync";
    private final Context context;
    private RandomAccessFile leaseFile;
    private FileLock lease;
    PodCloudWork(Context context){this.context=context.getApplicationContext();}
    static boolean debug(Context context){return (context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
    synchronized Object execute(String method,JSONObject args)throws Exception{
        if("cloud.cancel".equals(method)){WorkManager.getInstance(context).cancelUniqueWork(NAME).getResult().get(10,TimeUnit.SECONDS);return JSONObject.NULL;}
        if(!debug(context))throw new SecurityException("Cloud sync is debug only");
        if("cloud.schedule".equals(method)){
            PodBackgroundPackage approved=installed(context);approved.cloudSource();
            PeriodicWorkRequest work=new PeriodicWorkRequest.Builder(PodCloudWorker.class,6,TimeUnit.HOURS)
                .setInitialDelay(6,TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.UPDATE,work).getResult().get(10,TimeUnit.SECONDS);
            return JSONObject.NULL;
        }
        if("cloud.acquire".equals(method)){
            if(lease!=null)throw new IllegalStateException("Cloud lease already owned");
            java.io.File path=new java.io.File(context.getNoBackupFilesDir(),"podjs-cloud-writer.lock");
            leaseFile=new RandomAccessFile(path,"rw");
            try{lease=leaseFile.getChannel().tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){lease=null;}
            if(lease==null){leaseFile.close();leaseFile=null;return new JSONObject().put("acquired",false);}
            return new JSONObject().put("acquired",true);
        }
        if("cloud.release".equals(method)){close();return JSONObject.NULL;}
        throw new IllegalArgumentException("Unknown cloud lifecycle method");
    }
    static PodBackgroundPackage installed(Context context)throws Exception{
        try{return PodBackgroundPackage.installed(context,"android-watch");}
        catch(SecurityException wrongTarget){return PodBackgroundPackage.installed(context,"wearos-watch");}
    }
    @Override public synchronized void close(){try{if(lease!=null)lease.release();}catch(Exception ignored){}finally{lease=null;try{if(leaseFile!=null)leaseFile.close();}catch(Exception ignored){}leaseFile=null;}}
}
