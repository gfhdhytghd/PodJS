package dev.podjs.runtime;

public final class ServiceResultTraceTest {
    private static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        ServiceResultTrace disabled=new ServiceResultTrace(true,false);
        disabled.request(1,"file.read");check(disabled.pendingCount()==0);check(disabled.finish("file.read",Long.MAX_VALUE)==null);
        ServiceResultTrace release=new ServiceResultTrace(false,true);check(!release.enabled);
        ServiceResultTrace trace=new ServiceResultTrace(true,true);
        trace.request(1,"file.read");check(trace.result(1,false).equals("file.read"));check(trace.pendingCount()==1);
        check(trace.result(1,true).equals("file.read"));check(trace.pendingCount()==0);
        check(trace.finish("file.read",ServiceResultTrace.SLOW_NS)==null);
        String log=trace.finish("file.read",ServiceResultTrace.SLOW_NS+1);
        check(log.contains("category=file.read")&&log.contains("completed=2")&&log.contains("slow=1"));
        check(!trace.finish("sessionid=secret",ServiceResultTrace.SLOW_NS+1).contains("secret"));
        for(int i=0;i<1000;i++)trace.request(i,"signed-url-secret");
        check(trace.pendingCount()==64);check(trace.result(999,true).equals("other"));
        for(int i=0;i<200;i++)trace.finish("http.download",ServiceResultTrace.SLOW_NS+1);
        check(trace.finish("http.download",Long.MAX_VALUE)==null);check(trace.slowCount()==203);
        trace.cancel(998);trace.clear();check(trace.pendingCount()==0);
        System.out.println("ServiceResultTraceTest PASS");
    }
}
