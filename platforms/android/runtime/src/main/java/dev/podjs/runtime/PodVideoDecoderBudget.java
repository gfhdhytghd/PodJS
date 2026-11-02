package dev.podjs.runtime;

/** Process-local decoder budget shared by legacy and explicit video sessions. */
final class PodVideoDecoderBudget {
    private final int maximum;
    private int limit;
    private int leases;
    private int peak;

    PodVideoDecoderBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("invalid_decoder_limit");
        maximum = limit;
        this.limit = limit;
    }

    synchronized boolean acquire() {
        if (leases >= limit) return false;
        leases += 1;
        peak = Math.max(peak, leases);
        trace();
        return true;
    }

    synchronized void release() {
        if (leases <= 0) throw new IllegalStateException("decoder_budget_underflow");
        leases -= 1;
        trace();
        if (leases == 0) limit = maximum;
    }

    private void trace() {
        if (android.util.Log.isLoggable("PodJSService", android.util.Log.DEBUG))
            android.util.Log.i("PodJSService", "decoder_budget in_use=" + leases + " peak=" + peak);
    }
    synchronized int peakInUse() { return peak; }
    synchronized int inUse() { return leases; }
    synchronized int limit() { return limit; }

    /** Capability probes may only make the shared process budget stricter. */
    synchronized void restrictTo(int value) {
        if (value < 1) throw new IllegalArgumentException("invalid_decoder_limit");
        limit = Math.min(limit, value);
    }
}
