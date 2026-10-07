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
        for(int i=0;i<ServiceResultTrace.MAX_LOGS+2;i++)trace.finish("http.download",ServiceResultTrace.SLOW_NS+1);
        check(trace.finish("http.download",Long.MAX_VALUE)==null);check(trace.slowCount()==ServiceResultTrace.MAX_LOGS+5);
        trace.cancel(998);trace.clear();check(trace.pendingCount()==0);
        trace.request(7,"sql.execute","reader.markRead","UPDATE private_table SET body='private text'");
        ServiceResultTrace.Completion completion=trace.completion(7,true,57,-1);
        String precise=trace.finish(completion,1);
        check(precise.contains("method=sql.execute")&&precise.contains("label=reader.markRead")&&precise.contains("bytes=57")&&precise.contains("rows=-1"));
        check(!precise.contains("private"));check(trace.pendingCount()==0);
        trace.request(8,"sql.query",null,"SELECT private_text");
        check(trace.finish(trace.completion(8,true,100,30),1).contains("label=sql-"));
        trace.request(9,"sql.query","private text","SELECT secret");trace.cancel(9);
        check(trace.completion(9,true,0,0).method.equals("other"));
        check(release.completion(1,true,1,1)==null);
        System.out.println("ServiceResultTraceTest PASS");
    }
}
