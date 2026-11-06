package dev.podjs.runtime;

import android.content.*;
import android.net.*;
import android.os.BatteryManager;
import org.json.JSONObject;

/** Device observations only. App authentication never follows radio/ACL state. */
final class PodContentConditions {
    static JSONObject snapshot(Context context)throws Exception {
        Object charging=JSONObject.NULL,wifi=JSONObject.NULL,cellular=JSONObject.NULL,internet=JSONObject.NULL;
        try{Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(battery!=null){int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);if(status!=-1)charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL;}}catch(SecurityException ignored){}
        try{ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager!=null){Network active=manager.getActiveNetwork();NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);if(caps!=null){wifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);cellular=caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);internet=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}else if(active==null){wifi=false;cellular=false;internet=false;}}}catch(SecurityException ignored){}
        return new JSONObject().put("charging",charging).put("wifi",wifi).put("cellular",cellular).put("internet",internet);
    }
}
