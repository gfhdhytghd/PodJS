package dev.podjs.runtime;

/** Opt-in periodic diagnostics. Debuggable alone does not enable heap walks. */
final class MemoryTraceScheduler {
    interface Queue { void postDelayed(Runnable callback, long milliseconds); }
    private final boolean enabled;
    MemoryTraceScheduler() { this(false); }
    MemoryTraceScheduler(boolean enabled) { this.enabled = enabled; }
    void schedule(Queue queue, Runnable sample) {
        if (enabled) queue.postDelayed(sample, 1000);
    }
}
