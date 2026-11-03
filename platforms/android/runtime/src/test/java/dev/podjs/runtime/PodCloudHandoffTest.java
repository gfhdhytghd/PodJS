package dev.podjs.runtime;
import android.app.Application;import android.content.Context;
import androidx.test.core.app.ApplicationProvider;import androidx.work.*;import androidx.work.testing.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.Config;
import org.json.*;import java.lang.reflect.Method;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30,application=Application.class)
public class PodCloudHandoffTest {
 Context context;WorkManager work;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();WorkManagerTestInitHelper.initializeTestWorkManager(context,new Configuration.Builder().setExecutor(new SynchronousExecutor()).setTaskExecutor(new SynchronousExecutor()).build());work=WorkManager.getInstance(context);PodCloudWork.pending(context).edit().clear().commit();}
 @After public void cleanup(){PodCloudWork.pending(context).edit().clear().commit();WorkManagerTestInitHelper.closeWorkDatabase();}
 void hold(PodCloudWork owner,boolean active)throws Exception {Method method=PodCloudWork.class.getDeclaredMethod("hold",boolean.class);method.setAccessible(true);method.invoke(owner,active);}
 @Test public void immediateManualWorkHasConnectedConstraintOpaquePayloadAndLinearBackoff(){OneTimeWorkRequest request=PodCloudWork.manualWork("opaque_1");assertEquals(0,request.getWorkSpec().initialDelay);assertEquals(NetworkType.CONNECTED,request.getWorkSpec().constraints.getRequiredNetworkType());assertEquals("opaque_1",request.getWorkSpec().input.getString("manualToken"));assertEquals(1,request.getWorkSpec().input.getKeyValueMap().size());assertEquals(30000,request.getWorkSpec().backoffDelayDuration);assertEquals(BackoffPolicy.LINEAR,request.getWorkSpec().backoffPolicy);}
 @Test public void localBleLeaseDefersUniqueWorkerAndReleasesWithItsGuest()throws Exception {
  try(PodCloudWork first=new PodCloudWork(context);PodCloudWork second=new PodCloudWork(context)){
   hold(first,true);hold(second,true);assertTrue(PodCloudWork.held(context));PodCloudWork.enqueue(context,"opaque_2");assertTrue(PodCloudWork.pending(context).getBoolean("held",false));assertTrue(work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().isEmpty());
   first.close();assertTrue(PodCloudWork.held(context));second.close();assertFalse(PodCloudWork.held(context));assertFalse(PodCloudWork.pending(context).getBoolean("held",true));assertEquals(1,work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().size());
  }
 }
 @Test public void hardDeadlineRetriesButGuestFailureClearsOnlyMatchingWork()throws Exception {
  PodCloudWork.pending(context).edit().putString("token","opaque_3").putString("workId","00000000-0000-0000-0000-000000000003").putBoolean("held",false).commit();PodCloudWork.finished(context,"old_token","00000000-0000-0000-0000-000000000003");assertEquals("opaque_3",PodCloudWork.pending(context).getString("token",null));
  assertEquals(ListenableWorker.Result.retry().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","deadline_exceeded").put("status","retry"),0).getClass());
  assertEquals(ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","completed").put("status","failure"),0).getClass());
  PodCloudWork.finished(context,"opaque_3","00000000-0000-0000-0000-000000000099");assertEquals("opaque_3",PodCloudWork.pending(context).getString("token",null));
  PodCloudWork.finished(context,"opaque_3","00000000-0000-0000-0000-000000000003");PodCloudWork.resumeHeld(context);assertNull(PodCloudWork.pending(context).getString("token",null));assertTrue(work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().isEmpty());
 }
 @Test public void midWorkerLocalHoldRetainsOnlyMatchingContinuation()throws Exception {PodCloudWork.pending(context).edit().putString("token","opaque_4").putString("workId","00000000-0000-0000-0000-000000000004").putBoolean("held",false).commit();PodCloudWork.deferHeld(context,"old");assertFalse(PodCloudWork.pending(context).getBoolean("held",true));PodCloudWork.deferHeld(context,"opaque_4");PodCloudWork.finished(context,"opaque_4","00000000-0000-0000-0000-000000000004");assertEquals("opaque_4",PodCloudWork.pending(context).getString("token",null));}
 @Test public void standbyIsDurableBeforeForegroundAndHomeExpeditesSameOpaqueGrant()throws Exception {
  PodCloudWork.enqueue(context,"opaque_restart",true);assertEquals("opaque_restart",PodCloudWork.pending(context).getString("token",null));assertTrue(PodCloudWork.pending(context).getBoolean("standby",false));long delay=PodCloudWork.manualWork("opaque_restart",true).getWorkSpec().initialDelay;assertTrue(delay>0&&delay<=WindowedWorkQueue.STANDBY_MS);
  PodCloudWork.enqueue(context,"opaque_restart",false);java.util.List<WorkInfo> next=work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get();
  assertEquals(1,next.stream().filter(v->!v.getState().isFinished()).count());assertFalse(PodCloudWork.pending(context).getBoolean("standby",true));
 }
 @Test public void restartReconcilesPublishedTokenEvenWhenPreviousEnqueueNeverReachedWorkManager()throws Exception {
  PodCloudWork.pending(context).edit().putString("token","opaque_crash").putBoolean("held",false).commit();assertTrue(work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().isEmpty());
  PodCloudWork.resumeHeld(context);java.util.UUID id=work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().get(0).getId();
  PodCloudWork.resumeHeld(context);assertEquals(id,work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().get(0).getId());assertEquals("opaque_crash",PodCloudWork.pending(context).getString("token",null));
 }

 @Test public void checkpointAndDeadlineRetriesOutliveThreeAttemptsButGuestFailureStillStops()throws Exception {
  for(int attempt:new int[]{0,3,20}){
   assertEquals(ListenableWorker.Result.retry().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","completed").put("status","retry"),attempt).getClass());
   assertEquals(ListenableWorker.Result.retry().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","deadline_exceeded").put("status","retry"),attempt).getClass());
   assertEquals(ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","completed").put("status","failure"),attempt).getClass());
   assertEquals(ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","handler_exception").put("status","retry"),attempt).getClass());
  }
 }

 @Test public void cpuDebtPersistsAndStopsGuestAdmissionUntilProgress()throws Exception {
  try {
   PodCloudWork.chargeCpu(context,10000,false);long due=PodCloudWork.cpuNotBefore(context);
   assertTrue(due-System.currentTimeMillis()>=199000);
   Method method=PodCloudWork.class.getDeclaredMethod("manualWork",WindowedWorkQueue.Job.class,long.class);method.setAccessible(true);
   WindowedWorkQueue.Job job=new WindowedWorkQueue.Job("cpu",java.util.UUID.randomUUID().toString(),null,0,System.currentTimeMillis(),false,false);
   OneTimeWorkRequest request=(OneTimeWorkRequest)method.invoke(null,job,due);
   assertTrue(request.getWorkSpec().initialDelay>=199000);
   PodCloudWorker worker=TestWorkerBuilder.from(context,PodCloudWorker.class,new SynchronousExecutor()).build();
   assertEquals(ListenableWorker.Result.retry().getClass(),worker.doWork().getClass());
   // A rejected enqueue cannot reset persisted debt.
   try{PodCloudWork.enqueue(context,"bad token");fail();}catch(IllegalArgumentException expected){}
   assertEquals(due,PodCloudWork.cpuNotBefore(context));
   PodCloudWork.chargeCpuFor(context,"stale",0,true);assertEquals(due,PodCloudWork.cpuNotBefore(context));
   PodCloudWork.chargeCpu(context,0,true);assertEquals(0,PodCloudWork.cpuNotBefore(context));
  }finally{PodCloudWork.chargeCpu(context,0,true);}
 }

}
