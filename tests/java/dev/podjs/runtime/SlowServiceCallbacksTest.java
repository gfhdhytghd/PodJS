package dev.podjs.runtime;

public final class SlowServiceCallbacksTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(SlowServiceCallbacks.enabled(true,null,false));
        check(!SlowServiceCallbacks.enabled(false,null,false));
        check(SlowServiceCallbacks.enabled(false,null,true));
        check(!SlowServiceCallbacks.enabled(true,false,true));
        check(SlowServiceCallbacks.enabled(false,true,false));
        SlowServiceCallbacks off=new SlowServiceCallbacks(false);
        off.request(1,"file.read");check(off.complete(1,true)==null);check(off.finish("file.read",false,100_000_000)==null);
        SlowServiceCallbacks trace=new SlowServiceCallbacks(true);
        trace.request(1,"file.read");check(trace.complete(1,false).equals("file.read"));
        check(trace.finish("file.read",false,50_000_000)==null);
        String slow=trace.finish(trace.complete(1,true),false,50_000_001);
        check(slow.contains("service=file.read callback=postHostCompletion.serviceResult"));
        check(trace.complete(1,true).equals("other"));
        trace.request(2,"sql.PRIVATE_BODY");check(trace.complete(2,true).equals("sql"));
        check(!trace.finish("PRIVATE_ACCOUNT https://secret",true,99_000_000).contains("PRIVATE"));
        trace.request(3,"browser.authorize");trace.cancel(3);check(trace.complete(3,true).equals("other"));
        for(int i=0;i<1000;i++)trace.request(i,"file.read");
        check(trace.complete(0,true).equals("other"));check(trace.complete(999,true).equals("file.read"));
        int records=2;for(int i=0;i<100;i++)if(trace.finish("file.read",false,99_000_000)!=null)records++;
        check(records==64);trace.clear();check(trace.finish("file.read",false,99_000_000)!=null);
        System.out.println("SlowServiceCallbacks PASS: enable/disable, strict 50ms, labels, bounds, reset");
    }
}
