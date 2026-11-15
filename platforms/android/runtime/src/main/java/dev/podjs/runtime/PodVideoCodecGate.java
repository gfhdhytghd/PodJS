package dev.podjs.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.IntSupplier;

/** Process-wide physical video decoder admission, including software and retrievers.
 * A permit remains held until the underlying codec has actually released.
 * Call reclaim on the main looper; codec threads never invoke player callbacks. */
final class PodVideoCodecGate {
    static final int OTHER=0, PREWARM=1, CURRENT=2, MAXIMUM=3;
    static final PodVideoCodecGate PROCESS=new PodVideoCodecGate();
    private final LinkedHashMap<Object,Owner> owners=new LinkedHashMap<>();
    private int live,peak;
    private long sequence;
    private static final class Owner {
        final IntSupplier priority; final Runnable release;
        Owner(IntSupplier priority,Runnable release){this.priority=priority;this.release=release;}
    }
    synchronized void register(Object key,IntSupplier priority,Runnable release){owners.put(key,new Owner(priority,release));}
    synchronized void unregister(Object key){owners.remove(key);}
    void reclaim(Object keep,int priority) {
        ArrayList<Owner> victims=new ArrayList<>();
        synchronized(this){for(java.util.Map.Entry<Object,Owner> entry:owners.entrySet())
            if(entry.getKey()!=keep&&entry.getValue().priority.getAsInt()<CURRENT&&entry.getValue().priority.getAsInt()<=priority)victims.add(entry.getValue());}
        victims.sort(java.util.Comparator.comparingInt(o->o.priority.getAsInt()));
        for(Owner owner:victims)owner.release.run();
    }
    synchronized Permit acquire(String owner,int priority) {
        // Speculation never turns a steady two-codec state into three codecs.
        if(live>=(priority==OTHER?2:MAXIMUM))return null;
        long id=++sequence;live++;peak=Math.max(peak,live);trace("create",id,owner,priority);
        return new Permit(id,owner,priority);
    }
    synchronized Permit await(String owner,int priority)throws java.io.IOException {
        long deadline=android.os.SystemClock.elapsedRealtime()+2000;
        Permit permit;
        while((permit=acquire(owner,priority))==null){
            if(priority!=CURRENT)throw new java.io.IOException("video_decoder_limit");
            long remaining=deadline-android.os.SystemClock.elapsedRealtime();
            if(remaining<=0)throw new java.io.IOException("video_decoder_limit");
            try{wait(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("video_decoder_cancelled",e);}
        }
        return permit;
    }
    synchronized int live(){return live;}
    synchronized int peak(){return peak;}
    private void trace(String event,long id,String owner,int priority){android.util.Log.i("PodVideoCodec","event="+event+" id="+id+" owner="+owner+" priority="+priority+" live="+live+" peak="+peak);}
    final class Permit implements AutoCloseable {
        private final long id;private final String owner;private final int priority;private boolean closed;
        Permit(long id,String owner,int priority){this.id=id;this.owner=owner;this.priority=priority;}
        public void close(){synchronized(PodVideoCodecGate.this){if(closed)return;closed=true;live--;trace("release",id,owner,priority);PodVideoCodecGate.this.notifyAll();}}
    }
}
