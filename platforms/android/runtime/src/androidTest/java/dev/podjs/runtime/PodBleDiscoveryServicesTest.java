package dev.podjs.runtime;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.CancellationSignal;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public final class PodBleDiscoveryServicesTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private JSONObject request(int timeout)throws Exception{return new JSONObject().put("advertisedServiceUuid","761a11e1-2040-4140-a283-20739182a120").put("manufacturerId",65535).put("manufacturerDataBase64","Tk9ORS1GT1JFTlNJRw==").put("serviceUuid","761a11e2-2040-4140-a283-20739182a120").put("characteristicUuid","761a11e3-2040-4140-a283-20739182a120").put("scanTimeoutMs",timeout).put("readTimeoutMs",1000);}
    @Test public void missingPermissionsNeverStartScan()throws Exception{
        Context denied=new ContextWrapper(context()){@Override public int checkSelfPermission(String p){return PackageManager.PERMISSION_DENIED;}};
        try(PodBleDiscoveryServices service=new PodBleDiscoveryServices(denied)){
            assertEquals("required",service.execute("discovery.status",new JSONObject(),new CancellationSignal()).getString("permission"));
            try{service.execute("discovery.bleReadCharacteristic",request(100),new CancellationSignal());fail("missing permission accepted");}catch(SecurityException expected){}
        }
    }
    @Test public void timeoutAndCancellationReleaseScan()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(PodBleDiscoveryServices service=new PodBleDiscoveryServices(context())){
            try{service.execute("discovery.bleReadCharacteristic",request(100),new CancellationSignal());fail("unexpected peer");}catch(IllegalStateException expected){assertEquals("BLE scan timed out",expected.getMessage());}
            CancellationSignal signal=new CancellationSignal();Future<?> task=worker.submit(()->{try{service.execute("discovery.bleReadCharacteristic",request(30000),signal);throw new AssertionError("unexpected peer");}catch(IllegalStateException expected){assertEquals("BLE discovery closed",expected.getMessage());}catch(Exception error){throw new RuntimeException(error);}});
            java.lang.reflect.Field field=PodBleDiscoveryServices.class.getDeclaredField("reads");field.setAccessible(true);Object read=null;long until=SystemClock.elapsedRealtime()+2000;
            while(read==null&&SystemClock.elapsedRealtime()<until){synchronized(service){java.util.Set<?> pending=(java.util.Set<?>)field.get(service);if(!pending.isEmpty())read=pending.iterator().next();}if(read==null)Thread.sleep(1);}assertNotNull(read);
            java.lang.reflect.Field scanning=read.getClass().getDeclaredField("scanning");scanning.setAccessible(true);boolean started=false;
            while(!started&&SystemClock.elapsedRealtime()<until){synchronized(read){started=scanning.getBoolean(read);}if(!started)Thread.sleep(1);}assertTrue(started);signal.cancel();task.get(2,TimeUnit.SECONDS);synchronized(read){assertFalse(scanning.getBoolean(read));}
        }finally{worker.shutdownNow();}
    }
}
