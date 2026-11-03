package dev.podjs.runtime;
import java.util.concurrent.*;
import org.json.JSONObject;
/** Real JNI wait/reply/cancel/close ownership. Synthetic guest only. */
public final class CloudPerf2WaitProbe {
 public static void main(String[] args)throws Exception{
  ExecutorService executor=Executors.newFixedThreadPool(2);
  try{
   try(PodBackgroundRun run=new PodBackgroundRun("wait-probe","echo","globalThis.backgroundHandler=async c=>{for(let i=0;i<500;i++)if(await c.request('debug.cloudHost',{i})!==i)throw Error('reply');return 'success'}",10000,8*1024*1024,JSONObject.NULL,new String[]{"debug.cloudHost"})){
    Future<JSONObject> result=executor.submit(run::run);int count=0;
    while(!result.isDone()){JSONObject request=run.waitRequest(50);if(request==null)continue;int i=request.getJSONObject("args").getInt("i");if(i!=count++)throw new AssertionError("order");if(!run.reply(new JSONObject().put("id",request.getLong("id")).put("ok",true).put("value",i)))throw new AssertionError("reply");}
    if(count!=500||!result.get().getString("status").equals("success"))throw new AssertionError("completion");
   }
   for(int i=0;i<30;i++){
    PodBackgroundRun run=new PodBackgroundRun("wait-close","cancel","globalThis.backgroundHandler=async c=>{await c.request('blocked',{});return 'success'}",10000,8*1024*1024,JSONObject.NULL,new String[]{"blocked"});
    Future<JSONObject> result=executor.submit(run::run);while(run.waitRequest(50)==null)if(result.isDone())throw new AssertionError("request missing");
    CountDownLatch entering=new CountDownLatch(1);Future<JSONObject> wait=executor.submit(()->{entering.countDown();return run.waitRequest(100);});entering.await();Thread.sleep(5);run.close();
    if(wait.get(2,TimeUnit.SECONDS)!=null)throw new AssertionError("cancel queue");if(!result.get(2,TimeUnit.SECONDS).getString("code").equals("cancelled"))throw new AssertionError("cancel result");run.close();
   }
   System.out.println("BACKGROUND_WAIT_PASS 500 replies; 30 concurrent wait/cancel/close races");
  }finally{executor.shutdownNow();}
 }
}
