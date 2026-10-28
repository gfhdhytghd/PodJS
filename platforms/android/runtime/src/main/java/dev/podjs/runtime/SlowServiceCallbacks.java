package dev.podjs.runtime;

import java.util.LinkedHashMap;

/** Bounded service-result entry timings. This wall span includes synchronous
 * guest dispatch and dependent jobs, not network/worker waiting. Only literal
 * service and callback labels; no arguments, results, SQL or error text. */
final class SlowServiceCallbacks {
    static final long SLOW_NS=50_000_000L;
    static final int MAX_PENDING=64,MAX_LOGS=64;
    final boolean enabled;
    private final LinkedHashMap<Integer,String> pending=new LinkedHashMap<>();
    private int logs;
    SlowServiceCallbacks(boolean enabled){this.enabled=enabled;}
    static boolean enabled(boolean debug,Boolean manifestOverride,boolean releaseOptIn){
        return manifestOverride!=null?manifestOverride:debug||releaseOptIn;
    }
    synchronized void request(int id,String method){
        if(!enabled)return;
        if(!pending.containsKey(id)&&pending.size()>=MAX_PENDING)pending.remove(pending.keySet().iterator().next());
        pending.put(id,ServiceResultTrace.exactMethod(method));
    }
    synchronized void cancel(int id){pending.remove(id);}
    synchronized String complete(int id,boolean terminal){
        if(!enabled)return null;
        String method=terminal?pending.remove(id):pending.get(id);
        return method==null?"other":method;
    }
    synchronized String finish(String method,boolean status,long ns){
        if(!enabled||method==null||ns<=SLOW_NS||logs>=MAX_LOGS)return null;
        logs++;
        return "serviceCallback service="+ServiceResultTrace.exactMethod(method)
            +" callback="+(status?"postHostCompletion.authStatus":"postHostCompletion.serviceResult")
            +" elapsedNs="+ns+" logs="+logs;
    }
    synchronized void clear(){pending.clear();logs=0;}
}
