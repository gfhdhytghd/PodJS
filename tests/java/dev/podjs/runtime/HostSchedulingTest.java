package dev.podjs.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class HostSchedulingTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class Loop implements HostTurnQueue.Poster {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();long now;int posted;long delay;
        public void post(Runnable task,long delayMs){queue.add(task);posted++;delay=delayMs;}
        public void remove(Runnable task){queue.removeIf(v->v==task);}
        void once(){queue.removeFirst().run();}
    }
    static void mailbox(){
        Loop l=new Loop();HostTurnQueue q=new HostTurnQueue(l,()->l.now);List<Integer> seen=new ArrayList<>();
        for(int i=0;i<100;i++){final int v=i;q.post(()->{seen.add(v);l.now+=600_000;});}
        check(l.queue.size()==1&&l.delay>0,"coalesced, yielding host wakeup");
        l.queue.add(()->seen.add(-1));l.once();
        check(seen.size()==4&&q.pendingCount()==96,"2ms budget returns before queue empties");
        l.once();check(seen.get(4)==-1,"input executes before the next continuation");
        while(!l.queue.isEmpty())l.once();
        seen.remove(Integer.valueOf(-1));for(int i=0;i<100;i++)check(seen.get(i)==i,"FIFO and exactly once");
        // Independent task cap, even when the supplied clock does not move.
        for(int i=0;i<100;i++)q.post(()->{});l.once();check(q.pendingCount()==68,"task limit");
        q.clear();check(q.pendingCount()==0&&l.queue.isEmpty(),"destroy removes queued work");
        q.post(()->{q.clear();q.post(()->seen.add(1000));});q.post(()->{throw new AssertionError("stale lifetime");});
        l.once();check(l.queue.size()==1,"replacement work receives a fresh host turn");l.once();
        check(seen.get(seen.size()-1)==1000,"replacement owner makes progress");
        q.post(()->{throw new IllegalStateException("visible failure");});q.post(()->seen.add(1001));
        try{l.once();throw new AssertionError("swallowed failure");}catch(IllegalStateException expected){}
        l.once();check(seen.get(seen.size()-1)==1001,"failure cannot strand later tasks");
    }
    static void watchdog(){
        MainThreadWatchdog w=new MainThreadWatchdog();long second=1_000_000_000L;
        check(w.probe(0)==0&&w.observe(99*second)==null,"inactive has no deadline");w.active(true);
        long token=w.probe(0);w.phase(MainThreadWatchdog.Phase.SERVICE);
        check(token!=0&&w.probe(second)==0,"one pending ping; busy observer cannot reset deadline");
        String record=w.observe(second+1);check(record!=null&&record.contains("phase=service")&&w.stalls()==1,"stall is visible before main returns");
        check(w.observe(9*second)==null&&w.stalls()==1,"one incident per stuck ping");
        w.acknowledge(token,10*second);long next=w.probe(11*second);
        check(w.acknowledge(token,12*second)==null,"stale ack cannot complete new probe");
        w.active(false);check(w.acknowledge(next,13*second)==null,"background cancels outstanding probe");
        w.active(true);
        for(int i=0;i<32;i++){long at=(20L+i*3)*second;long t=w.probe(at);w.observe(at+second+1);w.acknowledge(t,at+second+2);}
        check(w.stalls()==33&&w.summary().contains("logs=16"),"release counts survive log cap");
        check(!w.summary().contains("SQL")&&!w.summary().contains("args"),"fixed numeric summary");
    }
    static void effectLanes(){
        Loop l=new Loop();ArrayDeque<String> fx=new ArrayDeque<>(),net=new ArrayDeque<>();List<String> seen=new ArrayList<>();
        fx.add("0");net.add("network");
        HostEffectPump pump=new HostEffectPump(fx::pollFirst,net::pollFirst,(v,foreground)->{
            seen.add(v);l.now+=3_000_000;int n=Integer.parseInt(v);if(n<129)fx.add(""+(n+1));
        },v->{seen.add(v);l.now+=3_000_000;},()->l.now);
        check(pump.drain(true,false)&&seen.equals(List.of("0")),"first atomic dispatch exhausts time budget");
        pump.drain(true,false);check(seen.equals(List.of("0","network")),"self-replenishing effects cannot starve NET");
        pump.drain(false,true);check(seen.size()==131,"checkpoint drains all 130 prior writes despite normal cap");
        seen.remove("network");for(int i=0;i<130;i++)check(seen.get(i).equals(""+i),"per-lane FIFO, no lost/prefetched work");
        check(!pump.drain(false,false),"empty idle queues stop scheduling");
    }
    public static void main(String[] args){mailbox();watchdog();effectLanes();System.out.println("host scheduling/watchdog: FIFO, input, budgets, lifetime, two-lane starvation, checkpoint, recovery, cap PASS");}
}
