package dev.podjs.runtime;

import android.content.Context;
import androidx.work.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Verified, debug-only headless cloud guest. A narrowly enumerated host route
 * supplies data services; no Activity, frame loop, or guest-selected code. */
public final class PodCloudWorker extends Worker {
    private volatile PodBackgroundRun active;
    private volatile PodServices services;
    static final Set<String> METHODS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "app.buildInfo","cloud.acquire","cloud.release","discovery.networkSnapshot",
        "sql.query","sql.execute","sql.transaction","secure.get","secure.set","secure.delete",
        "crypto.random","crypto.hash","crypto.ecdhPublicKey","crypto.ecdhUnwrap","crypto.hkdfSha256",
        "crypto.largeAesGcmEncrypt","crypto.largeAesGcmDecrypt","crypto.gzip","crypto.gunzip",
        "http.download","uri.parse","file.list","file.createTemp","file.read","file.write","file.stat","file.hash","file.commit","file.delete",
        "file.gzip","file.gunzip","file.htmlText","file.openText","file.readTextChunk","file.closeText"
    )));
    public PodCloudWorker(Context context,WorkerParameters params){super(context,params);}
    @Override public Result doWork(){
        Context context=getApplicationContext();
        if(!PodCloudWork.debug(context)){WorkManager.getInstance(context).cancelUniqueWork(PodCloudWork.NAME);return Result.success();}
        if(!PodTakeover.ready(context))return Result.retry();
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            String source=PodCloudWork.installed(context).cloudSource();
            try(PodBackgroundRun run=new PodBackgroundRun(context.getPackageName(),PodCloudWork.HANDLER,source,300000,256*1024*1024,JSONObject.NULL,new String[]{"debug.cloudHost","kv.get"},PodTakeover.dataRoot(context).getAbsolutePath())){
                active=run;
                try(PodServices host=new PodServices(context,event->{
                    try{JSONObject e=new JSONObject(event);if(!"service.result".equals(e.optString("t")))return;
                        JSONObject reply=new JSONObject().put("id",e.getLong("id")).put("ok",e.getBoolean("ok"));
                        if(e.getBoolean("ok"))reply.put("value",e.opt("value"));else {String message=e.optString("message",e.optString("code","host_error"));reply.put("code",message.substring(0,Math.min(128,message.length())));}
                        run.reply(reply);
                    }catch(Exception error){run.cancel();}
                })){
                    services=host;if(isStopped())run.cancel();
                    Future<JSONObject> result=executor.submit(run::run);
                    while(!result.isDone()){
                        if(isStopped()){run.cancel();break;}
                        JSONObject request=run.pollRequest();
                        if(request==null){Thread.sleep(2);continue;}
                        JSONObject args=request.optJSONObject("args");String method=args==null?"":args.optString("method");
                        if(!"debug.cloudHost".equals(request.optString("method"))||!METHODS.contains(method)||!PodCloudWork.debug(context)){
                            run.reply(new JSONObject().put("id",request.getLong("id")).put("ok",false).put("code","permission_denied"));continue;
                        }
                        host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",request.getInt("id")).put("method",method).put("args",args.getJSONObject("args")));
                    }
                    JSONObject outcome=result.get(10,TimeUnit.SECONDS);
                    if(isStopped())return Result.retry();
                    if("success".equals(outcome.optString("status")))return Result.success();
                    return getRunAttemptCount()<3?Result.retry():Result.failure();
                }finally{services=null;active=null;}
            }
        }catch(Exception error){return getRunAttemptCount()<3?Result.retry():Result.failure();}
        finally{executor.shutdownNow();}
    }
    @Override public void onStopped(){PodBackgroundRun run=active;if(run!=null)run.cancel();PodServices host=services;if(host!=null)host.close();}
}
