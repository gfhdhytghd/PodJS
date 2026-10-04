package dev.podjs.runtime;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public final class PodNetworkServicesTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private JSONObject args(String id)throws Exception{return new JSONObject().put("subscriptionId",id);}
    private Object subscription(PodNetworkServices services,String id)throws Exception{java.lang.reflect.Field field=PodNetworkServices.class.getDeclaredField("subscriptions");field.setAccessible(true);synchronized(services){return ((java.util.Map<?,?>)field.get(services)).get(id);}}
    private boolean field(Object subscription,String name)throws Exception{java.lang.reflect.Field field=subscription.getClass().getDeclaredField(name);field.setAccessible(true);synchronized(subscription){return field.getBoolean(subscription);}}
    @Test public void realNetworkCallbackAndUnsubscribeSuppressLateDelivery()throws Exception{
        BlockingQueue<String> events=new LinkedBlockingQueue<>();
        try(PodNetworkServices services=new PodNetworkServices(context(),events::add)){
            assertEquals("one",services.execute("discovery.networkSubscribe",args("one"),new CancellationSignal()).getString("subscriptionId"));
            String event=events.poll(5,TimeUnit.SECONDS);assertNotNull("An existing device network must deliver a callback",event);JSONObject value=new JSONObject(event);assertEquals("network.changed",value.getString("t"));assertEquals("one",value.getString("subscriptionId"));assertTrue(value.getJSONObject("value").has("reason"));
            Object owner=subscription(services,"one");assertTrue(field(owner,"defaultRegistered"));assertTrue(field(owner,"wifiRegistered"));
            assertTrue(services.execute("discovery.networkUnsubscribe",args("one"),new CancellationSignal()).getBoolean("released"));assertNull(subscription(services,"one"));assertFalse(field(owner,"defaultRegistered"));assertFalse(field(owner,"wifiRegistered"));
            events.clear();java.lang.reflect.Field callback=owner.getClass().getDeclaredField("defaultCallback");callback.setAccessible(true);((ConnectivityManager.NetworkCallback)callback.get(owner)).onLost(null);assertNull(events.poll(50,TimeUnit.MILLISECONDS));
        }
    }
    @Test public void cancellationDuplicateAndOwnerCloseDoNotRetainCallbacks()throws Exception{
        try(PodNetworkServices services=new PodNetworkServices(context(),ignored->{})){
            CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();try{services.execute("discovery.networkSubscribe",args("cancelled"),cancelled);fail("cancelled subscription");}catch(android.os.OperationCanceledException expected){}assertNull(subscription(services,"cancelled"));
            services.execute("discovery.networkSubscribe",args("active"),new CancellationSignal());Object owner=subscription(services,"active");
            try{services.execute("discovery.networkSubscribe",args("active"),new CancellationSignal());fail("duplicate subscription");}catch(IllegalArgumentException expected){}assertSame(owner,subscription(services,"active"));
            services.close();assertFalse(field(owner,"defaultRegistered"));assertFalse(field(owner,"wifiRegistered"));assertNull(subscription(services,"active"));
            try{services.execute("discovery.networkSubscribe",args("closed"),new CancellationSignal());fail("closed service");}catch(IllegalStateException expected){}
        }
    }
}
