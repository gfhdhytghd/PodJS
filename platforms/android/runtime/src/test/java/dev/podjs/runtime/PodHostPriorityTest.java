package dev.podjs.runtime;

import android.app.Activity;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodHostPriorityTest {
    static JSONObject request(int id,String method)throws Exception {
        return new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method)
            .put("args",new JSONObject().put("key","synthetic").put("path","synthetic-missing"));
    }
    @Test(timeout=10000) public void secureUiBypassesBusyGeneralWorkerAndQueuedHeadlessUnits()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        List<Integer> order=new CopyOnWriteArrayList<>();
        PodServices ui=new PodServices(activity,line->{try{JSONObject e=new JSONObject(line);if("service.result".equals(e.optString("t")))order.add(e.getInt("id"));}catch(Exception e){throw new RuntimeException(e);}});
        PodServices restore=new PodServices(activity,line->{try{JSONObject e=new JSONObject(line);if("service.result".equals(e.optString("t")))order.add(e.getInt("id"));}catch(Exception e){throw new RuntimeException(e);}});
        restore.headlessWork();
        CountDownLatch release=new CountDownLatch(1);
        Field field=PodServices.class.getDeclaredField("worker");field.setAccessible(true);
        ExecutorService worker=(ExecutorService)field.get(ui);
        Future<?> busy=worker.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        HostWorkPriority.Ticket active=HostWorkPriority.DEVICE.reserve(true);active.enter();
        try {
            for(int i=0;i<32;i++)restore.dispatch(request(i+100,"file.stat"));
            ui.dispatch(request(1,"secure.get"));active.close();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(order.isEmpty()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}
            assertFalse(order.isEmpty());assertEquals(Integer.valueOf(1),order.get(0));assertFalse(busy.isDone());
        }finally{active.close();release.countDown();busy.get(2,TimeUnit.SECONDS);restore.close();ui.close();}
    }
    @Test(timeout=10000) public void cancelBeforeWorkerStartsDoesNotLeakPriorityReservation()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        PodServices services=new PodServices(activity,line->{});CountDownLatch release=new CountDownLatch(1);
        Field field=PodServices.class.getDeclaredField("secureWorker");field.setAccessible(true);
        ExecutorService worker=(ExecutorService)field.get(services);
        Future<?> busy=worker.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try {
            services.dispatch(request(1,"secure.get"));services.dispatch(new JSONObject().put("t","service.cancel").put("id",1));
            HostWorkPriority.Ticket after=HostWorkPriority.DEVICE.reserve(true);after.enter();after.close();
        }finally{release.countDown();busy.get(2,TimeUnit.SECONDS);services.close();}
    }
}
