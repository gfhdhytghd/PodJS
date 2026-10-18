package dev.podjs.runtime;
import android.bluetooth.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Exercises the actual production GATT callbacks, without radio or a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,manifest=Config.NONE,shadows=PodBleMtuReadTest.GattShadow.class)
public class PodBleMtuReadTest {
 static final UUID SERVICE=UUID.fromString("7e57d001-1f7d-4f0b-9f3d-2d7d3a65d001"), FIELD=UUID.fromString("7e57d001-1f7d-4f0b-9f3d-2d7d3a65d002");
 @Implements(BluetoothGatt.class)
 public static class GattShadow {
  boolean accepted=true,throwsMtu=false;int requestedMtu,reads;
  @Implementation protected BluetoothGattService getService(UUID uuid){BluetoothGattService s=new BluetoothGattService(SERVICE,0);s.addCharacteristic(new BluetoothGattCharacteristic(FIELD,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));return s;}
  @Implementation protected boolean requestMtu(int mtu){requestedMtu=mtu;if(throwsMtu)throw new IllegalStateException("rejected");return accepted;}
  @Implementation protected boolean readCharacteristic(BluetoothGattCharacteristic field){assertEquals(FIELD,field.getUuid());reads++;return true;}
 }
 private PodBleDiscoveryServices owner;private Object read;private BluetoothGattCallback callback;private BluetoothGatt gatt;private GattShadow shadow;
 @Before public void prepare()throws Exception{
  Context c=ApplicationProvider.getApplicationContext();owner=new PodBleDiscoveryServices(c);
  Class<?> cls=Class.forName("dev.podjs.runtime.PodBleDiscoveryServices$Read");Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);
  read=ctor.newInstance(owner,null,SERVICE,65535,new byte[]{1},SERVICE,FIELD,true);
  Field f=cls.getDeclaredField("gattCallback");f.setAccessible(true);callback=(BluetoothGattCallback)f.get(read);
  gatt=Shadow.newInstanceOf(BluetoothGatt.class);shadow=Shadow.extract(gatt);
 }
 @After public void close()throws Exception{Method m=read.getClass().getMethod("close");m.setAccessible(true);m.invoke(read);owner.close();}
 @Test public void legacyScanRequestsTheFineAndCoarseLocationGroup(){java.util.List<String> required=java.util.Arrays.asList(PodBleDiscoveryServices.permissions());assertTrue(required.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION));assertTrue(required.contains(android.Manifest.permission.ACCESS_FINE_LOCATION));}
 @Test public void negotiateBeforeReadAndIgnoreDuplicateMtuCallbacks(){callback.onServicesDiscovered(gatt,0);assertEquals(517,shadow.requestedMtu);assertEquals(0,shadow.reads);callback.onMtuChanged(gatt,517,0);callback.onMtuChanged(gatt,517,0);assertEquals(1,shadow.reads);}
 @Test public void rejectedMtuStartsBoundedLongRead(){shadow.accepted=false;callback.onServicesDiscovered(gatt,0);assertEquals(517,shadow.requestedMtu);assertEquals(1,shadow.reads);callback.onMtuChanged(gatt,23,0);assertEquals(1,shadow.reads);}
 @Test public void mtuExceptionFallsBackToRead(){shadow.throwsMtu=true;callback.onServicesDiscovered(gatt,0);assertEquals(1,shadow.reads);}
 @Test public void failedMtuCallbackStillReadsAtExistingMtu(){callback.onServicesDiscovered(gatt,0);callback.onMtuChanged(gatt,23,257);assertEquals(1,shadow.reads);}
}
