package dev.podjs.runtime;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.*;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestDriver;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real WorkManager/Room synthetic DB. No user/device data or native business DB. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=Application.class)
public class PodWorkManagerTakeoverTest {
    static final String OLD="com.example.nativeapp.RemovedWorker";
    Context context;WorkManager work;PodWorkManagerTakeover.Config legacy;
    @Before public void setup()throws Exception {
        context=ApplicationProvider.getApplicationContext();
        legacy=new PodWorkManagerTakeover.Config(new JSONObject().put("nativeAppId",context.getPackageName())
            .put("workerClasses",new JSONArray().put(OLD)).put("uniqueWorkNames",new JSONArray().put("native-sync"))
            .put("tags",new JSONArray().put("native-log")));
        Configuration config=new Configuration.Builder().setExecutor(new SynchronousExecutor())
            .setTaskExecutor(new SynchronousExecutor()).setWorkerFactory(PodWorkManagerTakeover.factory(legacy)).build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context,config);
        work=WorkManager.getInstance(context);
    }
    @After public void close(){WorkManagerTestInitHelper.closeWorkDatabase();}
    public static class CurrentWorker extends Worker {
        public CurrentWorker(Context c,WorkerParameters p){super(c,p);}
        @Override public Result doWork(){return Result.success();}
    }
    OneTimeWorkRequest old(String tag) {
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(CurrentWorker.class).setInitialDelay(1,TimeUnit.DAYS).addTag(tag).build();
        // Synthetic DB row stores a native class which is absent from this APK.
        request.getWorkSpec().workerClassName=OLD;
        request.getTags().remove(CurrentWorker.class.getName());request.getTags().add(OLD);
        return request;
    }
    WorkInfo.State state(UUID id)throws Exception{return work.getWorkInfoById(id).get(5,TimeUnit.SECONDS).getState();}
    @Test public void pendingLegacyRecordUsesFactoryInsteadOfMissingClassAndCannotStartMigration()throws Exception {
        OneTimeWorkRequest request=old("unlisted-native-tag");work.enqueue(request).getResult().get();
        TestDriver driver=WorkManagerTestInitHelper.getTestDriver(context);
        assertNotNull(driver);driver.setInitialDelayMet(request.getId());
        assertEquals(WorkInfo.State.ENQUEUED,state(request.getId())); // harmless retry, not FAILED/reflection error
        assertFalse(new File(context.getFilesDir(),"package-takeover").exists());
        assertEquals(1,work.getWorkInfoById(request.getId()).get().getRunAttemptCount());
    }
    @Test public void activeLegacyStubSelfCancelsWithoutRunningNativeCode()throws Exception {
        File journal=new File(context.getFilesDir(),"package-takeover/journal");journal.getParentFile().mkdirs();
        Files.write(journal.toPath(),"phase=ACTIVE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OneTimeWorkRequest request=old("unlisted-native-tag");work.enqueue(request).getResult().get();
        WorkManagerTestInitHelper.getTestDriver(context).setInitialDelayMet(request.getId());
        assertTrue(state(request.getId()).isFinished());
    }
    @Test public void retirementCancelsNamesExplicitAndAutomaticClassTagsButPreservesCurrentWorkAndBackup()throws Exception {
        OneTimeWorkRequest named=old("unused"),byClass=old("unused"),byTag=new OneTimeWorkRequest.Builder(CurrentWorker.class)
            .addTag("native-log").setInitialDelay(1,TimeUnit.DAYS).build();
        work.enqueueUniqueWork("native-sync",ExistingWorkPolicy.KEEP,named).getResult().get();
        work.enqueue(byClass).getResult().get();work.enqueue(byTag).getResult().get();
        PeriodicWorkRequest current=new PeriodicWorkRequest.Builder(PodCloudWorker.class,6,TimeUnit.HOURS).setInitialDelay(1,TimeUnit.DAYS).build();
        work.enqueueUniquePeriodicWork("podjs-cloud-sync-6h",ExistingPeriodicWorkPolicy.UPDATE,current).getResult().get();
        File backup=new File(context.getFilesDir(),"package-takeover/synthetic-backup/native.bin");backup.getParentFile().mkdirs();
        byte[] bytes={1,2,3};Files.write(backup.toPath(),bytes);
        String generation="g-"+UUID.randomUUID();
        PodWorkManagerTakeover.retire(context,generation,legacy,work);
        for(OneTimeWorkRequest request:Arrays.asList(named,byClass,byTag))assertEquals(WorkInfo.State.CANCELLED,state(request.getId()));
        assertEquals(WorkInfo.State.ENQUEUED,state(current.getId()));assertArrayEquals(bytes,Files.readAllBytes(backup.toPath()));
        // Simulate a cold process. The durable receipt must protect work created later.
        java.lang.reflect.Field cache=PodWorkManagerTakeover.class.getDeclaredField("completed");cache.setAccessible(true);((Set<?>)cache.get(null)).clear();
        OneTimeWorkRequest later=old("unused");work.enqueueUniqueWork("native-sync",ExistingWorkPolicy.REPLACE,later).getResult().get();
        PodWorkManagerTakeover.retire(context,generation,legacy,work);
        assertEquals(WorkInfo.State.ENQUEUED,state(later.getId()));assertEquals(WorkInfo.State.ENQUEUED,state(current.getId()));
    }
    @Test public void retiredPeriodicWorkerCannotRescheduleAfterActive()throws Exception {
        File journal=new File(context.getFilesDir(),"package-takeover/journal");journal.getParentFile().mkdirs();
        Files.write(journal.toPath(),"phase=ACTIVE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(CurrentWorker.class,6,TimeUnit.HOURS).setInitialDelay(1,TimeUnit.DAYS).build();
        request.getWorkSpec().workerClassName=OLD;request.getTags().add(OLD);
        work.enqueueUniquePeriodicWork("native-sync",ExistingPeriodicWorkPolicy.UPDATE,request).getResult().get();
        TestDriver driver=WorkManagerTestInitHelper.getTestDriver(context);driver.setInitialDelayMet(request.getId());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(WorkInfo.State.CANCELLED,state(request.getId()));
    }
    @Test public void persistedLegacyDatabaseReopensOnProductionStartupAndCurrentTaskRegisters()throws Exception {
        WorkManagerTestInitHelper.closeWorkDatabase();
        WorkManagerImpl.setDelegate(null);
        Configuration configuration=new Configuration.Builder().setWorkerFactory(PodWorkManagerTakeover.factory(legacy)).build();
        WorkManager.initialize(context,configuration);work=WorkManager.getInstance(context);
        PeriodicWorkRequest old=new PeriodicWorkRequest.Builder(CurrentWorker.class,6,TimeUnit.HOURS).setInitialDelay(1,TimeUnit.DAYS).build();
        old.getWorkSpec().workerClassName=OLD;old.getTags().add(OLD);
        work.enqueueUniquePeriodicWork("native-sync",ExistingPeriodicWorkPolicy.UPDATE,old).getResult().get(10,TimeUnit.SECONDS);
        assertEquals(WorkInfo.State.ENQUEUED,state(old.getId()));
        WorkManagerImpl.getInstance(context).closeDatabase();
        java.lang.reflect.Field instance=WorkManagerImpl.class.getDeclaredField("sDefaultInstance");instance.setAccessible(true);instance.set(null,null);
        WorkManager.initialize(context,configuration);work=WorkManager.getInstance(context);
        assertEquals(WorkInfo.State.ENQUEUED,state(old.getId()));
        PodWorkManagerTakeover.retire(context,"g-"+UUID.randomUUID(),legacy,work);
        assertEquals(WorkInfo.State.CANCELLED,state(old.getId()));
        PeriodicWorkRequest current=new PeriodicWorkRequest.Builder(PodCloudWorker.class,6,TimeUnit.HOURS).setInitialDelay(1,TimeUnit.DAYS).build();
        work.enqueueUniquePeriodicWork("podjs-cloud-sync-6h",ExistingPeriodicWorkPolicy.UPDATE,current).getResult().get(10,TimeUnit.SECONDS);
        assertEquals(WorkInfo.State.ENQUEUED,state(current.getId()));
        WorkManagerImpl.getInstance(context).closeDatabase();instance.set(null,null);
    }
    @Test public void nativeVersion23DatabaseUpgradesTo24WithLegacyWorkPreservedThenRetired()throws Exception {
        WorkManagerTestInitHelper.closeWorkDatabase();WorkManagerImpl.setDelegate(null);
        File file=new File(context.getNoBackupFilesDir(),"androidx.work.workdb");
        String id=UUID.randomUUID().toString();
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)) {
            java.io.ByteArrayOutputStream schema=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream in=context.getAssets().open("workmanager-2.10.1-v23.sql")){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)schema.write(buffer,0,n);}
            for(String sql:schema.toString("UTF-8").replaceAll("(?m)^--.*$", "").split(";"))if(!sql.trim().isEmpty())db.execSQL(sql);
            android.content.ContentValues values=new android.content.ContentValues();
            try(android.database.Cursor columns=db.rawQuery("PRAGMA table_info(WorkSpec)",null)) {
                while(columns.moveToNext())if(columns.getInt(3)==1&&columns.isNull(4)) {
                    String name=columns.getString(1),type=columns.getString(2);
                    if("TEXT".equals(type))values.put(name,"");else if("BLOB".equals(type))values.put(name,new byte[0]);else values.put(name,0);
                }
            }
            values.put("id",id);values.put("worker_class_name",OLD);values.put("input_merger_class_name","androidx.work.OverwritingInputMerger");
            // Native 2.10.1 Data v1: magic 0xabef, version 1, zero entries.
            byte[] data={(byte)0xab,(byte)0xef,0,1,0,0,0,0};values.put("input",data);values.put("output",data);
            values.put("initial_delay",TimeUnit.DAYS.toMillis(1));values.put("interval_duration",TimeUnit.HOURS.toMillis(6));values.put("flex_duration",TimeUnit.HOURS.toMillis(6));
            values.put("last_enqueue_time",System.currentTimeMillis());values.put("schedule_requested_at",-1);values.put("backoff_delay_duration",30000);
            db.insertOrThrow("WorkSpec",null,values);db.execSQL("INSERT INTO WorkTag(tag,work_spec_id) VALUES(?,?)",new Object[]{OLD,id});
            db.execSQL("INSERT INTO WorkName(name,work_spec_id) VALUES(?,?)",new Object[]{"native-sync",id});
            assertEquals(23,db.getVersion());
        }
        WorkManager.initialize(context,new Configuration.Builder().setWorkerFactory(PodWorkManagerTakeover.factory(legacy)).build());work=WorkManager.getInstance(context);
        assertEquals(WorkInfo.State.ENQUEUED,state(UUID.fromString(id)));
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)){assertEquals(24,db.getVersion());}
        PodWorkManagerTakeover.afterAdmission(context,"g-"+UUID.randomUUID());assertEquals(WorkInfo.State.CANCELLED,state(UUID.fromString(id)));
        WorkManagerImpl.getInstance(context).closeDatabase();java.lang.reflect.Field instance=WorkManagerImpl.class.getDeclaredField("sDefaultInstance");instance.setAccessible(true);instance.set(null,null);
    }
    @Test public void productionAssetConfigurationRetiresDeclaredLegacyWork()throws Exception {
        OneTimeWorkRequest request=old("unused");work.enqueueUniqueWork("native-sync",ExistingWorkPolicy.KEEP,request).getResult().get();
        PodWorkManagerTakeover.afterAdmission(context,"g-"+UUID.randomUUID());
        assertEquals(WorkInfo.State.CANCELLED,state(request.getId()));
    }
    @Test public void productionDefaultFactoryLeavesFreshInstallCurrentWorkersAlone() {
        // No injected manifest/config: use exactly the Application call on a fresh app.
        assertNotNull(PodWorkManagerTakeover.factory(context));
        assertNull(PodWorkManagerTakeover.factory(context).createWorker(context,CurrentWorker.class.getName(),null));
        assertTrue(PodWorkManagerTakeover.completedInProcess(context,new File(context.getApplicationInfo().dataDir,"files/podjs")));
    }
}
