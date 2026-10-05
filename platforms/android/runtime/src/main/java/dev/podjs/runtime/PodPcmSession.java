package dev.podjs.runtime;

/** Bounded pull/write session; no unbounded producer or PCM queue. Android IO is
 * injected so denial, cancellation during open/read and teardown run on the JVM. */
final class PodPcmSession implements AutoCloseable {
    static final int SAMPLE_RATE=22050, CHUNK_SAMPLES=4096;
    interface Device extends AutoCloseable {
        int read(short[] chunk) throws Exception;
        int write(short[] chunk) throws Exception;
        long played();
        void close();
    }
    interface Factory { Device open(boolean record) throws Exception; }
    private static final class Lease {
        final long id; final boolean record; final Device device; boolean busy; long samples;
        Lease(long id,boolean record,Device device){this.id=id;this.record=record;this.device=device;}
    }
    private final Factory factory;
    private long generation, nextId;
    private boolean opening,closed;
    private Lease lease;
    PodPcmSession(Factory factory){this.factory=factory;}
    long open(boolean record,boolean debug,boolean foreground,boolean permission) throws Exception {
        final long epoch;
        synchronized(this){
            if(!debug||!foreground||record&&!permission)throw new SecurityException("Acoustic permission/debug/foreground required");
            if(closed)throw new IllegalStateException("Acoustic host closed");
            if(opening||lease!=null)throw new IllegalStateException("PCM session busy");
            opening=true;epoch=generation;
        }
        Device device=null;
        try {
            device=factory.open(record);
            synchronized(this){
                if(closed||epoch!=generation)throw new IllegalStateException("PCM start cancelled");
                lease=new Lease(++nextId,record,device);device=null;return lease.id;
            }
        } finally {if(device!=null)device.close();synchronized(this){opening=false;}}
    }
    private synchronized Lease acquire(long id,boolean record){
        if(lease==null||lease.id!=id||lease.record!=record)throw new IllegalStateException("PCM session expired");
        if(lease.busy)throw new IllegalStateException("PCM request already pending");
        lease.busy=true;return lease;
    }
    short[] read(long id) throws Exception {
        Lease owner=acquire(id,true);
        try {short[] chunk=new short[CHUNK_SAMPLES];int count=owner.device.read(chunk);
            if(count<=0||count>chunk.length)throw new IllegalStateException("Microphone read failed");
            synchronized(this){if(lease!=owner)throw new IllegalStateException("PCM cancelled");owner.samples+=count;if(owner.samples>SAMPLE_RATE*120L)throw new IllegalStateException("Acoustic listen timeout");}
            return java.util.Arrays.copyOf(chunk,count);
        }catch(Exception e){stop(id);throw e;}finally{synchronized(this){owner.busy=false;}}
    }
    int write(long id,short[] chunk) throws Exception {
        if(chunk.length==0||chunk.length>CHUNK_SAMPLES)throw new IllegalArgumentException("PCM chunk limit");
        Lease owner=acquire(id,false);
        try {
            if(owner.samples+chunk.length>SAMPLE_RATE*600L)throw new IllegalArgumentException("PCM duration limit");
            int count=owner.device.write(chunk);if(count<=0||count>chunk.length)throw new IllegalStateException("PCM write failed");
            synchronized(this){if(lease!=owner)throw new IllegalStateException("PCM cancelled");owner.samples+=count;}return count;
        }catch(Exception e){stop(id);throw e;}finally{synchronized(this){owner.busy=false;}}
    }
    synchronized boolean drained(long id){if(lease==null||lease.id!=id||lease.record)throw new IllegalStateException("PCM session expired");return lease.device.played()>=lease.samples;}
    void stop(long id){Lease old;synchronized(this){if(lease==null||lease.id!=id)return;old=lease;lease=null;}old.device.close();}
    void cancel(){Lease old;synchronized(this){generation++;old=lease;lease=null;}if(old!=null)old.device.close();}
    public void close(){synchronized(this){closed=true;}cancel();}
}
