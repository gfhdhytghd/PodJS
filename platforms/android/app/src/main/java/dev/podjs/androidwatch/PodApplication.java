package dev.podjs.androidwatch;

import android.app.Application;
import androidx.work.Configuration;
import dev.podjs.runtime.PodStartupTrace;

/** Initialize WorkManager at its first host scheduler call, with legacy worker compatibility. */
public final class PodApplication extends Application implements Configuration.Provider {
    @Override public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().setWorkerFactory(dev.podjs.runtime.PodWorkManagerTakeover.factory(this)).build();
    }
    @Override public void onCreate() {
        long began = PodStartupTrace.now();
        super.onCreate();
        PodStartupTrace.traceMainQueue(this);
        android.util.Log.i("PodJSPerf", "startup stage=application processToOnCreateMs="
            + (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())+" processStartMs="+android.os.Process.getStartElapsedRealtime()+" atMs="+android.os.SystemClock.elapsedRealtime()+" epochMs="+System.currentTimeMillis());
        PodStartupTrace.stage("applicationOnCreate", began);
    }
}
