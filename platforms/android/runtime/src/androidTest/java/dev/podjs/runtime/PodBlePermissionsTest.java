package dev.podjs.runtime;

import android.Manifest;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodBlePermissionsTest {
    @Test public void nearbyPermissionsAreSpecificToOperation() {
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_CONNECT},PodBlePermissions.required(31,false,false));
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},PodBlePermissions.required(36,false,true));
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},PodBlePermissions.required(31,true,false));
    }
    @Test public void legacyLocationIsRequiredOnlyForDiscovery() {
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH,Manifest.permission.BLUETOOTH_ADMIN},PodBlePermissions.required(30,false,true));
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH,Manifest.permission.BLUETOOTH_ADMIN,Manifest.permission.ACCESS_FINE_LOCATION},PodBlePermissions.required(30,true,false));
    }
    @Test public void deniedPermissionFailsClosedAndGrantedSetPasses() {
        ContextWrapper denied=new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
        };
        assertTrue(PodBlePermissions.missing(denied,false,true).length>0);
        try { PodBlePermissions.require(denied,false,true); fail("Missing grants accepted"); } catch(SecurityException expected) { }
        ContextWrapper granted=new ContextWrapper(denied) {
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        };
        assertEquals(0,PodBlePermissions.missing(granted,true,true).length);
        PodBlePermissions.require(granted,true,true);
    }
}
