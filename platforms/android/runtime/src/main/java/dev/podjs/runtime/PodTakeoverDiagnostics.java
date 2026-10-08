package dev.podjs.runtime;

/** One release-safe line: exception messages, causes, URLs and source values are never logged. */
final class PodTakeoverDiagnostics {
    private PodTakeoverDiagnostics() {}
    static String line(String phase,Exception error) {
        String frame="none";
        for(StackTraceElement entry:error.getStackTrace()) {
            if(entry.getClassName().startsWith("dev.podjs.")) {
                frame=entry.getClassName()+"#"+entry.getMethodName()+":"+entry.getLineNumber();
                break;
            }
        }
        return "phase="+phase+" exception="+error.getClass().getName()+" frame="+frame;
    }
    static void warn(String phase,Exception error) {
        android.util.Log.w("PodTakeover",line(phase,error));
    }
}
