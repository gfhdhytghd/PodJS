package dev.podjs.runtime;

/** Process-local decoder budget shared by legacy and explicit video sessions. */
final class PodVideoDecoderBudget {
    private final int maximum;
    private int limit;
    private int leases;

    PodVideoDecoderBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("invalid_decoder_limit");
        maximum = limit;
        this.limit = limit;
    }

    synchronized boolean acquire() {
        if (leases >= limit) return false;
        leases += 1;
        return true;
    }

    synchronized void release() {
        if (leases <= 0) throw new IllegalStateException("decoder_budget_underflow");
        leases -= 1;
        if (leases == 0) limit = maximum;
    }

    synchronized int inUse() { return leases; }
    synchronized int limit() { return limit; }

    /** Capability probes may only make the shared process budget stricter. */
    synchronized void restrictTo(int value) {
        if (value < 1) throw new IllegalArgumentException("invalid_decoder_limit");
        limit = Math.min(limit, value);
    }
}
