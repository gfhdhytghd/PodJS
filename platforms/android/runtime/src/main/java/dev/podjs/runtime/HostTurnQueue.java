package dev.podjs.runtime;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** A FIFO mailbox with a real host yield between batches, never front-of-queue.
 * Producers coalesce wakeups; a continuously busy mailbox cannot postpone an
 * already scheduled wakeup or extend the current batch indefinitely. */
final class HostTurnQueue {
    interface Poster { void post(Runnable job,long delayMs); void remove(Runnable job); }
    private final Poster poster;
    private final LongSupplier clock;
    private final ArrayDeque<Runnable> pending=new ArrayDeque<>();
    private final Runnable wake=this::drain;
    private boolean scheduled,running;
    private long generation;
    static final int MAX_TASKS=32;
    static final long BUDGET_NS=2_000_000L,YIELD_MS=1;
    HostTurnQueue(Poster poster,LongSupplier clock){this.poster=poster;this.clock=clock;}
    synchronized void post(Runnable job){
        pending.addLast(job);
        schedule();
    }
    private void schedule(){
        if(!scheduled&&!running&&!pending.isEmpty()){
            scheduled=true;poster.post(wake,YIELD_MS);
        }
    }
    synchronized void clear(){
        generation++;pending.clear();poster.remove(wake);scheduled=false;
    }
    private void drain(){
        final long epoch;
        synchronized(this){scheduled=false;running=true;epoch=generation;}
        long began=clock.getAsLong();int count=0;
        try{
            while(count<MAX_TASKS&&(count==0||clock.getAsLong()-began<BUDGET_NS)){
                Runnable next;
                synchronized(this){if(epoch!=generation)break;next=pending.pollFirst();}
                if(next==null)break;
                count++;next.run();
            }
        }finally{
            synchronized(this){running=false;schedule();}
        }
    }
    synchronized int pendingCount(){return pending.size();}
}
