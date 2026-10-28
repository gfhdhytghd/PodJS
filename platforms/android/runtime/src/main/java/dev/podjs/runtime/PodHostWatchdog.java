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
    PodHostWatchdog(Handler main){
        this.main=main;
        observer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"PodWatchdog");t.setDaemon(true);return t;});
        observer.scheduleWithFixedDelay(this::tick,250,250,TimeUnit.MILLISECONDS);
    }
    private static void log(String value){if(value!=null)Log.w("PodWatchdog",value);}
    // A suspended device cannot acknowledge anything. Exclude deep sleep,
    // matching the main Looper's uptime clock, rather than counting it as ANR.
    private static long awakeNanos(){return SystemClock.uptimeMillis()*1_000_000L;}
    private synchronized void tick(){
        if(closed)return;
        long now=awakeNanos();log(state.observe(now));
        long token=state.probe(now);
        if(token==0)return;
        ping=()->log(state.acknowledge(token,awakeNanos()));
        main.post(ping);
    }
    @Override public synchronized void close(){
        if(closed)return;
        closed=true;state.active(false);
        if(ping!=null)main.removeCallbacks(ping);
        observer.shutdownNow();Log.i("PodWatchdog",state.summary());
    }
}
