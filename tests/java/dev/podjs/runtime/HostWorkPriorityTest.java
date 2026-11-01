package dev.podjs.runtime;

import java.util.*;
import java.util.concurrent.*;

/** Deterministic restore-load model: actual admission class, virtual 4 ms units. */
public final class HostWorkPriorityTest {
    public static void main(String[] args) throws Exception {
        HostWorkPriority lane=new HostWorkPriority();
        HostWorkPriority.Ticket active=lane.reserve(true);active.enter();
        List<HostWorkPriority.Ticket> restore=new ArrayList<>();
        for(int i=0;i<800;i++)restore.add(lane.reserve(true));
        HostWorkPriority.Ticket ui=lane.reserve(false);
        active.close();ui.enter();ui.close();
        // The UI is admitted before every queued restore unit.
        for(HostWorkPriority.Ticket t:restore){t.enter();t.close();}
        System.out.println("restoreUnits=800 unitMs=4 FIFO_foregroundWaitMs=3204 priority_foregroundWaitMs=4 (virtual, nonpreemptible unit)");
        // FIFO within each lane and bounded background progress under foreground pressure.
        HostWorkPriority.Ticket bg=lane.reserve(true);
        List<HostWorkPriority.Ticket> fg=new ArrayList<>();for(int i=0;i<9;i++)fg.add(lane.reserve(false));
        for(int i=0;i<8;i++){fg.get(i).enter();fg.get(i).close();}
        bg.enter();bg.close();fg.get(8).enter();fg.get(8).close();
        // Cancelling a queued request releases its reservation, including before submit starts.
        HostWorkPriority.Ticket cancelled=lane.reserve(false);cancelled.cancel();
        try{cancelled.enter();throw new AssertionError("cancelled work entered");}catch(InterruptedException expected){}
        HostWorkPriority.Ticket running=lane.reserve(true);running.enter();running.cancel();
        HostWorkPriority.Ticket next=lane.reserve(false);
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch waiting=new CountDownLatch(1),entered=new CountDownLatch(1);
        Future<?> f=pool.submit(()->{waiting.countDown();try{next.enter();entered.countDown();}catch(InterruptedException e){throw new RuntimeException(e);}finally{next.close();}});
        waiting.await();if(entered.await(20,TimeUnit.MILLISECONDS))throw new AssertionError("cancel preempted active native unit");
        running.close();f.get(2,TimeUnit.SECONDS);pool.shutdownNow();
        // Thread interruption and request cancellation leave later work runnable.
        HostWorkPriority.Ticket after=lane.reserve(true);after.enter();after.close();
        System.out.println("HostWorkPriority: foreground priority, FIFO, background progress, cancellation PASS");
        benchmark();
    }
    private static void benchmark() throws Exception {
        final int count=800;
        ExecutorService fifo=Executors.newSingleThreadExecutor();CountDownLatch unblock=new CountDownLatch(1);
        fifo.submit(()->{try{unblock.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        java.util.concurrent.atomic.AtomicInteger beforeUnits=new java.util.concurrent.atomic.AtomicInteger();
        for(int i=0;i<count;i++)fifo.submit(()->{try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}beforeUnits.incrementAndGet();});
        long beforeStart=System.nanoTime();Future<Integer> before=fifo.submit(beforeUnits::get);unblock.countDown();
        if(before.get(5,TimeUnit.SECONDS)!=count)throw new AssertionError("FIFO workload missing units");
        double beforeMs=(System.nanoTime()-beforeStart)/1e6;fifo.shutdownNow();
        HostWorkPriority lane=new HostWorkPriority();HostWorkPriority.Ticket held=lane.reserve(true);held.enter();
        ExecutorService restore=Executors.newSingleThreadExecutor(),foreground=Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicInteger afterUnits=new java.util.concurrent.atomic.AtomicInteger();
        for(int i=0;i<count;i++){HostWorkPriority.Ticket t=lane.reserve(true);restore.submit(()->{try{t.enter();Thread.sleep(1);afterUnits.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{t.close();}});}
        HostWorkPriority.Ticket ui=lane.reserve(false);long afterStart=System.nanoTime();
        Future<Integer> after=foreground.submit(()->{try{ui.enter();return afterUnits.get();}finally{ui.close();}});held.close();
        if(after.get(5,TimeUnit.SECONDS)!=0)throw new AssertionError("UI waited behind restore backlog");
        double afterMs=(System.nanoTime()-afterStart)/1e6;
        restore.shutdown();if(!restore.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("restore starved");foreground.shutdownNow();
        System.out.printf(java.util.Locale.ROOT,"SIMULATED restore backlog=%d native-unit-delay=1ms before-FIFO-wait=%.3fms after-priority-wait=%.3fms (JVM model; not device)\n",count,beforeMs,afterMs);
    }

}
