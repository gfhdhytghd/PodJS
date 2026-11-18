package dev.podjs.runtime;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Byte admission shares decap's physical identity and release acknowledgement.
 * Estimates cover opaque codec allocations; PSS remains the acceptance metric. */
final class PodMediaMemoryAccounting implements PodMediaMemoryBudget,
        PodVideoCodecGate.LifecycleListener, PodVideoCodecGate.ByteAdmission {
    interface Charge extends AutoCloseable { void commit(); void close(); }
    interface Bytes { Charge reserve(long bytes); default Charge reserve(Owner owner,long bytes){return reserve(bytes);} }
    private static volatile PodMediaMemoryAccounting process;
    private static final AtomicLong resourceIds=new AtomicLong();
    private final PodVideoCodecGate gate;
    private final Bytes bytes;
    private final HashMap<Long,Reservation> codecs=new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Token,Boolean> live=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Token implements Reservation,AutoCloseable {
        final PodMediaMemoryAccounting budget;final Owner owner;final long bytes;final Charge charge;
        boolean committed,closed;
        Token(PodMediaMemoryAccounting budget,Owner owner,long bytes,Charge charge){this.budget=budget;this.owner=owner;this.bytes=bytes;this.charge=charge;}
        public Owner owner(){return owner;}
        public long reservedBytes(){return bytes;}
        public void close(){budget.release(this);}
    }
    PodMediaMemoryAccounting(PodVideoCodecGate gate,Bytes bytes){this.gate=gate;this.bytes=bytes;}
    static synchronized void install(){
        if(process!=null)return;
        PodMediaMemoryAccounting next=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,new Bytes(){
            public Charge reserve(long n){return reserve(null,n);}
            public Charge reserve(Owner owner,long n){
                PodProcessMemoryBudget.Lease lease=PodProcessMemoryBudget.reserveMedia(n,owner!=null&&owner.priority!=Priority.CURRENT);
                return lease==null?null:new Charge(){public void commit(){lease.commit();}public void close(){lease.close();}};
            }
        });
        next.gate.bindMemory(next,next);process=next;
    }
    static Priority priority(int value){return value==PodVideoCodecGate.CURRENT?Priority.CURRENT:value==PodVideoCodecGate.PREWARM?Priority.PREWARM:Priority.OPTIONAL;}
    public Reservation tryReserve(Owner owner,long worstCaseBytes){
        if(owner==null||worstCaseBytes<=0)return null;
        // The native optional lane distinguishes domain quota from global PSS
        // pressure atomically, including simultaneous codec/capture requests.
        Charge charge=bytes.reserve(owner,worstCaseBytes);
        if(charge==null)return null;Token token=new Token(this,owner,worstCaseBytes,charge);live.put(token,Boolean.TRUE);return token;
    }
    private Token token(Reservation reservation){
        if(!(reservation instanceof Token)||((Token)reservation).budget!=this)throw new IllegalArgumentException("foreign_media_reservation");
        return (Token)reservation;
    }
    public void commit(Reservation reservation,long actualBytes){
        Token t=token(reservation);synchronized(t){
            if(actualBytes<0||actualBytes>t.bytes)throw new IllegalArgumentException("media_reservation_growth");
            if(!t.closed&&!t.committed){t.charge.commit();t.committed=true;}
        }
    }
    public void release(Reservation reservation){Token t=token(reservation);synchronized(t){if(!t.closed){t.closed=true;t.charge.close();live.remove(t);}}}
    public synchronized void onOwnerEvent(Owner owner,Event event){
        Reservation reservation=codecs.get(owner.id);
        if(reservation==null||reservation.owner()!=owner)return;
        if(event==Event.CREATED)commit(reservation,reservation.reservedBytes());
        if(event==Event.RELEASED){codecs.remove(owner.id);release(reservation);}
        // RESERVED and RELEASING retain bytes; only the physical ack returns them.
    }
    public synchronized boolean admit(PodVideoCodecGate.Allocation allocation){
        Kind kind=allocation.purpose.contains("cover")||allocation.purpose.equals("stock_frame")?Kind.RETRIEVER:Kind.VIDEO_CODEC;
        Owner owner=new Owner(allocation.id,allocation.generation,allocation.owner,priority(allocation.priority),kind);
        Reservation reservation=tryReserve(owner,allocation.estimatedBytes);
        if(reservation==null){gate.requestReclaim(allocation.owner,allocation.priority==PodVideoCodecGate.CURRENT?PodVideoCodecGate.PREWARM:PodVideoCodecGate.OTHER);return false;}
        codecs.put(allocation.id,reservation);return true;
    }
    public synchronized void reserved(PodVideoCodecGate.Allocation allocation){
        Reservation reservation=codecs.get(allocation.id);
        if(reservation==null)throw new IllegalStateException("codec_missing_memory_admission");
        // Keep the promise pending even if construction spans a PSS sample.
    }
    public synchronized void created(PodVideoCodecGate.Allocation allocation){
        Reservation reservation=codecs.get(allocation.id);
        if(reservation!=null)onOwnerEvent(reservation.owner(),Event.CREATED);
    }
    public synchronized void released(PodVideoCodecGate.Allocation allocation){
        Reservation reservation=codecs.get(allocation.id);if(reservation!=null)onOwnerEvent(reservation.owner(),Event.RELEASED);
    }
    static AutoCloseable reserveResource(Object identity,long generation,int priority,Kind kind,long size){
        PodMediaMemoryAccounting p=process;if(p==null)return ()->{};
        Owner owner=new Owner(-resourceIds.incrementAndGet(),generation,identity,priority(priority),kind);
        Reservation reservation=p.tryReserve(owner,size);
        if(reservation==null){if(kind!=Kind.KEYFRAME)p.gate.requestReclaim(identity,priority==PodVideoCodecGate.CURRENT?PodVideoCodecGate.PREWARM:PodVideoCodecGate.OTHER);return null;}
        // Allocation follows this call; retain the conservative envelope until
        // the caller confirms actual resource release, including failure paths.
        return (Token)reservation;
    }
    static void commitResource(AutoCloseable charge){if(charge instanceof Token){Token t=(Token)charge;t.budget.commit(t,t.bytes);}}
    static void close(AutoCloseable charge){if(charge!=null)try{charge.close();}catch(Exception error){throw new IllegalStateException("media_memory_release",error);}}
    static org.json.JSONObject stats()throws org.json.JSONException {
        org.json.JSONObject result=new org.json.JSONObject();PodMediaMemoryAccounting p=process;
        for(Kind kind:Kind.values()){long n=0;int count=0;if(p!=null)for(Token token:p.live.keySet())if(token.owner.kind==kind){n+=token.bytes;count++;}result.put(kind.name(),new org.json.JSONObject().put("estimatedBytes",n).put("allocations",count));}
        return result;
    }
    static void trim(int level){if(process!=null&&level>0)process.gate.requestReclaim(null,level>=2?PodVideoCodecGate.PREWARM:PodVideoCodecGate.OTHER);}
}
