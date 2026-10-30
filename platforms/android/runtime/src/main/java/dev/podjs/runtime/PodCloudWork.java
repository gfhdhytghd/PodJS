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
    private static final Object QUEUE=new Object();
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
    static OneTimeWorkRequest manualWork(String token){return manualWork(token,false);}
    static OneTimeWorkRequest manualWork(String token,boolean standby){return manualWork(new WindowedWorkQueue.Job(token,java.util.UUID.randomUUID().toString(),null,0,System.currentTimeMillis()+(standby?WindowedWorkQueue.STANDBY_MS:0),standby,false));}
    private static OneTimeWorkRequest manualWork(WindowedWorkQueue.Job job){return new OneTimeWorkRequest.Builder(PodCloudWorker.class).setId(java.util.UUID.fromString(job.id)).setInputData(new Data.Builder().putString("manualToken",job.token).build()).setInitialDelay(Math.max(0,job.due-System.currentTimeMillis()),TimeUnit.MILLISECONDS).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build();}
    private static WindowedWorkQueue queue(Context c){return new WindowedWorkQueue(new WindowedWorkQueue.Ports(){
        public WindowedWorkQueue.Job read(){android.content.SharedPreferences s=pending(c);String token=s.getString("token",null);return token==null?null:new WindowedWorkQueue.Job(token,s.getString("workId",null),s.getString("parent",null),s.getInt("failures",0),s.getLong("due",0),s.getBoolean("standby",false),s.getBoolean("held",false));}
        public void write(WindowedWorkQueue.Job job)throws Exception{if(!pending(c).edit().clear().putString("token",job.token).putString("workId",job.id).putString("parent",job.parent).putInt("failures",job.failures).putLong("due",job.due).putBoolean("standby",job.standby).putBoolean("held",job.held).commit())throw new java.io.IOException("cloud_queue_persist_failed");}
        public void clear()throws Exception{if(!pending(c).edit().clear().commit())throw new java.io.IOException("cloud_queue_persist_failed");}
        private WorkInfo info(String id)throws Exception{return id==null?null:WorkManager.getInstance(c).getWorkInfoById(java.util.UUID.fromString(id)).get(10,TimeUnit.SECONDS);}
        public WindowedWorkQueue.State state(String id)throws Exception{WorkInfo i=info(id);return i==null?WindowedWorkQueue.State.MISSING:i.getState()==WorkInfo.State.RUNNING?WindowedWorkQueue.State.RUNNING:i.getState().isFinished()?WindowedWorkQueue.State.FINISHED:WindowedWorkQueue.State.WAITING;}
        public String running()throws Exception{for(WorkInfo i:WorkManager.getInstance(c).getWorkInfosForUniqueWork(MANUAL_NAME).get(10,TimeUnit.SECONDS))if(i.getState()==WorkInfo.State.RUNNING)return i.getId().toString();return null;}
        public long scheduled(String id)throws Exception{WorkInfo i=info(id);return i==null||i.getState()!=WorkInfo.State.ENQUEUED?Long.MAX_VALUE:i.getNextScheduleTimeMillis();}
        public void enqueue(WindowedWorkQueue.Job job,WindowedWorkQueue.Policy policy)throws Exception{android.util.Log.i("PodJSCloudWorker","queued delayMs="+Math.max(0,job.due-System.currentTimeMillis())+" failures="+job.failures+" standby="+job.standby+" successor="+(job.parent!=null));WorkManager.getInstance(c).enqueueUniqueWork(MANUAL_NAME,policy==WindowedWorkQueue.Policy.APPEND?ExistingWorkPolicy.APPEND_OR_REPLACE:policy==WindowedWorkQueue.Policy.KEEP?ExistingWorkPolicy.KEEP:ExistingWorkPolicy.REPLACE,manualWork(job)).getResult().get(10,TimeUnit.SECONDS);}
        public String id(){return java.util.UUID.randomUUID().toString();}public long now(){return System.currentTimeMillis();}
    });}
    static String pendingToken(Context c){synchronized(QUEUE){return pending(c).getString("token",null);}}
    static void enqueue(Context c,String token)throws Exception {enqueue(c,token,false);}
    static void enqueue(Context c,String token,boolean standby)throws Exception {boolean paused=held(c);synchronized(QUEUE){queue(c).enqueue(token,standby,paused);}}
    static void deferHeld(Context c,String token)throws Exception{synchronized(QUEUE){queue(c).defer(token);}}
    static void resumeHeld(Context c)throws Exception {boolean paused=held(c);synchronized(QUEUE){queue(c).resume(paused);}}
    static boolean manualRunning(Context c)throws Exception{synchronized(QUEUE){return queue(c).active();}}
    static boolean yielded(Context c,String token,String id)throws Exception{synchronized(QUEUE){return queue(c).yielded(token,id);}}
    static boolean start(Context c,String token,String id)throws Exception{synchronized(QUEUE){return queue(c).start(token,id);}}
    static boolean next(Context c,String token,String id,boolean progress,boolean periodic)throws Exception{synchronized(QUEUE){return queue(c).next(token,id,progress,periodic);}}
    static void progressed(Context c,String token)throws Exception{boolean paused=held(c);synchronized(QUEUE){queue(c).progress(token,paused);}}
    static void finished(Context c,String token,String id)throws Exception{synchronized(QUEUE){queue(c).finished(token,id);}}
    synchronized Object execute(String method,JSONObject args)throws Exception{
        if("cloud.cancel".equals(method)){synchronized(QUEUE){WorkManager.getInstance(context).cancelUniqueWork(NAME).getResult().get(10,TimeUnit.SECONDS);WorkManager.getInstance(context).cancelUniqueWork(MANUAL_NAME).getResult().get(10,TimeUnit.SECONDS);pending(context).edit().clear().commit();return JSONObject.NULL;}}
        if("cloud.release".equals(method)){releaseWriter();return JSONObject.NULL;}
        PodBackgroundPackage approved=installed(context);approved.cloudSource(debug(context));
        if("cloud.localTransfer".equals(method)){Object value=args.opt("active");if(!(value instanceof Boolean))throw new IllegalArgumentException("active_must_be_boolean");long revision=args.getLong("generation");if(revision<0)throw new IllegalArgumentException("invalid_generation");if(revision>localRevision){hold((Boolean)value);localRevision=revision;}return JSONObject.NULL;}
        if("cloud.enqueue".equals(method)){if(args.optBoolean("progress",false))progressed(context,args.getString("token"));else enqueue(context,args.getString("token"),args.optBoolean("standby",false));return JSONObject.NULL;}
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
