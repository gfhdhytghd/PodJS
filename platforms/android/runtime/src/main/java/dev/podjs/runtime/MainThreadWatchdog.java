package dev.podjs.runtime;

/** Fixed-schema, bounded diagnostics. The observer must run off the main
 * thread; a blocked main turn need not return before its stall is counted. */
final class MainThreadWatchdog {
    enum Phase { IDLE, SERVICE, JOBS, FRAME, LIFECYCLE, INPUT }
    static final long THRESHOLD_NS=1_000_000_000L;
    static final int MAX_LOGS=16;
    private boolean active,reported;
    private long generation,pending,sentNs,stalls,logs,maxDelayNs;
    private Phase phase=Phase.IDLE;
    synchronized void active(boolean value){
        if(active==value)return;
        active=value;pending=0;reported=false;generation++;
    }
    synchronized void phase(Phase value){phase=value;}
    synchronized long probe(long now){
        if(!active||pending!=0)return 0;
        pending=++generation;sentNs=now;reported=false;return pending;
    }
    synchronized String observe(long now){
        if(!active||pending==0)return null;
        long delay=Math.max(0,now-sentNs);maxDelayNs=Math.max(maxDelayNs,delay);
        if(delay<=THRESHOLD_NS||reported)return null;
        reported=true;stalls++;
        if(logs>=MAX_LOGS)return null;
        logs++;
        return "lane=main phase="+phase.name().toLowerCase(java.util.Locale.ROOT)
            +" delayMs="+(delay/1_000_000)+" stalls="+stalls+" logs="+logs;
    }
    synchronized String acknowledge(long token,long now){
        if(token==0||pending!=token)return null;
        String record=observe(now);pending=0;reported=false;return record;
    }
    synchronized long stalls(){return stalls;}
    synchronized int phaseCode(){return phase.ordinal()+1;}
    synchronized long delayMs(long now){return pending==0?0:Math.max(0,now-sentNs)/1_000_000L;}
    synchronized String summary(){return "lane=main summary=true stalls="+stalls+" maxDelayMs="+(maxDelayNs/1_000_000)+" logs="+logs;}
}
