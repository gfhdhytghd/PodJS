package dev.podjs.runtime;

import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real service commands and OnlineProvisioningSession; in-memory HTTP only. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class PodProvisionCancellationTest {
    static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static class Relay implements OnlineProvisioningSession.Http {
        final CountDownLatch io=new CountDownLatch(1),aborted=new CountDownLatch(1);
        final List<String> reasons=new CopyOnWriteArrayList<>();
        public OnlineProvisioningSession.Reply exchange(String method,String path,String token,byte[] body)throws java.io.IOException{
            JSONObject value=new JSONObject();
            try{
                if(path.equals(OnlineProvisioningSession.SESSIONS))value.put("sessionId",new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("sessionId")).put("watchToken",String.join("",Collections.nCopies(64,"a"))).put("claimToken",String.join("",Collections.nCopies(64,"b"))).put("expiresAt",100);
                else if(path.endsWith("/close"))reasons.add(new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("reason"));
                else {io.countDown();if(!aborted.await(3,TimeUnit.SECONDS))throw new java.io.IOException("fixture wait expired");}
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}catch(org.json.JSONException e){throw new java.io.IOException(e);}
            return new OnlineProvisioningSession.Reply(200,value.toString().getBytes(StandardCharsets.UTF_8));
        }
        public void abort(){aborted.countDown();}
    }
    static JSONObject request(int id,String method,JSONObject args)throws Exception{return new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method).put("args",args);}
    static JSONObject cancel(int id,Object reason)throws Exception{return new JSONObject().put("t","service.cancel").put("id",id).put("reason",reason);}
    static boolean cleared(OnlineProvisioningSession session)throws Exception{return Arrays.equals((byte[])get(session,"secret"),new byte[32]);}
    static OnlineProvisioningSession install(PodServices services,Relay relay,String owner)throws Exception{
        OnlineProvisioningSession s=new OnlineProvisioningSession("fixture-watch","fixture-install",relay,()->0,Runnable::run);s.start();
        Object host=get(services,"provisioning");set(host,"session",s);set(host,"sessionId",s.sessionId());set(host,"startOwner",owner);return s;
    }
    @Test(timeout=15000) public void serviceCancelBypassesFullPoolWithReasonAndClearsSecret()throws Exception{
        Relay relay=new Relay();CountDownLatch release=new CountDownLatch(1),occupied=new CountDownLatch(8);
        try(PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),line->{})){
            OnlineProvisioningSession session=install(services,relay,"7");ExecutorService pool=(ExecutorService)get(services,"streamWorkers");
            try{
                for(int i=0;i<8;i++)pool.submit(()->{occupied.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                assertTrue(occupied.await(2,TimeUnit.SECONDS));services.dispatch(request(8,"provision.receive",new JSONObject().put("sessionId",session.sessionId())));assertTrue(relay.io.await(2,TimeUnit.SECONDS));
                services.dispatch(cancel(8,"user_back"));
                assertTrue(cleared(session));assertEquals(0,relay.aborted.getCount());assertEquals(1,release.getCount());assertEquals(Collections.singletonList("user_back"),relay.reasons);
                services.dispatch(cancel(8,"background"));
                services.dispatch(request(9,"provision.close",new JSONObject().put("sessionId",session.sessionId()).put("reason","user_back")));assertEquals(1,relay.reasons.size());
            }finally{release.countDown();}
        }
    }
    @Test(timeout=15000) public void explicitCloseWithoutPendingOperationBypassesAllNineWorkers()throws Exception{
        Relay relay=new Relay();CountDownLatch release=new CountDownLatch(1),occupied=new CountDownLatch(9);
        try(PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),line->{})){
            OnlineProvisioningSession session=install(services,relay,"7");ExecutorService pool=(ExecutorService)get(services,"streamWorkers");
            try{
                for(int i=0;i<9;i++)pool.submit(()->{occupied.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                assertTrue(occupied.await(2,TimeUnit.SECONDS));
                for(int i=0;i<64;i++)services.dispatch(request(100+i,"runtime.delay",new JSONObject().put("milliseconds",120000)));
                services.dispatch(request(9,"provision.close",new JSONObject().put("sessionId",session.sessionId()).put("reason","user_declined")));
                assertTrue(cleared(session));assertEquals(1,release.getCount());assertEquals(Collections.singletonList("user_declined"),relay.reasons);
            }finally{release.countDown();}
        }
    }
    @Test public void cancelAfterStartRequestRemovalStillClosesUndeliveredSessionWithReason()throws Exception{
        for(Object reason:new Object[]{"background","user_back","invalid",123,JSONObject.NULL,null}){
            Relay relay=new Relay();try(PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),line->{})){
                OnlineProvisioningSession session=install(services,relay,"7");assertFalse(cleared(session));
                services.dispatch(cancel(6,"user_back"));assertFalse(cleared(session));
                services.dispatch(cancel(7,reason));assertTrue(cleared(session));
                assertEquals(Collections.singletonList("background".equals(reason)||"user_back".equals(reason)?reason:"error"),relay.reasons);
                services.dispatch(cancel(7,"timeout"));assertEquals(1,relay.reasons.size());
            }
        }
    }
}
