package dev.podjs.runtime;

/** Process-local decoder budget shared by legacy and explicit video sessions. */
final class PodVideoDecoderBudget {
    private final int maximum;
    private int limit;
    private int leases;
    private int peak;
    private boolean spare, spareCancelled;
    private int foregroundWaiters;
    private final java.util.List<Runnable> spareReleased=new java.util.ArrayList<>();

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

    /** Borrow the last free lease; foreground can cancel the short-lived owner. */
    synchronized boolean acquireSpare() {
        if(limit<2||spare||foregroundWaiters>0||!acquire())return false;
        spare=true;spareCancelled=false;return true;
    }
    synchronized boolean spareInUse(){return spare;}
    synchronized boolean spareCancelled(){return spareCancelled;}
    void preemptSpare(Runnable ready){
        boolean now;
        synchronized(this){foregroundWaiters++;spareCancelled=true;now=!spare;if(!now)spareReleased.add(ready);}
        if(now)ready.run();
    }
    synchronized void foregroundAdmitted(){if(foregroundWaiters<=0)throw new IllegalStateException("foreground_waiter_underflow");foregroundWaiters--;}
    void releaseSpare(){
        java.util.List<Runnable> ready;
        synchronized(this){if(!spare)throw new IllegalStateException("spare_lease_underflow");spare=false;release();ready=new java.util.ArrayList<>(spareReleased);spareReleased.clear();}
        for(Runnable callback:ready)callback.run();
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
