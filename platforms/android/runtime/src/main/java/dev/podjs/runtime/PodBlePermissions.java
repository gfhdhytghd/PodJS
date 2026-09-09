package dev.podjs.runtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;

/** Host permission policy only; never prompts, enables radio or resumes an action. */
public final class PodBlePermissions {
    private PodBlePermissions() { }
    static String[] required(int sdk,boolean scan,boolean advertise) {
        ArrayList<String> permissions=new ArrayList<>();
        if(sdk>=31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            if(scan) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if(advertise) permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            if(scan) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.toArray(new String[0]);
    }
    public static String[] missing(Context context,boolean scan,boolean advertise) {
        ArrayList<String> missing=new ArrayList<>();
        for(String permission:required(Build.VERSION.SDK_INT,scan,advertise))
            if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) missing.add(permission);
        return missing.toArray(new String[0]);
    }
    static void require(Context context,boolean scan,boolean advertise) {
        if(missing(context,scan,advertise).length!=0) throw new SecurityException("Bluetooth permissions unavailable");
    }
}
