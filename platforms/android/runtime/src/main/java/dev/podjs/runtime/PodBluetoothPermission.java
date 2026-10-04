package dev.podjs.runtime;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Main-thread coordinator. Cancellation drops the guest callback, never
 * fabricates a permission result or dismisses the system's user decision. */
final class PodBluetoothPermission {
    static final int REQUEST=0x5042;
    interface Callback {void complete(String state);void fail(String code,String message);}
    private final Context context;
    private final int requestCode;
    private final String[] permissions;
    private Callback pending;
    private int pendingId;
    private boolean inFlight;
    PodBluetoothPermission(Context context){this(context,REQUEST,Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_CONNECT}:new String[0]);}
    PodBluetoothPermission(Context context,int requestCode,String[] permissions){this.context=context;this.requestCode=requestCode;this.permissions=permissions.clone();}
    String status(){for(String permission:permissions)if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return "required";return "granted";}
    void request(int requestId,Callback callback){
        if(inFlight){callback.fail("busy","Bluetooth permission request already active");return;}
        if("granted".equals(status())){callback.complete("granted");return;}
        if(!(context instanceof Activity)){callback.fail("unavailable","Bluetooth permission requires an Activity");return;}
        Activity activity=(Activity)context;if(activity.isFinishing()||activity.isDestroyed()){callback.fail("unavailable","Activity is unavailable");return;}
        pending=callback;pendingId=requestId;inFlight=true;
        try{activity.requestPermissions(permissions,requestCode);}
        catch(RuntimeException error){pending=null;inFlight=false;callback.fail("unavailable","Cannot request Bluetooth permission");}
    }
    void result(int requestCode){if(requestCode!=this.requestCode)return;inFlight=false;Callback callback=pending;pending=null;if(callback!=null)callback.complete(status());}
    void cancel(int requestId){if(pendingId==requestId)pending=null;}
    void cancel(){pending=null;}
}
