package dev.podjs.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.util.function.LongSupplier;

/** Debug's in-process probe can omit protected memtrack graphics. Use the
 * system-server view for that component, without replaying its cached Java or
 * native heaps as if they were current. Neither query proves an external PSS
 * acceptance check: ActivityManager can return cached data on Android Q+.
 */
final class PodProcessMemoryReader {
    static final long SYSTEM_QUERY_INTERVAL_MS=5000;
    static final class Reading {
        final long totalKiB,graphicsKiB;
        Reading(long totalKiB,long graphicsKiB){
            if(totalKiB<=0||graphicsKiB<0||graphicsKiB>totalKiB)throw new IllegalArgumentException("Invalid process memory reading");
            this.totalKiB=totalKiB;this.graphicsKiB=graphicsKiB;
        }
        static Reading of(Debug.MemoryInfo info){
            String graphics=info.getMemoryStat("summary.graphics");
            if(graphics==null)throw new IllegalStateException("Graphics memory coverage unavailable");
            return new Reading(info.getTotalPss(),Long.parseLong(graphics));
        }
    }
    interface Source {Reading local();Reading system();}
    static final class Snapshot {
        final long totalKiB,localKiB,localGraphicsKiB,graphicsKiB,correctionKiB,protectedQueryAgeMs;
        Snapshot(Reading local,Reading system,long age){
            localKiB=local.totalKiB;localGraphicsKiB=local.graphicsKiB;graphicsKiB=Math.max(local.graphicsKiB,system.graphicsKiB);
            correctionKiB=graphicsKiB-local.graphicsKiB;
            totalKiB=Math.addExact(localKiB,correctionKiB);protectedQueryAgeMs=Math.max(0,age);
        }
        /** OWW242 admission estimate. The privileged graphics sample can lag
         * both allocation and retirement of a codec. Keep the fresh local
         * reading and bound only its system-side correction. */
        long oww242BudgetKiB(int videoDecoders){
            long graphicsFloor=24_000;
            if(videoDecoders>0)graphicsFloor=44_000;
            if(videoDecoders>1)graphicsFloor=48_000L+10_000L*(videoDecoders-2);
            long graphics=Math.max(graphicsFloor,Math.min(graphicsKiB,graphicsFloor+4096));
            return Math.addExact(localKiB,Math.max(0,graphics-localGraphicsKiB));
        }
    }
    private static PodProcessMemoryReader process;
    static synchronized PodProcessMemoryReader get(Context context){
        if(process==null){
            ActivityManager manager=(ActivityManager)context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
            process=new PodProcessMemoryReader(new Source(){
                public Reading local(){Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);return Reading.of(info);}
                public Reading system(){
                    if(manager==null)throw new IllegalStateException("System memory service unavailable");
                    Debug.MemoryInfo[] values=manager.getProcessMemoryInfo(new int[]{Process.myPid()});
                    if(values==null||values.length!=1||values[0]==null)throw new IllegalStateException("System process memory unavailable");
                    return Reading.of(values[0]);
                }
            },SystemClock::elapsedRealtime);
        }
        return process;
    }
    private final Source source;private final LongSupplier clock;
    private Reading protectedReading;private long queriedAt=-1;
    PodProcessMemoryReader(Source source,LongSupplier clock){this.source=source;this.clock=clock;}
    synchronized Snapshot snapshot(){
        long now=clock.getAsLong();
        if(protectedReading==null||now-queriedAt>=SYSTEM_QUERY_INTERVAL_MS){
            // A failed protected query does not turn into an uncorrected
            // successful sample. The owner keeps its ledger and trims/retries.
            Reading next=source.system();protectedReading=next;queriedAt=now;
        }
        return new Snapshot(source.local(),protectedReading,now-queriedAt);
    }
}
