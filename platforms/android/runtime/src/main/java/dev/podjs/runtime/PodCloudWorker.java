package dev.podjs.runtime;

import android.content.Context;
import androidx.work.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Verified, manifest-authorized headless cloud guest. A narrowly enumerated host route
 * supplies data services; no Activity, frame loop, or guest-selected code. */
public final class PodCloudWorker extends Worker {
    private volatile PodBackgroundRun active;
    private volatile PodServices services;
    static final Set<String> METHODS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "app.buildInfo","cloud.acquire","cloud.release","discovery.networkSnapshot","runtime.delay",
        "sql.query","sql.execute","sql.transaction","secure.get","secure.set","secure.delete","secure.update","secure.compareAndSet","secure.acquire","secure.release",
        "crypto.random","crypto.hash","crypto.hmacSha256","crypto.verifyP256","crypto.ecdhPublicKey","crypto.ecdhUnwrap","crypto.hkdfSha256",
        "crypto.aesGcmEncrypt","crypto.aesGcmDecrypt","crypto.largeAesGcmEncrypt","crypto.largeAesGcmDecrypt","crypto.gzip","crypto.gunzip",
        "log.append","http.download","uri.parse","file.list","file.createTemp","file.read","file.write","file.stat","file.hash","file.commit","file.delete",
        "file.gzip","file.gunzip","file.htmlText","file.openText","file.readTextChunk","file.closeText"
    )));
    public PodCloudWorker(Context context,WorkerParameters params){super(context,params);}
    /** A hard guest budget/failure is deterministic work, not a network retry.
     * Periodic WorkManager failure waits for the next normal period; only an
     * explicitly completed handler retry gets the short bounded backoff. */
    static Result resultFor(JSONObject outcome,int attempts){
        if(!"completed".equals(outcome.optString("code")))return Result.failure();
        if("success".equals(outcome.optString("status")))return Result.success();
        if("retry".equals(outcome.optString("status"))&&attempts<3)return Result.retry();
        return Result.failure();
    }
    @Override public Result doWork(){
        Context context=getApplicationContext();String manualToken=getInputData().getString("manualToken");
        if(manualToken!=null&&!manualToken.matches("[a-zA-Z0-9_-]{1,96}"))return Result.failure();
        // Capture only the opaque queue token for a periodic window. A later
        // outcome cannot continue or clear a different/newer grant.
        String queuedToken=manualToken!=null?manualToken:PodCloudWork.pendingToken(context),requestId=getId().toString();
        try{
            if(PodCloudWork.held(context)){PodCloudWork.deferHeld(context,queuedToken);return Result.success();}
            if(manualToken!=null&&!PodCloudWork.start(context,manualToken,requestId))return Result.success();
            if(!PodTakeover.ready(context)){if(queuedToken!=null){PodCloudWork.next(context,queuedToken,requestId,false,manualToken==null);return Result.success();}return getRunAttemptCount()<3?Result.retry():Result.failure();}
        }catch(Exception unavailable){return getRunAttemptCount()<3?Result.retry():Result.failure();}
        ExecutorService executor=Executors.newSingleThreadExecutor();
        // Only trusted method names and aggregate counts: never host arguments,
        // credentials, guest source, request bodies or exception messages.
        long began=android.os.SystemClock.elapsedRealtime(),lastReport=began;
        Map<String,Integer> calls=new TreeMap<>();String status="failed";
        try{
            PodBackgroundPackage approved=PodCloudWork.installed(context);
            String route=approved.cloudHostMethod(PodCloudWork.debug(context));
            String source=approved.cloudSource(PodCloudWork.debug(context));
            try(PodBackgroundRun run=new PodBackgroundRun(context.getPackageName(),PodCloudWork.HANDLER,source,300000,256*1024*1024,manualToken==null?JSONObject.NULL:new JSONObject().put("manual",true).put("token",manualToken),new String[]{route,"kv.get"},PodTakeover.dataRoot(context).getAbsolutePath())){
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
                        if(PodCloudWork.held(context)){PodCloudWork.deferHeld(context,queuedToken);run.cancel();result.get(10,TimeUnit.SECONDS);status="local_transfer_held";return Result.success();}
                        if(isStopped()){run.cancel();break;}
                        JSONObject request=run.pollRequest();
                        if(request==null){Thread.sleep(2);continue;}
                        JSONObject args=request.optJSONObject("args");String method=args==null?"":args.optString("method");
                        if(!route.equals(request.optString("method"))||!METHODS.contains(method)){
                            calls.merge("permission_denied",1,Integer::sum);
                            run.reply(new JSONObject().put("id",request.getLong("id")).put("ok",false).put("code","permission_denied"));continue;
                        }
                        calls.merge(method,1,Integer::sum);
                        long now=android.os.SystemClock.elapsedRealtime();
                        if(now-lastReport>=30000){android.util.Log.i("PodJSCloudWorker","progress elapsedMs="+(now-began)+" calls="+calls);lastReport=now;}
                        host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",request.getInt("id")).put("method",method).put("args",args.getJSONObject("args")));
                    }
                    JSONObject outcome=result.get(10,TimeUnit.SECONDS);
                    if(isStopped()){status="stopped";if(queuedToken!=null){PodCloudWork.next(context,queuedToken,requestId,false,manualToken==null);return Result.success();}return getRunAttemptCount()<3?Result.retry():Result.failure();}
                    String code=outcome.optString("code"),guestStatus=outcome.optString("status");
                    status="deadline_exceeded".equals(code)?"deadline_exceeded":"completed".equals(code)&&"success".equals(guestStatus)?"success":"completed".equals(code)&&"retry".equals(guestStatus)?"retry":"guest_failed";
                    if("completed".equals(code)&&"retry".equals(guestStatus)&&queuedToken!=null){
                        boolean progress=outcome.optBoolean("progress",false);
                        // APPEND_OR_REPLACE attaches the fresh request to this
                        // one. Success releases it; REPLACE here would cancel us.
                        PodCloudWork.next(context,queuedToken,requestId,progress,manualToken==null);
                        status=progress?"continued":"backoff";return Result.success();
                    }
                    Result mapped=resultFor(outcome,getRunAttemptCount());
                    if(!(mapped instanceof Result.Retry))PodCloudWork.finished(context,manualToken,requestId);
                    return mapped;
                }finally{services=null;active=null;}
            }
        }catch(Exception error){
            try{if(queuedToken!=null){if(!PodCloudWork.next(context,queuedToken,requestId,false,manualToken==null))PodCloudWork.resumeHeld(context);status="backoff";return Result.success();}}catch(Exception schedulingFailed){status="queue_failed";return getRunAttemptCount()<3?Result.retry():Result.failure();}
            return getRunAttemptCount()<3?Result.retry():Result.failure();
        }
        finally{executor.shutdownNow();android.util.Log.i("PodJSCloudWorker","finished status="+status+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-began)+" calls="+calls);}
    }
    @Override public void onStopped(){PodBackgroundRun run=active;if(run!=null)run.cancel();PodServices host=services;if(host!=null)host.close();}
}
