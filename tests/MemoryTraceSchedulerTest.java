package dev.podjs.runtime;

public class MemoryTraceSchedulerTest {
    public static void main(String[] args) {
        int[] posted = {0}, samples = {0};
        Runnable[] pending = {null};
        MemoryTraceScheduler.Queue queue = (callback, delay) -> {
            if (delay != 1000) throw new AssertionError("trace cadence");
            posted[0]++; pending[0] = callback;
        };
        Runnable sample = () -> samples[0]++;
        // Production default constructor: no dependency or enabled injection.
        new MemoryTraceScheduler().schedule(queue, sample);
        if (posted[0] != 0 || samples[0] != 0) throw new AssertionError("default trace scheduled");
        new MemoryTraceScheduler(true).schedule(queue, sample);
        if (posted[0] != 1 || samples[0] != 0) throw new AssertionError("explicit trace must enqueue, not run synchronously");
        pending[0].run();
        if (samples[0] != 1) throw new AssertionError("sample not delivered");
        System.out.println("MemoryTraceScheduler default/opt-in PASS");
    }
}
