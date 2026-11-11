package dev.podjs.runtime;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Production dispatcher/session regressions from ALERTWRV-01/02. No network. */
public final class ProvisioningQueueTest {
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static boolean secretAlive(Object target)throws Exception{Field f=target.getClass().getDeclaredField("secret");f.setAccessible(true);return !Arrays.equals((byte[])f.get(target),new byte[32]);}
    // The fallback lets this exact regression run on the reviewed pre-repair API.
    static void cancel(PodBleProvisionServices host,String method,JSONObject args,String reason)throws Exception{
        try{host.getClass().getDeclaredMethod("cancelOperation",String.class,JSONObject.class,String.class).invoke(host,method,args,reason);}
        catch(NoSuchMethodException old){if(method.equals("provision.start"))host.cancelStart(args.getString("requestId"));else host.cancel(args.getString("sessionId"));}
    }
    static void queue()throws Exception {
        CountDownLatch io=new CountDownLatch(1),aborted=new CountDownLatch(1),occupied=new CountDownLatch(8),release=new CountDownLatch(1);
        ProvisioningTest.Relay relay=new ProvisioningTest.Relay(){
            public OnlineProvisioningSession.Reply exchange(String method,String path,String token,byte[] body){
                if(path.endsWith("/request")){io.countDown();try{ProvisioningTest.check(aborted.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}return ProvisioningTest.reply(200,new JSONObject());}
                return super.exchange(method,path,token,body);
            }
            public void abort(){aborted.countDown();}
        };
        OnlineProvisioningSession session=ProvisioningTest.open(relay,new long[]{0});
        PodBleProvisionServices host=new PodBleProvisionServices(null);set(host,"session",session);set(host,"sessionId",session.sessionId());
        ExecutorService pool=Executors.newFixedThreadPool(9);
        try{
            for(int i=0;i<8;i++)pool.submit(()->{occupied.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            ProvisioningTest.check(occupied.await(2,TimeUnit.SECONDS));
            Future<?> receive=pool.submit(()->{try{ProvisioningTest.failure(ProvisioningSession.Failure.CLOSED,()->host.execute("provision.receive",new JSONObject().put("sessionId",session.sessionId()),null));}catch(Exception e){throw new RuntimeException(e);}});
            ProvisioningTest.check(io.await(2,TimeUnit.SECONDS));
            Future<?> close=pool.submit(()->{try{host.execute("provision.close",new JSONObject().put("sessionId",session.sessionId()).put("reason","user_back"),null);}catch(Exception e){throw new RuntimeException(e);}});
            ProvisioningTest.check(!close.isDone()&&secretAlive(session));
            // Same calling-thread entry as the host's cancellation listener.
            cancel(host,"provision.receive",new JSONObject().put("sessionId",session.sessionId()),"user_back");
            ProvisioningTest.check(!secretAlive(session)&&aborted.getCount()==0&&release.getCount()==1);
            receive.get(2,TimeUnit.SECONDS);close.get(2,TimeUnit.SECONDS);
            ProvisioningTest.check("user_back".equals(relay.closeBody.optString("reason")));
            ProvisioningTest.check(relay.calls.stream().filter(v->v.endsWith("/close")).count()==1);
        }finally{release.countDown();host.close();pool.shutdownNow();}
    }
    static void start(String phase,String reason)throws Exception {
        ProvisioningTest.Relay relay=new ProvisioningTest.Relay();OnlineProvisioningSession session=ProvisioningTest.open(relay,new long[]{0});
        PodBleProvisionServices host=new PodBleProvisionServices(null);set(host,"session",session);set(host,"sessionId",session.sessionId());set(host,"starting",phase.equals("starting"));set(host,"startOwner","7");
        try{
            cancel(host,"provision.start",new JSONObject().put("requestId","7"),reason);
            ProvisioningTest.check(!secretAlive(session));
            ProvisioningTest.check(reason.equals(relay.closeBody.optString("reason")));
            cancel(host,"provision.start",new JSONObject().put("requestId","7"),reason);
            ProvisioningTest.check(relay.calls.stream().filter(v->v.endsWith("/close")).count()==1);
        }finally{host.close();}
    }
    public static void main(String[] args)throws Exception{
        int failures=0;
        try{queue();System.out.println("PASS: full nine-worker queue: direct cancellation clears secret and emits user_back exactly once");}catch(AssertionError e){failures++;System.err.println("FAIL: occupied queue reason/progress: "+e);}
        for(String phase:new String[]{"starting","started-but-undelivered"})for(String reason:new String[]{"user_back","background"})try{start(phase,reason);System.out.println("PASS: "+phase+" "+reason);}catch(AssertionError e){failures++;System.err.println("FAIL: "+phase+" "+reason+": "+e);}
        if(failures>0)throw new AssertionError(args[0]+": "+failures+" cancellation regressions failed");
    }
}
