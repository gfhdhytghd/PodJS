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
    @Test public void snapshotSequenceAdvancesAcrossWorkers() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try(PodNetworkServices services=new PodNetworkServices(context(), ignored->{})){
            Callable<Long> sample=()->services.execute("discovery.networkSnapshot",new JSONObject(),new CancellationSignal()).getLong("snapshotSequence");
            long first=workers.submit(sample).get(5,TimeUnit.SECONDS);
            Future<Long> a=workers.submit(sample), b=workers.submit(sample);
            long second=a.get(5,TimeUnit.SECONDS), third=b.get(5,TimeUnit.SECONDS);
            assertEquals(first+1,Math.min(second,third));
            assertEquals(first+2,Math.max(second,third));
        }finally{workers.shutdownNow();}
    }

    @Test public void parsesOnlyClosedDebugStatuses(){
        assertEquals("checking",PodNetworkServices.parseDebugInternetStatus("checking"));
        assertEquals("available",PodNetworkServices.parseDebugInternetStatus(" WIFI "));
        assertEquals("unavailable",PodNetworkServices.parseDebugInternetStatus("offline"));
        assertEquals("bluetooth",PodNetworkServices.parseDebugInternetStatus("bt"));
        assertNull(PodNetworkServices.parseDebugInternetStatus("unexpected"));
        assertNull(PodNetworkServices.parseDebugInternetStatus(null));
    }
    private String shell(String command)throws Exception{
        android.os.ParcelFileDescriptor pipe=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[1024];int count;
            while((count=input.read(bytes))!=-1)out.write(bytes,0,count);
            return out.toString("UTF-8").trim();
        }
    }
    @Test public void releaseContextNeverReadsOrObservesDebugOverride()throws Exception{
        String key=PodNetworkServices.DEBUG_INTERNET_SETTING,previous=shell("settings get global "+key);
        Context release=new android.content.ContextWrapper(context()){
            @Override public Context getApplicationContext(){return this;}
            @Override public android.content.pm.ApplicationInfo getApplicationInfo(){
                android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo(super.getApplicationInfo());
                info.flags&=~android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;return info;
            }
        };
        try{
            shell("settings put global "+key+" unavailable");
            try(PodNetworkServices services=new PodNetworkServices(release,ignored->{})){
                assertFalse(services.execute("discovery.networkSnapshot",new JSONObject(),new CancellationSignal()).has("internetStatusOverride"));
                services.execute("discovery.networkSubscribe",args("release").put("observeDebugOverride",true),new CancellationSignal());
                assertFalse(field(subscription(services,"release"),"overrideRegistered"));
            }
        }finally{shell("null".equals(previous)?"settings delete global "+key:"settings put global "+key+" "+previous);}
    }
    @Test public void debugOverridePublishesFourStatesAndReleasesObserver()throws Exception{
        final String key=PodNetworkServices.DEBUG_INTERNET_SETTING;
        String previous=shell("settings get global "+key);
        BlockingQueue<String> events=new LinkedBlockingQueue<>();
        try(PodNetworkServices services=new PodNetworkServices(context(),event->{
            try{if("network.debugOverrideChanged".equals(new JSONObject(event).optString("t")))events.add(event);}
            catch(org.json.JSONException error){throw new AssertionError(error);}
        })){
            boolean debug=(context().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
            services.execute("discovery.networkSubscribe",args("override").put("observeDebugOverride",true),new CancellationSignal());
            Object owner=subscription(services,"override");assertEquals(debug,field(owner,"overrideRegistered"));
            for(String status:new String[]{"checking","unavailable","bluetooth","available"}){
                events.clear();shell("settings put global "+key+" "+status);
                JSONObject snapshot=services.execute("discovery.networkSnapshot",new JSONObject(),new CancellationSignal());
                if(debug){
                    assertEquals(status,snapshot.getString("internetStatusOverride"));
                    String event=events.poll(5,TimeUnit.SECONDS);
                    assertNotNull("setting notification",event);
                    assertEquals("override",new JSONObject(event).getString("subscriptionId"));
                }
                else assertFalse(snapshot.has("internetStatusOverride"));
            }
            services.execute("discovery.networkUnsubscribe",args("override"),new CancellationSignal());
            assertFalse(field(owner,"overrideRegistered"));
        }finally{shell("null".equals(previous)?"settings delete global "+key:"settings put global "+key+" "+previous);}
    }
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
