package dev.podjs.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Process counters with code-owned labels only. Never log aliases or arguments. */
final class SecureOperationStats {
    static final String[] METHODS={"secure.get","secure.set","secure.update","secure.delete","secure.compareAndSet","secure.acquire","secure.release","crypto.ecdhPublicKey","crypto.ecdhUnwrap","crypto.ecdhPossessionProof","crypto.encrypt","crypto.decrypt","crypto.hmacSha256","crypto.hkdfSha256","pairing.load","pairing.import","pairing.revoke"};
    static final String[] OPS={"load","containsAlias","getEntry","getKey","getCertificate","generate","deleteEntry","cipherInit","cipherAad","cipherFinal","agreement"};
    static final SecureOperationStats PROCESS=new SecureOperationStats();
    private final Map<String,long[]> rows=new LinkedHashMap<>();
    private final ThreadLocal<Span> current=new ThreadLocal<>();
    private long last=System.nanoTime();
    private static boolean started;
    static synchronized void start(){
        if(started)return;started=true;
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"PodSecureStats");t.setDaemon(true);return t;})
            .scheduleAtFixedRate(()->PROCESS.emit(System.nanoTime(),true,line->android.util.Log.i("PodJSSecure",line)),30,30,java.util.concurrent.TimeUnit.SECONDS);
    }
    static final class Span {
        final String method; final int site; final long began=System.nanoTime(); final long[] ops=new long[OPS.length];
        Span(String method,int site){this.method=method;this.site=site;}
    }
    Span begin(String method,int site){
        for(String allowed:METHODS)if(allowed.equals(method)){Span span=new Span(allowed,site>=0&&site<=31?site:0);current.set(span);return span;}
        return null;
    }
    void op(String operation){Span span=current.get();if(span==null)return;for(int i=0;i<OPS.length;i++)if(OPS[i].equals(operation)){span.ops[i]++;return;}}
    synchronized void end(Span span,boolean success){
        if(span==null)return;current.remove();
        long[] r=rows.computeIfAbsent(span.method+" site="+span.site,k->new long[3+OPS.length]);
        r[0]++;r[1]+=Math.max(0,System.nanoTime()-span.began);if(!success)r[2]++;
        for(int i=0;i<OPS.length;i++)r[3+i]+=span.ops[i];
    }
    synchronized void emit(long now,boolean force,Consumer<String> sink){
        if(!force&&now-last<30_000_000_000L)return;
        long interval=Math.max(1,(now-last)/1_000_000);last=now;
        if(rows.isEmpty())sink.accept("secureMethod=idle site=0 count=0 keystoreOps=0 ms=0 failures=0 intervalMs="+interval);
        for(Map.Entry<String,long[]> entry:rows.entrySet()){
            long[] r=entry.getValue();long ops=0;StringBuilder detail=new StringBuilder();
            for(int i=0;i<OPS.length;i++){ops+=r[3+i];detail.append(' ').append(OPS[i]).append('=').append(r[3+i]);}
            sink.accept("secureMethod="+entry.getKey()+" count="+r[0]+" keystoreOps="+ops+" ms="+r[1]/1_000_000+" failures="+r[2]+" intervalMs="+interval+detail);
        }
        rows.clear();
    }
}
