package dev.podjs.runtime;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One coalesced ping and one bounded observer per live view, in release too. */
final class PodHostWatchdog implements AutoCloseable {
    final MainThreadWatchdog state=new MainThreadWatchdog();
    private final Handler main;
    private final ScheduledExecutorService observer;
    private Runnable ping;
    private boolean closed;
    private static final long JS_STACK_DELAY_MS=300;
    private long pingToken,stackToken;
    interface StackSampler {
        void request(int phase,long delayMs);
        void cancel();
        void close();
    }
    private final StackSampler stacks;
    PodHostWatchdog(Handler main){
        this(main,null);
    }
    PodHostWatchdog(Handler main,StackSampler stacks){
        this.main=main;
        this.stacks=stacks;
        observer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"PodWatchdog");t.setDaemon(true);return t;});
        long period=stacks==null?250:100;
        observer.scheduleWithFixedDelay(this::tick,period,period,TimeUnit.MILLISECONDS);
    }
    private static void log(String value){if(value!=null)Log.w("PodWatchdog",value);}
    // A suspended device cannot acknowledge anything. Exclude deep sleep,
    // matching the main Looper's uptime clock, rather than counting it as ANR.
    private static long awakeNanos(){return SystemClock.uptimeMillis()*1_000_000L;}
    private synchronized void tick(){
        if(closed)return;
        long now=awakeNanos();String report=state.observe(now);log(report);
        // Diagnostic sampling precedes the unchanged one-second stall counter.
        // One sample per outstanding ping prevents flooding a single long job.
        long delay=state.delayMs(now);
        if(stacks!=null&&delay>=JS_STACK_DELAY_MS&&stackToken!=pingToken){
            stackToken=pingToken;stacks.request(state.phaseCode(),delay);
        }
        long token=state.probe(now);
        if(token==0)return;
        pingToken=token;
        ping=()->acknowledge(token);
        main.post(ping);
    }
    private synchronized void acknowledge(long token){
        if(closed)return;
        log(state.acknowledge(token,awakeNanos()));
        if(stacks!=null&&token==pingToken)stacks.cancel();
    }
    @Override public synchronized void close(){
        if(closed)return;
        closed=true;state.active(false);
        if(ping!=null)main.removeCallbacks(ping);
        observer.shutdownNow();Log.i("PodWatchdog",state.summary());
        // Same lock as tick/ack: no request can race native handle destruction.
        if(stacks!=null){stacks.cancel();stacks.close();}
    }
}
