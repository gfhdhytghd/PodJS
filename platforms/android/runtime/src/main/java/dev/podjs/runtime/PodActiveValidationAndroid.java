package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.*;

final class PodActiveValidationAndroid {
    private static final ConcurrentHashMap<String,PodActiveValidation.Workload> workloads=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Boolean> scheduled=new ConcurrentHashMap<>();
    private static final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"podjs-active-validation");t.setDaemon(true);return t;
    });
    private static final PodAfterFirstFrameTasks evidence=new PodAfterFirstFrameTasks(
        task->executor.schedule(task,10,TimeUnit.SECONDS));
    static void activeSummary(Context c,Runnable task) {
        evidence.submitOnce(c.getApplicationInfo().dataDir,task);
    }
    static PodActiveValidation policy(Context c) {
        try{return new PodActiveValidation(c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode(),System::currentTimeMillis);}
        catch(Exception missing){throw new IllegalStateException("package_version_unavailable");}
    }
    static PodActiveValidation.Workload workload(Context c){return workloads.computeIfAbsent(c.getApplicationInfo().dataDir,k->new PodActiveValidation.Workload());}
    static void afterFirstFrame(Context context) {
        Context c=context.getApplicationContext();String key=c.getApplicationInfo().dataDir;
        evidence.afterFirstFrame(key);
        if(scheduled.putIfAbsent(key,true)!=null)return;
        // Let the first guest SQL/font/layout burst settle after actual texture presentation.
        executor.schedule(()->run(c),10,TimeUnit.SECONDS);
    }
    private static void run(Context c) {
        try {
            File root=new File(PodTakeover.sandbox(c),"files/package-takeover");
            Properties journal=PodTakeoverCoordinator.read(new File(root,"journal"));
            if(!"ACTIVE".equals(journal.getProperty("phase")))return;
            File g=new PodTakeoverCoordinator(root,()->null,PodTakeoverCoordinator.EMPTY).activeGeneration(journal);
            String receipt=journal.getProperty("receipt");PodActiveValidation policy=policy(c);
            if(!policy.due(g,receipt))return;
            long began=System.nanoTime();
            policy.deep(g,receipt,()->{
                CancellationSignal signal=new CancellationSignal();
                try(PodActiveValidation.Workload.Reader reader=workload(c).read(signal::cancel)) {
                    PodRoomV18Converter room=new PodRoomV18Converter(null,f->new PodRoomAndroidDb(f,true,signal),"unused").withTimings(PodTakeoverDiagnostics::timed);
                    try {room.active(g);reader.check();}
                    catch(android.os.OperationCanceledException|android.database.sqlite.SQLiteDatabaseLockedException busy){throw new PodActiveValidation.Yield();}
                    reader.check();
                    new PodStoreConverter(PodTakeover.sandbox(c),new PodStoreAndroidCrypto(c),
                        "com.lightningstudio.watchrss.podjs.rehearsal".equals(c.getPackageName())&&
                        (c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0).withTimings(PodTakeoverDiagnostics::timed).active(g);
                    reader.check();
                }
            });
            PodTakeoverDiagnostics.timed("activeDeepPass",System.nanoTime()-began);
        } catch(PodActiveValidation.Yield busy) {
            PodTakeoverDiagnostics.timed("activeDeepYield",0);
            executor.schedule(()->run(c),30,TimeUnit.SECONDS);
        } catch(Exception failed) {
            // Deep() persists FAIL first. Never log exception messages or guest paths.
            PodTakeoverDiagnostics.timed("activeDeepFail",0);
            PodTakeoverDiagnostics.warn("ACTIVE_DEEP",failed);
        }
    }
}
