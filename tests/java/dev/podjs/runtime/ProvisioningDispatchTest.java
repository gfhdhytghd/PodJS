package dev.podjs.runtime;

import java.lang.reflect.Field;
import org.json.JSONObject;

/** Compile/run against each production dispatcher, with the real encrypted online session.
 * Only Android signatures and the unused Bluetooth transport are stubbed by the runner. */
public final class ProvisioningDispatchTest {
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    public static void main(String[] args)throws Exception {
        ProvisioningTest.Relay relay=new ProvisioningTest.Relay();
        OnlineProvisioningSession session=ProvisioningTest.open(relay,new long[]{0});
        PodBleProvisionServices host=new PodBleProvisionServices(null);
        set(host,"session",session);set(host,"sessionId",session.sessionId());
        JSONObject owned=new JSONObject().put("sessionId",session.sessionId());
        try {
            // A stale guest must not probe somebody else's session.
            int calls=relay.calls.size();
            try{host.execute("provision.check",new JSONObject().put("sessionId","stale"),null);throw new AssertionError();}
            catch(IllegalStateException expected){}
            ProvisioningTest.check(relay.calls.size()==calls);
            ProvisioningTest.check(host.execute("provision.receive",owned,null).getInt("_sequence")==0);
            // R43 configure is already held. Both monitor and final consent check use
            // this route, and neither may consume the wire or change response ownership.
            for(int i=0;i<2;i++){
                JSONObject result=host.execute("provision.check",owned,null);
                ProvisioningTest.check(result.length()==1&&result.getBoolean("active"));
            }
            JSONObject response=new JSONObject().put("sessionId",session.sessionId()).put("sequence",0)
                .put("result",new JSONObject().put("success",true).put("bound",true));
            host.execute("provision.respond",response,null);
            ProvisioningTest.check(relay.received.getBoolean("bound"));
            relay.seq=1;
            ProvisioningTest.check(host.execute("provision.receive",owned,null).getInt("_sequence")==1);
            relay.phoneClosed=true;
            ProvisioningTest.failure(ProvisioningSession.Failure.EXPIRED,()->host.execute("provision.check",owned,null));
            ProvisioningTest.failure(ProvisioningSession.Failure.CLOSED,()->host.execute("provision.respond",response,null));
        }finally{host.close();}
        for(String reason:new String[]{"user_declined","user_back","background","timeout","error"}){
            ProvisioningTest.Relay r=new ProvisioningTest.Relay();
            OnlineProvisioningSession s=ProvisioningTest.open(r,new long[]{0});
            PodBleProvisionServices h=new PodBleProvisionServices(null);
            set(h,"session",s);set(h,"sessionId",s.sessionId());
            h.execute("provision.close",new JSONObject().put("sessionId",s.sessionId()).put("reason",reason),null);
            ProvisioningTest.check(reason.equals(r.closeBody.optString("reason")));
            h.close();
        }
        ProvisioningHttpTest.install();
        PodBleProvisionServices actual=new PodBleProvisionServices(null);
        JSONObject started=actual.execute("provision.start",new JSONObject().put("deviceId","d").put("installId","i").put("transport","internet").put("requestId","1").put("appVersion","watch-production-path+28"),new android.os.CancellationSignal());
        ProvisioningTest.check("watch-production-path+28".equals(ProvisioningHttpTest.connections.get(0).getRequestProperty("X-WatchRSS-App-Version")));
        actual.execute("provision.close",new JSONObject().put("sessionId",started.getString("sessionId")).put("reason","user_back"),null);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(ProvisioningHttpTest.connections.size()<2&&System.nanoTime()<deadline)Thread.yield();
        ProvisioningTest.check(ProvisioningHttpTest.connections.size()==2);
        ProvisioningHttpTest.Connection closed=ProvisioningHttpTest.connections.get(1);
        while(closed.output.size()==0&&System.nanoTime()<deadline)Thread.yield();
        ProvisioningTest.check("watch-production-path+28".equals(closed.getRequestProperty("X-WatchRSS-App-Version")));
        ProvisioningTest.check("user_back".equals(new JSONObject(closed.output.toString(java.nio.charset.StandardCharsets.UTF_8)).getString("reason")));
        actual.close();
        System.out.println("PASS: "+args[0]+" production dispatch: R43 held configure liveness, bound response, sequence, ownership and phone cancellation");
    }
}
