package dev.podjs.runtime;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Executes the production background VM; perf messages contain fixed stage names/counts. */
public final class CloudPerf2GuestProbe {
    public static void main(String[] args)throws Exception{
        String source=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(PodBackgroundRun run=new PodBackgroundRun("cloudperf-synthetic","cpu-profile",source,300000,256*1024*1024,new JSONObject().put("native",args.length<2||!"portable".equals(args[1])),new String[]{"perf","debug.cloudHost"})){
            Future<JSONObject> result=executor.submit(run::run);long at=0,cpu=0;
            while(!result.isDone()){
                JSONObject request=run.waitRequest(50);if(request==null)continue;
                JSONObject row=request.getJSONObject("args");
                if("start".equals(row.optString("event"))){at=System.nanoTime();cpu=android.os.Process.getElapsedCpuTime();System.out.println(row);}
                else if("end".equals(row.optString("event"))){row.put("elapsedNs",System.nanoTime()-at).put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpu);System.out.println(row);}
                run.reply(new JSONObject().put("id",request.getLong("id")).put("ok",true).put("value",JSONObject.NULL));
            }
            JSONObject outcome=result.get(5,TimeUnit.SECONDS);System.out.println(outcome);if(!"success".equals(outcome.optString("status")))throw new AssertionError("guest profile failed");
        }finally{executor.shutdownNow();}
    }
}
