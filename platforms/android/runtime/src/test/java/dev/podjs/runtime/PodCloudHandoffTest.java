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
 @Test public void immediateManualWorkHasConnectedConstraintOpaquePayloadAndBoundedBackoff(){OneTimeWorkRequest request=PodCloudWork.manualWork("opaque_1");assertEquals(0,request.getWorkSpec().initialDelay);assertEquals(NetworkType.CONNECTED,request.getWorkSpec().constraints.getRequiredNetworkType());assertEquals("opaque_1",request.getWorkSpec().input.getString("manualToken"));assertEquals(1,request.getWorkSpec().input.getKeyValueMap().size());assertEquals(30000,request.getWorkSpec().backoffDelayDuration);assertEquals(BackoffPolicy.EXPONENTIAL,request.getWorkSpec().backoffPolicy);}
 @Test public void localBleLeaseDefersUniqueWorkerAndReleasesWithItsGuest()throws Exception {
  try(PodCloudWork first=new PodCloudWork(context);PodCloudWork second=new PodCloudWork(context)){
   hold(first,true);hold(second,true);assertTrue(PodCloudWork.held(context));PodCloudWork.enqueue(context,"opaque_2");assertTrue(PodCloudWork.pending(context).getBoolean("held",false));assertTrue(work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().isEmpty());
   first.close();assertTrue(PodCloudWork.held(context));second.close();assertFalse(PodCloudWork.held(context));assertFalse(PodCloudWork.pending(context).getBoolean("held",true));assertEquals(1,work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().size());
  }
 }
 @Test public void hardDeadlineOrGuestFailureCannotRearmPendingWork()throws Exception {
  PodCloudWork.pending(context).edit().putString("token","opaque_3").putBoolean("held",false).commit();PodCloudWork.finished(context,"old_token");assertEquals("opaque_3",PodCloudWork.pending(context).getString("token",null));
  assertEquals(ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","deadline_exceeded").put("status","retry"),0).getClass());
  PodCloudWork.finished(context,"opaque_3");PodCloudWork.resumeHeld(context);assertNull(PodCloudWork.pending(context).getString("token",null));assertTrue(work.getWorkInfosForUniqueWork(PodCloudWork.MANUAL_NAME).get().isEmpty());
 }
 @Test public void midWorkerLocalHoldRetainsOnlyMatchingContinuation()throws Exception {PodCloudWork.pending(context).edit().putString("token","opaque_4").putBoolean("held",false).commit();PodCloudWork.deferHeld(context,"old");assertFalse(PodCloudWork.pending(context).getBoolean("held",true));PodCloudWork.deferHeld(context,"opaque_4");PodCloudWork.finished(context,"opaque_4");assertEquals("opaque_4",PodCloudWork.pending(context).getString("token",null));}
}
