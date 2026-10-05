package dev.podjs.wear;

import android.app.Application;
import androidx.work.Configuration;
import dev.podjs.runtime.PodStartupTrace;

public final class PodApplication extends Application implements Configuration.Provider {
    @Override public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }
    @Override public void onCreate() {
        long began = PodStartupTrace.now();
        super.onCreate();
        android.util.Log.i("PodJSPerf", "startup stage=application processToOnCreateMs="
            + (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()));
        PodStartupTrace.stage("applicationOnCreate", began);
    }
}
