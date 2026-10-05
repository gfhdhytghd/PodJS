package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Device-free executable regression suite; only the coordinator is compiled by javac. */
public final class TakeoverTest {
    static final String HASH="a".repeat(64);
    static class PowerLoss extends Error {}
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    static PodTakeoverCoordinator.Probe source=()->new PodTakeoverCoordinator.Source(true,null,18,10,100);
    static class Converter implements PodTakeoverCoordinator.Converter {
        final AtomicInteger copies=new AtomicInteger();
        public String version(){return "fixture-v1";}
        public void snapshot(File g)throws Exception {Files.writeString(new File(g,"snapshot").toPath(),"immutable fixture");}
        public void resume(File g,PodTakeoverCoordinator.Phase phase)throws Exception {check(new File(g,"snapshot").isFile());}
        public void copy(File g)throws Exception {
            copies.incrementAndGet();Files.createDirectories(new File(g,"podjs/files").toPath());
            for(String path:new String[]{"podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store","podjs/files/article"})Files.writeString(new File(g,path).toPath(),"fixture");
        }
        public String verify(File g)throws Exception {
            for(String path:new String[]{"snapshot","podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store","podjs/files/article"})check(new File(g,path).isFile());
            return HASH;
        }
    }
    static File temp()throws Exception{return Files.createTempDirectory("takeover-test").resolve("package-takeover").toFile();}
    static void restartEveryPhase()throws Exception {
        for(PodTakeoverCoordinator.Phase stage:PodTakeoverCoordinator.Phase.values()) {
            if(stage==PodTakeoverCoordinator.Phase.PAUSED||stage==PodTakeoverCoordinator.Phase.BLOCKED)continue;
            File root=temp();Converter converter=new Converter();
            try {new PodTakeoverCoordinator(root,source,converter,p->{if(p==stage)throw new PowerLoss();}).run();throw new AssertionError();}catch(PowerLoss expected){}
            Properties p=new PodTakeoverCoordinator(root,source,converter).run();check("ACTIVE".equals(p.getProperty("phase")));
            File g=new PodTakeoverCoordinator(root,source,converter).activeGeneration(p);check(g.isDirectory());
            int count=converter.copies.get();
            // A new user write must survive repeated launch, even when native source disappears.
            Files.writeString(new File(g,"podjs/files/article").toPath(),"new user content");
            new PodTakeoverCoordinator(root,()->{throw new AssertionError("active must not probe source");},converter).run();
            check(converter.copies.get()==count);check(Files.readString(new File(g,"podjs/files/article").toPath()).equals("new user content"));
        }
    }
    static void failures()throws Exception {
        File root=temp();Properties p=new PodTakeoverCoordinator(root,source,PodTakeoverCoordinator.EMPTY).run();
        check(p.getProperty("phase").equals("PAUSED"));check(p.getProperty("resumePhase").equals("DETECTED"));check(!new File(root,"active").exists());
        check(new PodTakeoverCoordinator(root,source,PodTakeoverCoordinator.EMPTY).run().getProperty("phase").equals("PAUSED"));
        for(String code:new String[]{"source_schema_unsupported","source_corrupt","target_conflict"}) {
            File blocked=temp();p=new PodTakeoverCoordinator(blocked,()->new PodTakeoverCoordinator.Source(true,code,19,0,1),new Converter()).run();
            check(p.getProperty("phase").equals("BLOCKED"));check(!new File(blocked,"active").exists());
        }
        AtomicBoolean space=new AtomicBoolean(false);root=temp();
        PodTakeoverCoordinator engine=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(true,null,18,10,space.get()?100:0),new Converter());
        check(engine.run().getProperty("phase").equals("PAUSED"));space.set(true);check(engine.run().getProperty("phase").equals("ACTIVE"));
        // Failed conversion never publishes; the same batch can replay after restart.
        AtomicBoolean fail=new AtomicBoolean(true);root=temp();
        Converter retry=new Converter(){public void copy(File g)throws Exception{super.copy(g);if(fail.getAndSet(false))throw new IOException("secret value must not be logged");}};
        engine=new PodTakeoverCoordinator(root,source,retry);p=engine.run();check(p.getProperty("resumePhase").equals("COPYING"));
        check(!Files.readString(new File(root,"journal").toPath()).contains("secret value"));check(engine.run().getProperty("phase").equals("ACTIVE"));
    }
    static void fresh()throws Exception {
        File root=temp();File legacy=new File(root.getParentFile(),"podjs-kv.json");Files.writeString(legacy.toPath(),"existing user data");
        PodTakeoverCoordinator engine=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(false,null,0,0,0),new Converter());
        check(engine.activeGeneration(engine.run())==null);check(!root.exists());check(Files.readString(legacy.toPath()).equals("existing user data"));
    }
    static void terminalAdmissionCache()throws Exception {
        for(boolean nativeSource:new boolean[]{false,true}) {
            File root=temp();AtomicInteger detects=new AtomicInteger(),resolutions=new AtomicInteger();
            AtomicBoolean broken=new AtomicBoolean();PodTakeoverAdmission cache=new PodTakeoverAdmission();
            PodTakeoverCoordinator.Probe probe=()->{
                detects.incrementAndGet();if(broken.get())throw new IOException("transient detection failure");
                return new PodTakeoverCoordinator.Source(nativeSource,null,18,10,100);
            };
            PodTakeoverAdmission.Resolver resolve=()->{
                resolutions.incrementAndGet();
                return PodTakeoverAdmission.Paths.resolve(new PodTakeoverCoordinator(root,probe,new Converter()),
                    new File(root.getParentFile(),"podjs"),new File(root.getParentFile(),"podjs.sqlite"));
            };
            PodTakeoverAdmission.Paths first=cache.paths(resolve);int before=detects.get();
            check(nativeSource?first.data.getParentFile().getParentFile().equals(new File(root,"generations")):!root.exists());
            broken.set(true);
            if(nativeSource)Files.delete(new File(root,"active").toPath()); // no pointer re-read on hot paths either
            for(int i=0;i<100;i++) {
                PodTakeoverAdmission.Paths next=cache.paths(resolve);
                check(next==first);check(next.data==first.data);check(next.files==first.files);check(next.database==first.database);
            }
            check(detects.get()==before);check(resolutions.get()==1);
            try {new PodTakeoverAdmission().paths(resolve);throw new AssertionError();}catch(IOException expected){}
        }
        // Exceptions and unadmitted states are not cached: a later call can retry.
        File root=temp();PodTakeoverAdmission cache=new PodTakeoverAdmission();AtomicInteger retries=new AtomicInteger();
        PodTakeoverAdmission.Resolver retry=()->{
            if(retries.incrementAndGet()==1)throw new IOException("temporarily unavailable");
            return PodTakeoverAdmission.Paths.resolve(new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(false,null,0,0,0),new Converter()),new File(root,"data"),new File(root,"db"));
        };
        try{cache.paths(retry);throw new AssertionError();}catch(IOException expected){}
        check(cache.paths(retry)==cache.paths(retry));check(retries.get()==2);
        File pending=temp();AtomicInteger attempts=new AtomicInteger();PodTakeoverAdmission paused=new PodTakeoverAdmission();
        for(int i=0;i<2;i++)try{
            paused.paths(()->{attempts.incrementAndGet();return PodTakeoverAdmission.Paths.resolve(new PodTakeoverCoordinator(pending,source,PodTakeoverCoordinator.EMPTY),new File(pending,"data"),new File(pending,"db"));});throw new AssertionError();
        }catch(IOException expected){}
        check(attempts.get()==2);
    }
    static void concurrentAdmission()throws Exception {
        File root=temp();AtomicInteger detects=new AtomicInteger(),resolutions=new AtomicInteger();
        PodTakeoverAdmission cache=new PodTakeoverAdmission();CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(16);
        try {
            List<Future<PodTakeoverAdmission.Paths>> runs=new ArrayList<>();
            for(int i=0;i<16;i++)runs.add(pool.submit(()->{
                start.await();return cache.paths(()->{
                    resolutions.incrementAndGet();
                    return PodTakeoverAdmission.Paths.resolve(new PodTakeoverCoordinator(root,()->{
                        detects.incrementAndGet();Thread.sleep(20);return new PodTakeoverCoordinator.Source(false,null,0,0,0);
                    },new Converter()),new File(root.getParentFile(),"podjs"),new File(root.getParentFile(),"podjs.sqlite"));
                });
            }));
            start.countDown();PodTakeoverAdmission.Paths first=runs.get(0).get();
            for(Future<PodTakeoverAdmission.Paths> run:runs)check(run.get()==first);
            check(resolutions.get()==1);check(detects.get()==1);check(!root.exists());
        }finally{pool.shutdownNow();}
    }
    static void noSourceNeverWrites()throws Exception {
        for(int existing=0;existing<3;existing++) {
            File root=temp();
            if(existing>0)root.mkdirs();
            File journal=new File(root,"journal");
            if(existing==2)Files.writeString(journal.toPath(),"");
            java.nio.file.attribute.FileTime before=existing>0?Files.getLastModifiedTime(root.toPath()):null;
            for(int i=0;i<3;i++)check(new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(false,null,0,0,0),new Converter()).run().getProperty("phase").equals("NONE"));
            if(existing==0)check(!root.exists());
            else {check(root.list().length==existing-1);check(Files.getLastModifiedTime(root.toPath()).equals(before));}
            check(!new File(root,"lock").exists());check(!new File(root,"active").exists());
            check(existing==2?journal.length()==0:!journal.exists());
        }
    }
    static void atomicPointer()throws Exception {
        File root=temp();root.mkdirs();File pointer=new File(root,"active");Properties a=new Properties();a.setProperty("generation","g-one");a.setProperty("receipt",HASH);
        PodTakeoverCoordinator.atomic(pointer,a);AtomicBoolean stop=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread reader=new Thread(()->{try{while(!stop.get()){Properties p=PodTakeoverCoordinator.read(pointer);check(p.getProperty("receipt").equals(HASH));check(Set.of("g-one","g-two").contains(p.getProperty("generation")));}}catch(Throwable e){failure.set(e);}});reader.start();
        for(int i=0;i<100;i++){a.setProperty("generation",i%2==0?"g-one":"g-two");PodTakeoverCoordinator.atomic(pointer,a);}
        stop.set(true);reader.join();check(failure.get()==null);
        // Torn temporary file is never a committed pointer.
        Files.writeString(new File(root,"active.tmp").toPath(),"generation=g-");check(PodTakeoverCoordinator.read(pointer).getProperty("receipt").equals(HASH));
    }
    static void publicationGapAndCorruption()throws Exception {
        File root=temp();Converter converter=new Converter();
        try {
            new PodTakeoverCoordinator(root,source,converter,phase->{
                if(phase==PodTakeoverCoordinator.Phase.ACTIVATING) {
                    try {PodTakeoverCoordinator.atomic(new File(root,"active"),PodTakeoverCoordinator.read(new File(root,"journal")));}
                    catch(IOException e){throw new AssertionError(e);}
                    throw new PowerLoss(); // pointer durable, ACTIVE journal not yet durable
                }
            }).run();throw new AssertionError();
        }catch(PowerLoss expected){}
        check(new PodTakeoverCoordinator(root,source,converter).run().getProperty("phase").equals("ACTIVE"));
        check(converter.copies.get()==1);
        Files.writeString(new File(root,"active").toPath(),"generation=../../outside");
        try {new PodTakeoverCoordinator(root,source,converter).run();throw new AssertionError();}catch(IOException expected){}
        File changed=temp();new PodTakeoverCoordinator(changed,source,PodTakeoverCoordinator.EMPTY).run();
        check(new PodTakeoverCoordinator(changed,source,converter).run().getProperty("phase").equals("BLOCKED"));
        File badReceipt=temp();Converter invalid=new Converter(){public String verify(File g){return "";}};
        check(new PodTakeoverCoordinator(badReceipt,source,invalid).run().getProperty("phase").equals("PAUSED"));check(!new File(badReceipt,"active").exists());
    }
    static void concurrency()throws Exception {
        File root=temp();Converter converter=new Converter();ExecutorService pool=Executors.newFixedThreadPool(8);
        try {List<Future<Properties>> runs=new ArrayList<>();for(int i=0;i<16;i++)runs.add(pool.submit(()->new PodTakeoverCoordinator(root,source,converter).run()));
            for(Future<Properties> run:runs)check(run.get().getProperty("phase").equals("ACTIVE"));check(converter.copies.get()==1);
        }finally{pool.shutdownNow();}
        // A separate JVM must wait for the OS file lock, not merely a Java monitor.
        File locked=temp();locked.mkdirs();
        Process child;
        try(RandomAccessFile file=new RandomAccessFile(new File(locked,"lock"),"rw");java.nio.channels.FileLock lock=file.getChannel().lock()) {
            child=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),TakeoverTest.class.getName(),locked.toString()).inheritIO().start();
            check(!child.waitFor(250,TimeUnit.MILLISECONDS));check(!new File(locked,"journal").exists());
        }
        check(child.waitFor(10,TimeUnit.SECONDS));check(child.exitValue()==0);
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0){check(new PodTakeoverCoordinator(new File(args[0]),source,new Converter()).run().getProperty("phase").equals("ACTIVE"));return;}
        terminalAdmissionCache();concurrentAdmission();noSourceNeverWrites();restartEveryPhase();failures();fresh();atomicPointer();publicationGapAndCorruption();concurrency();System.out.println("takeover JVM: PASS (terminal admission cache, concurrent single resolution, no-source zero writes, phase restart, pause/block, atomic pointer, thread/process locks)");
    }
}
