package dev.podjs.runtime;

/** Monotonic startup timings. Values contain no guest data. */
public final class PodStartupTrace {
    private PodStartupTrace() {}
    public static long now() { return android.os.SystemClock.elapsedRealtimeNanos(); }
    public static void stage(String name, long began) {
        android.util.Log.i("PodJSPerf", "startup stage=" + name + " elapsedMs=" + ms(now() - began)+" beginMs="+ms(began)+" atMs="+ms(now())+" epochMs="+System.currentTimeMillis());
    }
    public static double ms(long nanos) { return nanos / 1_000_000.0; }
}
