package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.CancellationSignal;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.*;
import java.util.function.Consumer;

/** Explicit guest-owned default/local-Wi-Fi network observations. Never changes a network. */
final class PodNetworkServices implements Closeable {
    static final String DEBUG_INTERNET_SETTING="podjs.debug.internet_availability";
    private final Context context;
    private final boolean debug;
    private final ConnectivityManager manager;
    private final Consumer<String> sink;
    private final Map<String,Subscription> subscriptions=new HashMap<>();
    private boolean closed;
    private final Object snapshotLock=new Object();
    private long snapshotSequence;
    PodNetworkServices(Context context,Consumer<String> sink){this.context=context.getApplicationContext();debug=(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;manager=context.getSystemService(ConnectivityManager.class);this.sink=sink;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation)throws Exception{
        if("discovery.networkSnapshot".equals(method)){
            cancellation.throwIfCanceled();synchronized(this){if(closed)throw new IllegalStateException("Network observation closed");}
            // Keep route reads and their sequence in the same order, even
            // when discovery requests execute on different worker threads.
            synchronized(snapshotLock){
                cancellation.throwIfCanceled();
                Network network=manager==null?null:manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
                JSONObject result=new JSONObject().put("metered",manager==null||manager.isActiveNetworkMetered()).put("active",network!=null).put("internet",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).put("validated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).put("wifi",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("cellular",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).put("bluetooth",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
                boolean localWifi=false;
                if(manager!=null)for(Network candidate:manager.getAllNetworks()){
                    NetworkCapabilities local=manager.getNetworkCapabilities(candidate);
                    if(local!=null&&local.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){localWifi=true;break;}
                }
                result.put("localWifi",localWifi);
                // Preserve the real route fields for local/LAN consumers. Never honor overrides in release.
                if(debug){String forced=null;try{forced=parseDebugInternetStatus(Settings.Global.getString(context.getContentResolver(),DEBUG_INTERNET_SETTING));}catch(RuntimeException ignored){}if(forced!=null)result.put("internetStatusOverride",forced);}
                cancellation.throwIfCanceled();return result.put("snapshotSequence",++snapshotSequence);
            }
        }
        String id=args.getString("subscriptionId");if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid network subscription");
        if("discovery.networkUnsubscribe".equals(method))return new JSONObject().put("released",discard(id));
        if(!"discovery.networkSubscribe".equals(method))throw new IllegalArgumentException("Unknown network method");
        cancellation.throwIfCanceled();if(manager==null)throw new IllegalStateException("Network observation unavailable");
        Subscription subscription=new Subscription(id,debug&&args.optBoolean("observeDebugOverride",false));
        synchronized(this){if(closed)throw new IllegalStateException("Network observation closed");if(subscriptions.size()>=8)throw new IllegalStateException("Too many network subscriptions");if(subscriptions.containsKey(id))throw new IllegalArgumentException("Duplicate network subscription");subscriptions.put(id,subscription);}
        cancellation.setOnCancelListener(subscription::close);
        try{subscription.start();cancellation.throwIfCanceled();return new JSONObject().put("subscriptionId",id);}
        catch(Exception error){synchronized(this){subscriptions.remove(id,subscription);}subscription.close();throw error;}
        finally{cancellation.setOnCancelListener(null);}
    }
    static String parseDebugInternetStatus(String value){
        if(value==null)return null;
        switch(value.trim().toLowerCase(Locale.ROOT)){
            case "checking":return "checking";
            case "available":case "online":case "wifi":case "cellular":case "mobile":return "available";
            case "bluetooth":case "bt":return "bluetooth";
            case "unavailable":case "offline":case "none":return "unavailable";
            default:return null;
        }
    }
    boolean discard(String id){Subscription subscription;synchronized(this){subscription=subscriptions.remove(id);}if(subscription==null)return false;subscription.close();return true;}
    public void close(){List<Subscription> pending;synchronized(this){closed=true;pending=new ArrayList<>(subscriptions.values());subscriptions.clear();}for(Subscription subscription:pending)subscription.close();}
    private final class Subscription implements Closeable {
        final String id;boolean ended,defaultRegistered,wifiRegistered,overrideRegistered;
        final ContentObserver overrideObserver;
        Subscription(String id,boolean observeDebugOverride){
            this.id=id;
            overrideObserver=observeDebugOverride?new ContentObserver(new Handler(Looper.getMainLooper())){
                @Override public void onChange(boolean selfChange){emit("network.debugOverrideChanged",null,null);}
            }:null;
        }
        final ConnectivityManager.NetworkCallback defaultCallback=callback("default");
        final ConnectivityManager.NetworkCallback wifiCallback=callback("wifi");
        ConnectivityManager.NetworkCallback callback(String source){return new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network network){emit("network.changed",source,"available");}
            public void onLost(Network network){emit("network.changed",source,"lost");}
            public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){emit("network.changed",source,"capabilities");}
        };}
        synchronized void start(){
            if(ended)throw new IllegalStateException("Network subscription closed");
            manager.registerDefaultNetworkCallback(defaultCallback);defaultRegistered=true;
            if(overrideObserver!=null){context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(DEBUG_INTERNET_SETTING),false,overrideObserver);overrideRegistered=true;}
            // Local Wi-Fi routes can be useful even without internet validation.
            manager.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),wifiCallback);wifiRegistered=true;
        }
        synchronized void emit(String type,String source,String reason){
            if(ended)return;
            try{
                JSONObject event=new JSONObject().put("t",type).put("subscriptionId",id);
                if(source!=null)event.put("value",new JSONObject().put("source",source).put("reason",reason));
                sink.accept(event.toString());
            }
            catch(RuntimeException ignored){}catch(org.json.JSONException ignored){}
        }
        public synchronized void close(){
            if(ended)return;ended=true;
            if(overrideRegistered){overrideRegistered=false;try{context.getContentResolver().unregisterContentObserver(overrideObserver);}catch(RuntimeException ignored){}}
            if(defaultRegistered){defaultRegistered=false;try{manager.unregisterNetworkCallback(defaultCallback);}catch(RuntimeException ignored){}}
            if(wifiRegistered){wifiRegistered=false;try{manager.unregisterNetworkCallback(wifiCallback);}catch(RuntimeException ignored){}}
        }
    }
}
