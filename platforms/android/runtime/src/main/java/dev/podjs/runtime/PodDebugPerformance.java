package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;
import androidx.metrics.performance.JankStats;
import org.json.JSONObject;

/** Debug-only, guest-scoped collector. Ownership changes run on main;
 * JankStats callbacks are synchronized and tagged with their session. */
final class PodDebugPerformance implements AutoCloseable {
    private Window window;
    private JankStats stats;
    private Window.OnFrameMetricsAvailableListener listener;
    private String scenario = "";
    private long frames, jank, total, max, dropped;
    private final long[] detailed = new long[9];
    private volatile long sessionId;
    private volatile boolean resumed = true;
    private volatile boolean collecting;

    JSONObject execute(Context context, String method, JSONObject args) throws Exception {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)==0)
            throw new SecurityException("Performance tools require a debug build");
        if (method.equals("debug.performance.start")) {
            close(); clear(); scenario=args.optString("scenario", "debug");
            Context owner=context;
            java.util.Set<Context> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while (!(owner instanceof Activity) && owner instanceof ContextWrapper && seen.add(owner)) owner=((ContextWrapper)owner).getBaseContext();
            if (!(owner instanceof Activity)) throw new IllegalStateException("Activity unavailable");
            window=((Activity)owner).getWindow();
            final long epoch=++sessionId;
            collecting=true;
            try {
                stats=JankStats.createAndTrack(window, frame -> {
                    synchronized(this){
                        if (!resumed || !collecting || sessionId!=epoch) return;
                        frames++; if(frame.isJank()) jank++;
                        long duration=frame.getFrameDurationUiNanos();total+=duration;max=Math.max(max,duration);
                    }
                });
                stats.setTrackingEnabled(resumed);
                listener=(w, frame, drop)->recordDetailed(epoch,frame,drop);
                window.addOnFrameMetricsAvailableListener(listener,new Handler(Looper.getMainLooper()));
            } catch(RuntimeException error) {close();throw error;}
        } else {
            if (!method.equals("debug.performance.read") && !method.equals("debug.performance.stop"))
                throw new IllegalArgumentException("Unknown performance operation");
            if(args.optLong("sessionId",-1)!=sessionId) throw new IllegalArgumentException("Stale performance session");
        }
        if(method.equals("debug.performance.stop")) close();
        return snapshot();
    }
    private synchronized void recordDetailed(long epoch,FrameMetrics frame,int drop) {
        if (!resumed || !collecting || sessionId!=epoch) return;
        int[] keys = {FrameMetrics.TOTAL_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.ANIMATION_DURATION, FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.GPU_DURATION};
        for (int i=0;i<keys.length;i++) detailed[i]=Math.max(0,frame.getMetric(keys[i]));
        dropped+=drop;
        if(detailed[0]>=16000000L) {
            StringBuilder line=new StringBuilder("scenario=").append(scenario).append(" frame=").append(frames);
            String[] names={"total","layout","draw","sync","cmd","swap","anim","input","gpu"};
            for(int i=0;i<names.length;i++)line.append(' ').append(names[i]).append('=').append(detailed[i]/1e6).append("ms");
            android.util.Log.d("PodJS_Perf",line.append(" drop=").append(drop).toString());
        }
    }
    private synchronized JSONObject snapshot() throws Exception {
        JSONObject value=new JSONObject().put("source","Android JankStats / FrameMetrics").put("sessionId",sessionId).put("scenario",scenario)
            .put("frames",frames).put("jank",jank).put("jankPct",frames==0?0:jank*100.0/frames)
            .put("avgMs",frames==0?0:total/1e6/frames).put("maxMs",max/1e6).put("drop",dropped);
        String[] names={"totalMs","layoutMs","drawMs","syncMs","commandMs","swapMs","animationMs","inputMs","gpuMs"};
        JSONObject last=new JSONObject();for(int i=0;i<names.length;i++)last.put(names[i],detailed[i]/1e6);
        return value.put("lastFrame",last);
    }
    void lifecycle(boolean active){resumed=active;if(stats!=null)stats.setTrackingEnabled(active);}
    private synchronized void clear(){frames=jank=total=max=dropped=0;java.util.Arrays.fill(detailed,0);}
    @Override public void close(){collecting=false;if(stats!=null){stats.setTrackingEnabled(false);stats=null;}if(window!=null){if(listener!=null)window.removeOnFrameMetricsAvailableListener(listener);window=null;listener=null;}}
}
