package dev.podjs.runtime;

/** Process-local decoder budget shared by legacy and explicit video sessions. */
final class PodVideoDecoderBudget {
    private final int limit;
    private int leases;

    PodVideoDecoderBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("invalid_decoder_limit");
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
    }

    synchronized int inUse() { return leases; }
    int limit() { return limit; }
}
