package dev.podjs.runtime;

import java.nio.file.*;
import java.util.*;

/** Executes the production queue against a durable scheduler model. No Android
 * device, wall-clock sleep or production data. Dependencies model WorkManager's
 * KEEP/REPLACE/APPEND_OR_REPLACE contract, not its OS dispatch latency. */
public final class WindowedWorkQueueTest {
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    static final class Node {WindowedWorkQueue.Job job;WindowedWorkQueue.State state;String dependency;boolean cancelled;long scheduled;}
    static final class Disk implements WindowedWorkQueue.Ports {
        final Path dir;final LinkedHashMap<String,Node> jobs=new LinkedHashMap<>();long time;boolean network=true,failEnqueue,uncertainEnqueue,failWrite;
        Disk(Path dir)throws Exception{this.dir=dir;Files.createDirectories(dir);Properties p=load("scheduler");time=Long.parseLong(p.getProperty("time","0"));for(String id:p.getProperty("order","").split(",")){if(id.isEmpty())continue;Node n=new Node();n.job=decode(p,id+".");n.state=WindowedWorkQueue.State.valueOf(p.getProperty(id+".state"));n.dependency=empty(p.getProperty(id+".dependency"));n.cancelled=Boolean.parseBoolean(p.getProperty(id+".cancelled"));n.scheduled=Long.parseLong(p.getProperty(id+".scheduled"));jobs.put(id,n);}}
        static String empty(String s){return s==null||s.isEmpty()?null:s;}
        Properties load(String name)throws Exception{Properties p=new Properties();Path f=dir.resolve(name);if(Files.exists(f))try(var in=Files.newInputStream(f)){p.load(in);}return p;}
        void store(String name,Properties p)throws Exception{Path tmp=Files.createTempFile(dir,"write",null);try(var out=Files.newOutputStream(tmp)){p.store(out,"");}Files.move(tmp,dir.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        static WindowedWorkQueue.Job decode(Properties p,String prefix){String token=p.getProperty(prefix+"token");return token==null?null:new WindowedWorkQueue.Job(token,empty(p.getProperty(prefix+"id")),empty(p.getProperty(prefix+"parent")),Integer.parseInt(p.getProperty(prefix+"failures","0")),Long.parseLong(p.getProperty(prefix+"due","0")),Boolean.parseBoolean(p.getProperty(prefix+"standby")),Boolean.parseBoolean(p.getProperty(prefix+"held")));}
        static void encode(Properties p,String prefix,WindowedWorkQueue.Job j){p.setProperty(prefix+"token",j.token);p.setProperty(prefix+"id",j.id==null?"":j.id);p.setProperty(prefix+"parent",j.parent==null?"":j.parent);p.setProperty(prefix+"failures",""+j.failures);p.setProperty(prefix+"due",""+j.due);p.setProperty(prefix+"standby",""+j.standby);p.setProperty(prefix+"held",""+j.held);}
        public WindowedWorkQueue.Job read()throws Exception{return decode(load("queue"),"");}
        public void write(WindowedWorkQueue.Job j)throws Exception{if(failWrite){failWrite=false;throw new java.io.IOException("before durable publication");}Properties p=new Properties();encode(p,"",j);store("queue",p);}
        public void clear()throws Exception{Files.deleteIfExists(dir.resolve("queue"));}
        void save()throws Exception{Properties p=new Properties();p.setProperty("time",""+time);p.setProperty("order",String.join(",",jobs.keySet()));for(var e:jobs.entrySet()){String k=e.getKey()+".";Node n=e.getValue();encode(p,k,n.job);p.setProperty(k+"state",n.state.name());p.setProperty(k+"dependency",n.dependency==null?"":n.dependency);p.setProperty(k+"cancelled",""+n.cancelled);p.setProperty(k+"scheduled",""+n.scheduled);}store("scheduler",p);}
        public WindowedWorkQueue.State state(String id){return jobs.containsKey(id)?jobs.get(id).state:WindowedWorkQueue.State.MISSING;}
        public String running(){for(var e:jobs.entrySet())if(e.getValue().state==WindowedWorkQueue.State.RUNNING)return e.getKey();return null;}
        public long scheduled(String id){Node n=jobs.get(id);return n==null||n.dependency!=null&&state(n.dependency)!=WindowedWorkQueue.State.FINISHED?Long.MAX_VALUE:n.scheduled;}
        public void enqueue(WindowedWorkQueue.Job j,WindowedWorkQueue.Policy policy)throws Exception{
            if(failEnqueue){failEnqueue=false;throw new java.io.IOException("crash before scheduler transaction");}
            check(!jobs.containsKey(j.id),"duplicate request identity");String tail=null;for(var e:jobs.entrySet())tail=e.getKey();
            if(policy==WindowedWorkQueue.Policy.KEEP)for(Node n:jobs.values())if(n.state!=WindowedWorkQueue.State.FINISHED)return;
            if(policy==WindowedWorkQueue.Policy.REPLACE)for(Node n:jobs.values())if(n.state!=WindowedWorkQueue.State.FINISHED){n.state=WindowedWorkQueue.State.FINISHED;n.cancelled=true;}
            Node n=new Node();n.job=j;n.state=WindowedWorkQueue.State.WAITING;n.scheduled=j.due;
            if(policy==WindowedWorkQueue.Policy.APPEND&&tail!=null&&!jobs.get(tail).cancelled)n.dependency=tail;
            jobs.put(j.id,n);save();if(uncertainEnqueue){uncertainEnqueue=false;throw new java.io.IOException("crash after scheduler commit");}
        }
        public String id(){return UUID.randomUUID().toString();}public long now(){return time;}
        void advance(long t)throws Exception{time=t;save();}
        String take()throws Exception{if(!network)return null;for(var e:jobs.entrySet()){Node n=e.getValue();if(n.state==WindowedWorkQueue.State.WAITING&&time>=n.scheduled&&(n.dependency==null||state(n.dependency)==WindowedWorkQueue.State.FINISHED&&!jobs.get(n.dependency).cancelled)){check(running()==null,"two writers dispatched");n.state=WindowedWorkQueue.State.RUNNING;save();return e.getKey();}}return null;}
        void success(String id)throws Exception{Node n=jobs.get(id);check(!n.cancelled,"current request cancelled by its continuation");n.state=WindowedWorkQueue.State.FINISHED;save();}
    }
    static Path fixture()throws Exception{return Files.createTempDirectory("window-queue-");}
    static void progress59()throws Exception{
        Path dir=fixture();Disk p=new Disk(dir);new WindowedWorkQueue(p).enqueue("grant",false,false);long wait=0;Set<String> ids=new HashSet<>();
        for(int n=0;n<59;n++){
            p=new Disk(dir);WindowedWorkQueue q=new WindowedWorkQueue(p);WindowedWorkQueue.Job job=p.read();check(job.failures==0,"progress retained backoff");long delay=Math.max(0,job.due-p.now());wait+=delay;p.advance(Math.max(p.now(),job.due));String id=p.take();check(id!=null&&ids.add(id),"new window identity missing");check(q.start("grant",id),"window rejected");p.advance(p.now()+290000);
            if(n==58){q.finished("grant",id);p.success(id);check(p.read()==null,"terminal token remains");break;}
            check(q.next("grant",id,true,false),"progress lost");WindowedWorkQueue.Job next=p.read();check(!next.id.equals(id)&&next.token.equals("grant"),"grant changed");check(next.due-p.now()<=15000,"progress delayed");wait+=next.due-p.now();p.advance(next.due);check(p.take()==null,"child ran before success");q.finished("grant",id);check(p.read()!=null,"parent cleared successor");p.success(id);
            // The next loop reconstructs both scheduler and queue from disk.
            // Account/scope/epoch are revalidated by the next guest, not copied.
        }
        check(wait<=59*WindowedWorkQueue.PROGRESS_DELAY_MS,"unbounded interval sum");check(ids.size()==59,"wrong windows");
        System.out.println("WINDOW_BACKOFF_59 windows=59 uniqueRequests="+ids.size()+" sumEligibleDelayMs="+wait+" perWindowLimitMs="+WindowedWorkQueue.PROGRESS_DELAY_MS);
    }
    static void backoff()throws Exception{
        Path dir=fixture();Disk p=new Disk(dir);WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);long previous=0;
        for(int n=1;n<=40;n++){p=new Disk(dir);q=new WindowedWorkQueue(p);var job=p.read();p.advance(Math.max(p.now(),job.due));String id=p.take();check(q.start("grant",id),"retry start");q.next("grant",id,false,false);var next=p.read();long delay=next.due-p.now();check(delay>=previous&&delay<=900000,"bad retry cap");if(n<=5)check(delay>previous,"retry did not grow");previous=delay;p.success(id);}
        var job=p.read();p.advance(job.due);String id=p.take();q.start("grant",id);q.next("grant",id,true,false);check(p.read().failures==0&&p.read().due-p.now()==1000,"progress did not reset attempts");p.success(id);job=p.read();p.advance(job.due);id=p.take();q.start("grant",id);q.next("grant",id,false,false);check(p.read().due-p.now()==30000,"retry after progress retained old attempts");
        System.out.println("WINDOW_BACKOFF_FAILURE capMs="+previous+" persistedFailures=31 resetRetryMs=30000");
    }
    static void crashes()throws Exception{
        for(boolean after:new boolean[]{false,true}){Path dir=fixture();Disk p=new Disk(dir);WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);String parent=p.take();q.start("grant",parent);if(after)p.uncertainEnqueue=true;else p.failEnqueue=true;try{q.next("grant",parent,true,false);throw new AssertionError("crash missing");}catch(java.io.IOException expected){}
            String next=p.read().id;p=new Disk(dir);q=new WindowedWorkQueue(p);check(!q.start("grant",parent),"stale parent reexecuted");check(p.read().id.equals(next),"recovery changed successor");q.finished("grant",parent);p.success(parent);p.advance(p.read().due);check(next.equals(p.take()),"recovered successor missing");check(p.jobs.size()==2,"duplicate successor after uncertain commit");}
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);String id=p.take();p.failWrite=true;try{q.next("grant",id,true,false);throw new AssertionError();}catch(java.io.IOException expected){}check(p.read().id.equals(id)&&p.jobs.size()==1,"write failure enqueued unsigned successor");
    }
    static void fences()throws Exception{
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("old",false,false);String old=p.take();q.start("old",old);q.enqueue("new",false,false);String next=p.read().id;check(!q.next("old",old,true,false),"old outcome replaced new grant");q.finished("old",old);check(p.read().id.equals(next),"old terminal cleared new grant");
        p.network=false;p.advance(p.read().due);check(p.take()==null,"network constraint bypassed");p.network=true;String current=p.take();check(q.start("new",current),"new authorization not passed through");q.defer("new");q.finished("new",current);p.success(current);q.resume(true);check(p.take()==null,"local transfer resumed worker");q.resume(false);check(p.read().held==false&&p.take()!=null,"release did not resume");
        p=new Disk(fixture());q=new WindowedWorkQueue(p);q.enqueue("grant",true,false);String standby=p.read().id;q.enqueue("grant",false,false);check(!standby.equals(p.read().id),"standby not promoted");current=p.take();q.start("grant",current);int size=p.jobs.size();q.enqueue("grant",false,false);check(p.jobs.size()==size&&p.running().equals(current),"same-token KEEP cancelled writer");check(!q.next("grant","periodic",true,true),"periodic replaced live manual writer");
        q.next("grant",current,false,false);p.success(current);check(q.next("grant","periodic",true,true),"periodic progress did not reset waiting manual queue");check(p.read().failures==0&&p.read().due-p.now()==1000,"periodic reset wrong");
        q.finished("grant",p.read().id);check(!q.next("grant",current,true,false),"cancelled grant resurrected");
    }
    static void foregroundProgress()throws Exception{
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);String id=p.take();q.start("grant",id);q.next("grant",id,false,false);p.success(id);String waiting=p.read().id;check(q.progress("grant",false),"foreground progress not queued");check(!p.read().id.equals(waiting)&&p.read().failures==0&&p.read().due-p.now()==1000,"foreground kept old OS backoff");
        String next=p.read().id;check(!q.progress("old",false)&&p.read().id.equals(next),"old foreground replaced newer grant");p.advance(p.read().due);id=p.take();q.start("grant",id);check(!q.progress("grant",false)&&p.running().equals(id),"foreground replaced active writer");
    }
    static void migration()throws Exception{
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);String first=p.read().id;p.jobs.get(first).scheduled=p.now()+18000000;p.save();q.resume(false);check(!p.read().id.equals(first)&&p.read().due-p.now()<=900000,"old 5h OS delay survived");
        p=new Disk(fixture());q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);first=p.take();p.write(new WindowedWorkQueue.Job("grant",null,null,0,0,false,false));q.resume(false);check(first.equals(p.read().id),"running legacy request cancelled");q.finished("grant",first);check(p.read()==null,"legacy terminal not cleared");
    }
    static void benignConcurrency()throws Exception{
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);String id=p.take();check(q.start("grant",id)&&q.active(),"manual not active");WindowedWorkQueue.Job before=p.read();
        check(CloudWorkerOutcome.decide("active_writer","",0)==CloudWorkerOutcome.Decision.SUCCESS,"periodic collision failed");
        check(p.read().id.equals(before.id)&&p.read().failures==before.failures&&p.jobs.size()==1,"periodic changed manual queue");
        check(CloudWorkerOutcome.decide("already_running","retry",50)==CloudWorkerOutcome.Decision.SUCCESS,"Rust guard failed");
        check(CloudWorkerOutcome.decide("yielded","success",0)==CloudWorkerOutcome.Decision.SUCCESS,"writer yield failed");
        check(q.yielded("grant",id),"manual collision lost continuation");check(p.read().failures==before.failures&&p.read().due-p.now()==30000,"yield changed retry counter");p.success(id);
        check(!q.yielded("old",id),"old collision replaced successor");check(CloudWorkerOutcome.code("private arbitrary error").equals("unknown_outcome"),"raw code leaked");
        for(String code:CloudWorkerOutcome.CODES)check(!code.contains(" "),"invalid closed code");
        check(CloudWorkerOutcome.decide("deadline_exceeded","failure",0)==CloudWorkerOutcome.Decision.RETRY,"hard deadline lost retry");
        System.out.println("CONCURRENT_PERIODIC_MANUAL_PASS active_writer/already_running/yielded success; queue counters retained; genuine failures retained");
    }
    static void deadlineWindows()throws Exception{
        Disk p=new Disk(fixture());WindowedWorkQueue q=new WindowedWorkQueue(p);q.enqueue("grant",false,false);
        for(int n=1;n<=6;n++){
            var old=p.read();p.advance(old.due);String id=p.take();check(q.start("grant",id),"deadline start rejected");
            check(CloudWorkerOutcome.decide("deadline_exceeded","failure",100)==CloudWorkerOutcome.Decision.RETRY,"deadline became terminal");
            check(q.next("grant",id,false,false),"deadline successor missing");var next=p.read();
            check(next.token.equals(old.token)&&!next.id.equals(id)&&next.failures==n,"deadline grant/counter lost");
            check(next.due-p.now()==WindowedWorkQueue.retryDelay(n),"deadline incorrectly claimed progress");
            p.success(id);p=new Disk(p.dir);q=new WindowedWorkQueue(p);
        }
        check(CloudWorkerOutcome.stage("crypto.largeAesGcmDecrypt").equals("decrypt"),"decrypt stage missing");
        check(CloudWorkerOutcome.stage("file.commit").equals("file-commit"),"commit stage missing");
        check(CloudWorkerOutcome.stage("private content / account id").equals("host"),"untrusted stage leaked");
        check(CloudWorkerOutcome.guestStage("private content / account id")==null,"untrusted guest phase leaked");
        check(CloudWorkerOutcome.guestStage("checkpoint-write").equals("checkpoint-write"),"guest checkpoint phase missing");
        System.out.println("HARD_DEADLINE_RETRY_PASS windows=6 grant preserved; exponential capped backoff; no false progress");
    }
    public static void main(String[] args)throws Exception{deadlineWindows();progress59();backoff();crashes();fences();foregroundProgress();migration();benignConcurrency();System.out.println("WindowedWorkQueueTest PASS: durable progress/retry bounds, dependency success, crash publication/uncertain enqueue, grant fencing, network/hold/KEEP/REPLACE, periodic handoff and legacy delay migration");}
}
