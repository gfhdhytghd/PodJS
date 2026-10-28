package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.work.*;
import java.io.Closeable;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Installed-manifest-authorized workers and process-independent writer/local
 * transfer leases. Scheduling accepts only an opaque token, never credentials. */
final class PodCloudWork implements Closeable {
    static final String NAME="podjs-cloud-sync-6h",MANUAL_NAME="podjs-cloud-sync-manual",HANDLER="watchrss-cloud-sync";
    private final Context context;
    private final CloudWriterLease writer=new CloudWriterLease();private boolean localHold;private long localRevision=-1;
    private static final java.util.Set<PodCloudWork> HOLDERS=new java.util.HashSet<>();
    private static RandomAccessFile holdFile;private static FileLock holdLock;
    PodCloudWork(Context context){this.context=context.getApplicationContext();}
    static boolean debug(Context context){return (context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
    static android.content.SharedPreferences pending(Context c){return c.getSharedPreferences("podjs-cloud-manual",Context.MODE_PRIVATE);}
    static boolean held(Context c){synchronized(HOLDERS){if(!HOLDERS.isEmpty())return true;try(RandomAccessFile f=new RandomAccessFile(new java.io.File(c.getNoBackupFilesDir(),"podjs-cloud-local.lock"),"rw")){try(FileLock lock=f.getChannel().tryLock()){return lock==null;}}catch(Exception busy){return true;}}}
    private void hold(boolean active)throws Exception {synchronized(HOLDERS){
        if(active==localHold)return;
        if(active){if(HOLDERS.isEmpty()){holdFile=new RandomAccessFile(new java.io.File(context.getNoBackupFilesDir(),"podjs-cloud-local.lock"),"rw");try{holdLock=holdFile.getChannel().tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){holdLock=null;}if(holdLock==null){holdFile.close();holdFile=null;throw new IllegalStateException("local_transfer_lease_busy");}}HOLDERS.add(this);localHold=true;
        }else {HOLDERS.remove(this);localHold=false;if(HOLDERS.isEmpty()){try{if(holdLock!=null)holdLock.release();}finally{holdLock=null;if(holdFile!=null)holdFile.close();holdFile=null;}resumeHeld(context);}}
    }}
    static OneTimeWorkRequest manualWork(String token){return new OneTimeWorkRequest.Builder(PodCloudWorker.class).setInputData(new Data.Builder().putString("manualToken",token).build()).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();}
    static void enqueue(Context c,String token)throws Exception {
        if(token==null||!token.matches("[a-zA-Z0-9_-]{1,96}"))throw new IllegalArgumentException("invalid_manual_token");
        String previous=pending(c).getString("token",null);boolean paused=held(c);
        pending(c).edit().putString("token",token).putBoolean("held",paused).commit();if(paused)return;
        WorkManager.getInstance(c).enqueueUniqueWork(MANUAL_NAME,token.equals(previous)?ExistingWorkPolicy.KEEP:ExistingWorkPolicy.REPLACE,manualWork(token)).getResult().get(10,TimeUnit.SECONDS);
    }
    static void deferHeld(Context c,String token){if(token!=null&&token.equals(pending(c).getString("token",null)))pending(c).edit().putBoolean("held",true).commit();}
    static void resumeHeld(Context c)throws Exception {String token=pending(c).getString("token",null);if(token!=null&&pending(c).getBoolean("held",false)&&!held(c)){pending(c).edit().putBoolean("held",false).commit();WorkManager.getInstance(c).enqueueUniqueWork(MANUAL_NAME,ExistingWorkPolicy.REPLACE,manualWork(token)).getResult().get(10,TimeUnit.SECONDS);}}
    static void finished(Context c,String token){if(token!=null&&token.equals(pending(c).getString("token",null))&&!pending(c).getBoolean("held",false))pending(c).edit().clear().commit();}
    synchronized Object execute(String method,JSONObject args)throws Exception{
        if("cloud.cancel".equals(method)){WorkManager.getInstance(context).cancelUniqueWork(NAME).getResult().get(10,TimeUnit.SECONDS);WorkManager.getInstance(context).cancelUniqueWork(MANUAL_NAME).getResult().get(10,TimeUnit.SECONDS);pending(context).edit().clear().commit();return JSONObject.NULL;}
        if("cloud.release".equals(method)){releaseWriter();return JSONObject.NULL;}
        PodBackgroundPackage approved=installed(context);approved.cloudSource(debug(context));
        if("cloud.localTransfer".equals(method)){Object value=args.opt("active");if(!(value instanceof Boolean))throw new IllegalArgumentException("active_must_be_boolean");long revision=args.getLong("generation");if(revision<0)throw new IllegalArgumentException("invalid_generation");if(revision>localRevision){hold((Boolean)value);localRevision=revision;}return JSONObject.NULL;}
        if("cloud.enqueue".equals(method)){enqueue(context,args.getString("token"));return JSONObject.NULL;}
        if("cloud.workState".equals(method)){java.util.List<WorkInfo> states=WorkManager.getInstance(context).getWorkInfosForUniqueWork(MANUAL_NAME).get(10,TimeUnit.SECONDS);String state=states.isEmpty()?"idle":states.get(0).getState().name().toLowerCase(java.util.Locale.ROOT);for(WorkInfo info:states)if(!info.getState().isFinished()){state=info.getState().name().toLowerCase(java.util.Locale.ROOT);break;}if(pending(context).getBoolean("held",false))state="held";return new JSONObject().put("state",state);}
        if("cloud.schedule".equals(method)){
            PeriodicWorkRequest work=new PeriodicWorkRequest.Builder(PodCloudWorker.class,6,TimeUnit.HOURS).setInitialDelay(6,TimeUnit.HOURS).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.UPDATE,work).getResult().get(10,TimeUnit.SECONDS);resumeHeld(context);return JSONObject.NULL;
        }
        if("cloud.acquire".equals(method)){
            Object owner=args.opt("localTransferOwner");
            if(owner!=null&&owner!=JSONObject.NULL&&!(owner instanceof Boolean))throw new IllegalArgumentException("localTransferOwner_must_be_boolean");
            synchronized(HOLDERS){
                boolean ownPause=Boolean.TRUE.equals(owner)&&localHold&&HOLDERS.size()==1&&HOLDERS.contains(this);
                return new JSONObject().put("acquired",writer.acquire(context.getNoBackupFilesDir(),held(context),ownPause));
            }
        }
        throw new IllegalArgumentException("Unknown cloud lifecycle method");
    }
    static PodBackgroundPackage installed(Context context)throws Exception{try{return PodBackgroundPackage.installed(context,"android-watch");}catch(SecurityException wrongTarget){return PodBackgroundPackage.installed(context,"wearos-watch");}}
    private synchronized void releaseWriter(){writer.close();}
    @Override public synchronized void close(){releaseWriter();try{hold(false);}catch(Exception ignored){}}
}
