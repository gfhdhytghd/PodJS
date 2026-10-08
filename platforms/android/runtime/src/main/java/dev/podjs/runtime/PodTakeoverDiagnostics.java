package dev.podjs.runtime;

/** One release-safe line: only fixed message codes and file categories are projected. */
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
        String message=error.getMessage();
        String code=message!=null&&message.matches("[a-z0-9_]{1,64}")?" code="+message:"";
        String category=error instanceof PodFileConverter.FileFailure?" category="+((PodFileConverter.FileFailure)error).category:"";
        return "phase="+phase+" exception="+(error instanceof PodFileConverter.FileFailure?((PodFileConverter.FileFailure)error).exceptionClass:error.getClass().getName())+" frame="+frame+code+category;
    }
    static void warn(String phase,Exception error) {
        android.util.Log.w("PodTakeover",line(phase,error));
    }
}
