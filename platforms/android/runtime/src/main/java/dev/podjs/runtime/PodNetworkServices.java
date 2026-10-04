package dev.podjs.runtime;

import android.content.Context;
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
    private final ConnectivityManager manager;
    private final Consumer<String> sink;
    private final Map<String,Subscription> subscriptions=new HashMap<>();
    private boolean closed;
    PodNetworkServices(Context context,Consumer<String> sink){manager=context.getSystemService(ConnectivityManager.class);this.sink=sink;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation)throws Exception{
        if("discovery.networkSnapshot".equals(method)){
            cancellation.throwIfCanceled();synchronized(this){if(closed)throw new IllegalStateException("Network observation closed");}
            Network network=manager==null?null:manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
            JSONObject result=new JSONObject().put("active",network!=null).put("internet",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).put("validated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).put("wifi",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("cellular",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).put("bluetooth",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
            cancellation.throwIfCanceled();return result;
        }
        String id=args.getString("subscriptionId");if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid network subscription");
        if("discovery.networkUnsubscribe".equals(method))return new JSONObject().put("released",discard(id));
        if(!"discovery.networkSubscribe".equals(method))throw new IllegalArgumentException("Unknown network method");
        cancellation.throwIfCanceled();if(manager==null)throw new IllegalStateException("Network observation unavailable");
        Subscription subscription=new Subscription(id);
        synchronized(this){if(closed)throw new IllegalStateException("Network observation closed");if(subscriptions.size()>=8)throw new IllegalStateException("Too many network subscriptions");if(subscriptions.containsKey(id))throw new IllegalArgumentException("Duplicate network subscription");subscriptions.put(id,subscription);}
        cancellation.setOnCancelListener(subscription::close);
        try{subscription.start();cancellation.throwIfCanceled();return new JSONObject().put("subscriptionId",id);}
        catch(Exception error){synchronized(this){subscriptions.remove(id,subscription);}subscription.close();throw error;}
        finally{cancellation.setOnCancelListener(null);}
    }
    boolean discard(String id){Subscription subscription;synchronized(this){subscription=subscriptions.remove(id);}if(subscription==null)return false;subscription.close();return true;}
    public void close(){List<Subscription> pending;synchronized(this){closed=true;pending=new ArrayList<>(subscriptions.values());subscriptions.clear();}for(Subscription subscription:pending)subscription.close();}
    private final class Subscription implements Closeable {
        final String id;boolean ended,defaultRegistered,wifiRegistered;
        Subscription(String id){this.id=id;}
        final ConnectivityManager.NetworkCallback defaultCallback=callback("default");
        final ConnectivityManager.NetworkCallback wifiCallback=callback("wifi");
        ConnectivityManager.NetworkCallback callback(String source){return new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network network){changed(source,"available");}
            public void onLost(Network network){changed(source,"lost");}
            public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){changed(source,"capabilities");}
        };}
        synchronized void start(){
            if(ended)throw new IllegalStateException("Network subscription closed");
            manager.registerDefaultNetworkCallback(defaultCallback);defaultRegistered=true;
            // Local Wi-Fi routes can be useful even without internet validation.
            manager.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),wifiCallback);wifiRegistered=true;
        }
        synchronized void changed(String source,String reason){
            if(ended)return;
            try{sink.accept(new JSONObject().put("t","network.changed").put("subscriptionId",id).put("value",new JSONObject().put("source",source).put("reason",reason)).toString());}
            catch(RuntimeException ignored){}catch(org.json.JSONException ignored){}
        }
        public synchronized void close(){
            if(ended)return;ended=true;
            if(defaultRegistered){defaultRegistered=false;try{manager.unregisterNetworkCallback(defaultCallback);}catch(RuntimeException ignored){}}
            if(wifiRegistered){wifiRegistered=false;try{manager.unregisterNetworkCallback(wifiCallback);}catch(RuntimeException ignored){}}
        }
    }
}
