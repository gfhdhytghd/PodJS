package dev.podjs.runtime;

import java.util.function.Consumer;

/** Worker-side phases, independent of main-looper completion/guest timings. */
final class DeviceServiceTrace {
    private final boolean enabled;
    private final Consumer<String> output;
    private int logs;
    DeviceServiceTrace(boolean enabled,Consumer<String> output){this.enabled=enabled;this.output=output;}
    long begin(){return enabled?System.nanoTime():0;}
    synchronized void end(String phase,long began,int requestId){
        if(!enabled||logs>=4096)return;
        // Code-owned literals only; no keys, values, paths or exceptions.
        switch(phase){case "secure.lock":case "secure.read":case "secure.key":case "secure.decrypt":case "secure.json":case "secure.total":break;default:return;}
        logs++;
        try{output.accept("host phase="+phase+" id="+requestId+" ns="+(System.nanoTime()-began));}catch(RuntimeException ignored){}
    }
}
