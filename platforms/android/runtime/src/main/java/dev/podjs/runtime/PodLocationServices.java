package dev.podjs.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;

/** One foreground, explicitly requested location lookup. All methods run on main. */
final class PodLocationServices {
    static final int PERMISSION_REQUEST=0x504c;
    interface Callback {void complete(JSONObject value);void fail(String code,String message);}
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Callback pending;
    private CancellationSignal cancellation;
    private boolean permissionInFlight;
    private int generation;
    PodLocationServices(Context context){this.context=context;}
    void current(Callback callback) {
        if(pending!=null || permissionInFlight){callback.fail("busy","Location request already active");return;}
        if(!(context instanceof Activity) || ((Activity)context).isFinishing() || ((Activity)context).isDestroyed()) {callback.fail("unavailable","Location requires an active Activity");return;}
        pending=callback;
        if(granted()){locate();return;}
        permissionInFlight=true;
        try {((Activity)context).requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_REQUEST);}
        catch(RuntimeException error){permissionInFlight=false;fail("unavailable","Cannot request location permission");}
    }
    private boolean granted() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    void permissionResult(int code) {
        if(code!=PERMISSION_REQUEST)return;
        permissionInFlight=false;
        if(pending==null)return;
        if(granted())locate();else fail("permission_denied","Location permission was not granted");
    }
    @SuppressWarnings("MissingPermission")
    private void locate() {
        LocationManager manager=context.getSystemService(LocationManager.class);
        if(manager==null){complete(null);return;}
        ArrayList<String> providers=new ArrayList<>();
        Location recent=null;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER}) {
            try {if(!manager.isProviderEnabled(provider))continue;}catch(RuntimeException unavailable){continue;}
            providers.add(provider);
            try {Location candidate=manager.getLastKnownLocation(provider);if(candidate!=null && (recent==null || candidate.getTime()>recent.getTime()))recent=candidate;}catch(RuntimeException ignored){}
        }
        if(recent!=null && System.currentTimeMillis()-recent.getTime()<6*60*60*1000L || providers.isEmpty()){complete(recent);return;}
        final Location fallback=recent;
        final int lookup=++generation;
        cancellation=new CancellationSignal();
        try {
            manager.getCurrentLocation(providers.get(0),cancellation,main::post,location->{
                if(lookup!=generation || pending==null)return;
                cancellation=null;complete(location!=null?location:fallback);
            });
        } catch(SecurityException denied){fail("permission_denied","Location permission was revoked");}
        catch(RuntimeException unavailable){complete(fallback);}
    }
    private void complete(Location location) {
        Callback callback=pending;pending=null;cancellation=null;
        if(callback==null)return;
        try {callback.complete(location==null?null:new JSONObject().put("latitude",location.getLatitude()).put("longitude",location.getLongitude()).put("timestamp",location.getTime()));}
        catch(Exception error){callback.fail("unavailable","Location is unavailable");}
    }
    private void fail(String code,String message) {Callback callback=pending;pending=null;cancellation=null;if(callback!=null)callback.fail(code,message);}
    void cancel(){generation++;pending=null;if(cancellation!=null){cancellation.cancel();cancellation=null;}}
}
