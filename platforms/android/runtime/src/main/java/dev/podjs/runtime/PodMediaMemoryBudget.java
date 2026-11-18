package dev.podjs.runtime;

/** Typed byte reservations. The codec gate supplies physical lifetime events;
 * this interface neither creates a decoder pool nor releases a player. */
public interface PodMediaMemoryBudget {
    enum Priority { OPTIONAL, PREWARM, CURRENT }
    enum Kind { VIDEO_CODEC, AUDIO_CODEC, RETRIEVER, COMPRESSED_BUFFER, SURFACE, KEYFRAME, FRAME_CAPTURE }
    enum Event { RESERVED, CREATED, RELEASING, RELEASED }
    final class Owner {
        public final long id,generation;
        public final Object identity;
        public final Priority priority;
        public final Kind kind;
        public Owner(long id,long generation,Priority priority,Kind kind) {
            this(id,generation,null,priority,kind);
        }
        public Owner(long id,long generation,Object identity,Priority priority,Kind kind) {
            this.id=id;this.generation=generation;this.identity=identity;this.priority=java.util.Objects.requireNonNull(priority);this.kind=java.util.Objects.requireNonNull(kind);
        }
    }
    interface Reservation {
        Owner owner();
        long reservedBytes();
    }
    Reservation tryReserve(Owner owner,long worstCaseBytes);
    /** A growth beyond the original promise requires a new successful reservation. */
    void commit(Reservation reservation,long actualBytes);
    /** Uncommitted promises close on failed allocation. Committed resources close
     * after actual codec release / GPU fence, never on cancellation request. */
    void release(Reservation reservation);
    void onOwnerEvent(Owner owner,Event event);
}
