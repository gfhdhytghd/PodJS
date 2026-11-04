package dev.podjs.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Numeric completed host spans. Wall time is not CPU time. No supplied text. */
final class ServiceWorkStats {
    private final Map<String,long[]> rows=new LinkedHashMap<>();
    private long emitted;
    private final long[][] sqlSites=new long[64][4];
    synchronized void sqlSite(int site,long workNs,long cpuNs,long units){long[] r=sqlSites[site>=0&&site<64?site:0];r[0]++;r[1]+=Math.max(0,units);r[2]+=Math.max(0,workNs);r[3]+=Math.max(0,cpuNs);}
    static String phase(String method){
        switch(method){
            case "secure.get":return "secure-read";
            case "secure.set":case "secure.update":case "secure.delete":case "secure.compareAndSet":return "secure-write";
            case "sql.query":case "sql.execute":case "sql.transaction":return "sql";
            case "crypto.aesGcmDecrypt":case "crypto.largeAesGcmDecrypt":return "decrypt";
            case "crypto.aesGcmEncrypt":case "crypto.largeAesGcmEncrypt":return "encrypt";
            case "crypto.verifyP256":case "crypto.hash":case "file.hash":return "verify";
            case "crypto.hkdfSha256":return "hkdf";
            case "crypto.gunzip":case "file.gunzip":case "crypto.gunzipSealedParts":return "gunzip";
            case "crypto.gzip":case "file.gzip":return "gzip";
            default:return null;
        }
    }
    synchronized void add(String method,long queueNs,long workNs,long cpuNs,long inputChars,long units,boolean success){
        String phase=phase(method);if(phase==null)return;
        long[] r=rows.computeIfAbsent(phase,k->new long[9]);r[0]++;r[1]+=Math.max(0,queueNs);r[2]+=Math.max(0,workNs);r[3]+=Math.max(0,cpuNs);r[4]+=Math.max(0,inputChars);r[5]+=Math.max(0,units);r[6]=Math.max(r[6],Math.max(0,queueNs));r[7]=Math.max(r[7],Math.max(0,workNs));if(!success)r[8]++;
    }
    synchronized void emit(long now,boolean force,boolean background,Consumer<String> sink){
        if(!force&&now-emitted<30_000_000_000L)return;long intervalMs=emitted==0?0:Math.max(0,(now-emitted)/1_000_000);emitted=now;
        for(Map.Entry<String,long[]> e:rows.entrySet()){long[] r=e.getValue();sink.accept("hostStage="+e.getKey()+" lane="+(background?"background":"foreground")+" count="+r[0]+" queueNs="+r[1]+" workNs="+r[2]+" cpuNs="+r[3]+" inputChars="+r[4]+" units="+r[5]+" maxQueueNs="+r[6]+" maxWorkNs="+r[7]+" failures="+r[8]);}
        for(int site=0;site<sqlSites.length;site++){long[] r=sqlSites[site];if(r[0]>0){sink.accept("metric=sql site="+site+" lane="+(background?"background":"foreground")+" windowMs="+intervalMs+" count="+r[0]+" units="+r[1]+" workNs="+r[2]+" cpuNs="+r[3]);java.util.Arrays.fill(r,0);}}
        rows.clear();
    }
}
