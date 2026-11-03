package dev.podjs.runtime;

/** Monotonic startup timings. Values contain no guest data. */
public final class PodStartupTrace {
    private PodStartupTrace() {}
    public static long now() { return android.os.SystemClock.elapsedRealtimeNanos(); }
    public static void stage(String name, long began) {
        android.util.Log.i("PodJSPerf", "startup stage=" + name + " elapsedMs=" + ms(now() - began)+" beginMs="+ms(began)+" atMs="+ms(now())+" epochMs="+System.currentTimeMillis());
    }
    public static double ms(long nanos) { return nanos / 1_000_000.0; }
    /** Debug-only bounded main-queue attribution. Never log Message contents. */
    public static void traceMainQueue(android.content.Context context) {
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || !android.util.Log.isLoggable("PodBootTrace", android.util.Log.DEBUG)) return;
        android.os.Looper looper = android.os.Looper.getMainLooper();
        looper.setMessageLogging(new android.util.Printer() {
            long began; String target = "unknown";
            public void println(String line) {
                if (line.startsWith(">>>>>")) {
                    began = now();
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("Handler \\(([A-Za-z0-9_.$]+)\\)").matcher(line);
                    target = match.find() ? match.group(1) : "unknown";
                } else if (line.startsWith("<<<<<") && began != 0) {
                    if (now() - began >= 5_000_000L) stage("mainDispatch." + target, began);
                    began = 0;
                }
            }
        });
        new android.os.Handler(looper).postDelayed(() -> looper.setMessageLogging(null), 2000);
    }
}
