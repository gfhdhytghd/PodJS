package dev.podjs.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.IntSupplier;

/** Process-wide physical video decoder admission, including software and retrievers.
 * A permit remains held until the underlying codec has actually released.
 * Call reclaim on the main looper; codec threads never invoke player callbacks. */
final class PodVideoCodecGate {
    static final int OTHER=0, PREWARM=1, CURRENT=2, MAXIMUM=3;
    /** A transient reservation refusal, never a decoder/device failure. */
    static final class AdmissionDenied extends java.io.IOException {
        final int priority;
        AdmissionDenied(int priority){super("video_decoder_limit");this.priority=priority;}
    }
    /** Attached to the original exception without changing Media3's exception type. */
    static final class FactoryFailure extends Exception { FactoryFailure(){super("video_codec_factory_attempted");} }
    static boolean isAdmissionDenied(Throwable error){return contains(error,AdmissionDenied.class);}
    static boolean isFactoryFailure(Throwable error){return contains(error,FactoryFailure.class);}
    private static boolean contains(Throwable error,Class<?> marker){
        java.util.ArrayDeque<Throwable> pending=new java.util.ArrayDeque<>();
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if(error!=null)pending.add(error);
        while(!pending.isEmpty()&&seen.size()<64){Throwable next=pending.removeFirst();if(!seen.add(next))continue;
            if(marker.isInstance(next))return true;if(next.getCause()!=null)pending.add(next.getCause());
            for(Throwable suppressed:next.getSuppressed())pending.add(suppressed);
            if(next instanceof androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException){
                Throwable fallback=((androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException)next).fallbackDecoderInitializationException;
                if(fallback!=null)pending.add(fallback);
            }
        }return false;
    }
    static final PodVideoCodecGate PROCESS=new PodVideoCodecGate();
    private final LinkedHashMap<Object,Owner> owners=new LinkedHashMap<>();
    /** One reservation identity follows a codec through actual release.
     * Generation zero means an opaque, non-session operation. */
    static final class Allocation {
        final long id,generation; final Object owner; final String purpose; final int priority;
        Allocation(long id,Object owner,long generation,String purpose,int priority){this.id=id;this.owner=owner;this.generation=generation;this.purpose=purpose;this.priority=priority;}
    }
    interface LifecycleListener { void reserved(Allocation allocation); void released(Allocation allocation); }
    private final LinkedHashMap<Long,Allocation> allocations=new LinkedHashMap<>();
    private final java.util.List<LifecycleListener> listeners=new ArrayList<>();
    /** Replay and events are serialized with admission. Listeners must only do
     * bounded bookkeeping; never call player APIs or throw to deny admission. */
    synchronized AutoCloseable observe(LifecycleListener listener){
        LifecycleListener subscription=new LifecycleListener(){
            public void reserved(Allocation a){listener.reserved(a);}
            public void released(Allocation a){listener.released(a);}
        };
        listeners.add(subscription);
        for(Allocation a:new ArrayList<>(allocations.values()))notifyListener(subscription,a,true);
        return ()->{synchronized(PodVideoCodecGate.this){listeners.remove(subscription);}};
    }
    private void notifyListener(LifecycleListener listener,Allocation allocation,boolean reserved){
        try{if(reserved)listener.reserved(allocation);else listener.released(allocation);}
        catch(RuntimeException error){android.util.Log.w("PodVideoCodec","lifecycle_observer_failed");}
    }
    /** Budget consumers request this lane, never close a player themselves. */
    void requestReclaim(Object keep,int priority){
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->reclaim(keep,priority));
    }
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
    synchronized Permit acquire(String purpose,int priority){return acquire(purpose,0,purpose,priority);}
    synchronized Permit acquire(Object owner,long generation,String purpose,int priority) {
        // Speculation never turns a steady two-codec state into three codecs.
        if(live>=(priority==OTHER?2:MAXIMUM))return null;
        long id=++sequence;live++;peak=Math.max(peak,live);trace("create",id,purpose,priority);
        Allocation allocation=new Allocation(id,owner,generation,purpose,priority);allocations.put(id,allocation);
        for(LifecycleListener listener:new ArrayList<>(listeners))notifyListener(listener,allocation,true);
        return new Permit(allocation);
    }
    synchronized Permit await(String purpose,int priority)throws java.io.IOException {return await(purpose,0,purpose,priority);}
    synchronized Permit await(Object owner,long generation,String purpose,int priority)throws java.io.IOException {
        long deadline=android.os.SystemClock.elapsedRealtime()+2000;
        Permit permit;boolean reclaimRequested=false;
        while((permit=acquire(owner,generation,purpose,priority))==null){
            if(priority!=CURRENT)throw new AdmissionDenied(priority);
            if(!reclaimRequested){reclaimRequested=true;requestReclaim(owner,PREWARM);}
            long remaining=deadline-android.os.SystemClock.elapsedRealtime();
            if(remaining<=0)throw new AdmissionDenied(priority);
            try{wait(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("video_decoder_cancelled",e);}
        }
        return permit;
    }
    synchronized int live(){return live;}
    synchronized int peak(){return peak;}
    private void trace(String event,long id,String owner,int priority){android.util.Log.i("PodVideoCodec","event="+event+" id="+id+" owner="+owner+" priority="+priority+" live="+live+" peak="+peak);}
    final class Permit implements AutoCloseable {
        private final Allocation allocation;private boolean closed;
        Permit(Allocation allocation){this.allocation=allocation;}
        public void close(){synchronized(PodVideoCodecGate.this){if(closed)return;closed=true;live--;allocations.remove(allocation.id);trace("release",allocation.id,allocation.purpose,allocation.priority);
            for(LifecycleListener listener:new ArrayList<>(listeners))notifyListener(listener,allocation,false);
            PodVideoCodecGate.this.notifyAll();}}
    }
}
