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
    static void detectedRestartRejectsPublicationEvidence()throws Exception {
        for(String evidence:new String[]{"active","activation-receipt","native-backup-receipt","generation-link"}) {
            File root=temp();Converter converter=new Converter(){public void snapshot(File g)throws Exception{throw new IOException("synthetic snapshot failure");}};
            Properties paused=new PodTakeoverCoordinator(root,source,converter).run();
            byte[] journal=Files.readAllBytes(new File(root,"journal").toPath());
            File previous=new File(root,"generations/"+paused.getProperty("generation"));
            if(evidence.equals("generation-link")) {
                File retained=new File(previous.getParentFile(),"retained");Files.move(previous.toPath(),retained.toPath());Files.createSymbolicLink(previous.toPath(),retained.toPath());
            }else Files.writeString(new File(evidence.equals("active")?root:previous,evidence).toPath(),"synthetic evidence");
            try{new PodTakeoverCoordinator(root,source,converter).run();throw new AssertionError("unsafe restart accepted");}
            catch(IOException expected){check(expected.getMessage().equals(evidence.equals("active")?"unexpected_pointer":evidence.equals("generation-link")?"generation_symlink":"unexpected_activation"));}
            check(Arrays.equals(journal,Files.readAllBytes(new File(root,"journal").toPath())));
            try(java.util.stream.Stream<Path> paths=Files.walk(root.getParentFile().toPath())) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
    static void fresh()throws Exception {
        File root=temp();File legacy=new File(root.getParentFile(),"podjs-kv.json");Files.writeString(legacy.toPath(),"existing user data");
        PodTakeoverCoordinator engine=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(false,null,0,0,0),new Converter());
        check(engine.activeGeneration(engine.run())==null);check(!root.exists());check(Files.readString(legacy.toPath()).equals("existing user data"));
    }
    static void podjsOnlyIsNotNative()throws Exception {
        File sandbox=Files.createTempDirectory("takeover-podjs-only").toFile();
        for(String path:new String[]{"shared_prefs/WebViewChromiumPrefs.xml","databases/podjs.sqlite","files/podjs/podjs-kv.json","files/podjs/secure.store","files/podjs/files/article_content/own.html","cache/rss_images/own-cache","shared_prefs/unknown-host.xml"}) {
            File file=new File(sandbox,path);file.getParentFile().mkdirs();Files.writeString(file.toPath(),"host-owned unchanged bytes");
        }
        Map<String,java.nio.file.attribute.FileTime> times=new TreeMap<>();Map<String,String> bytes=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(sandbox.toPath())){for(Path path:(Iterable<Path>)paths::iterator){String key=sandbox.toPath().relativize(path).toString();times.put(key,Files.getLastModifiedTime(path));if(Files.isRegularFile(path))bytes.put(key,Files.readString(path));}}
        File root=new File(sandbox,"files/package-takeover");AtomicInteger probes=new AtomicInteger();
        check(!PodTakeoverSource.detect(sandbox,false).present); // locked fresh install is still NONE
        for(int i=0;i<3;i++) {
            Properties inventory=new Properties();
            check(!PodTakeoverSource.inventory(sandbox,inventory));check(inventory.values().stream().allMatch("false"::equals));
            PodTakeoverCoordinator coordinator=new PodTakeoverCoordinator(root,()->{probes.incrementAndGet();return PodTakeoverSource.detect(sandbox,true);},EMPTY_MUST_NOT_RUN);
            Properties p=coordinator.run();check(p.getProperty("phase").equals("NONE"));check(coordinator.activeGeneration(p)==null);check(!root.exists());
        }
        check(probes.get()==3);
        try(java.util.stream.Stream<Path> paths=Files.walk(sandbox.toPath())){List<Path> all=paths.toList();check(all.size()==times.size());for(Path path:all){String key=sandbox.toPath().relativize(path).toString();check(times.get(key).equals(Files.getLastModifiedTime(path)));if(Files.isRegularFile(path))check(bytes.get(key).equals(Files.readString(path)));}}
        // A genuine native orphan must still block rather than masquerade as fresh.
        for(String path:new String[]{"databases/watchrss.db-wal","shared_prefs/watchrss_account_state.xml.bak","files/datastore/settings.preferences_pb.preferences_pb","files/notes/assets/image"}) {
            File nativeFile=new File(sandbox,path);nativeFile.getParentFile().mkdirs();Files.writeString(nativeFile.toPath(),"native evidence");check(PodTakeoverSource.inventory(sandbox,new Properties()));check(PodTakeoverSource.detect(sandbox,true).present);Files.delete(nativeFile.toPath());
        }
    }
    static final PodTakeoverCoordinator.Converter EMPTY_MUST_NOT_RUN=new Converter(){public void snapshot(File g){throw new AssertionError("no-source converter called");}};
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
        Thread reader=new Thread(()->{try{while(!stop.get()){Properties p=PodTakeoverCoordinator.read(pointer);check(Set.of("g-one","g-two").contains(p.getProperty("generation")));check(p.getProperty("receipt").equals(p.getProperty("generation").equals("g-one")?HASH:"b".repeat(64)));}}catch(Throwable e){failure.set(e);}});reader.start();
        for(int i=0;i<100;i++){a.setProperty("generation",i%2==0?"g-one":"g-two");a.setProperty("receipt",i%2==0?HASH:"b".repeat(64));PodTakeoverCoordinator.atomic(pointer,a);}
        stop.set(true);reader.join();check(failure.get()==null);
        // Torn temporary file is never a committed pointer.
        Files.writeString(new File(root,"active.tmp").toPath(),"generation=g-");check(PodTakeoverCoordinator.read(pointer).getProperty("receipt").equals("b".repeat(64)));
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
    static void activationRechecksGates()throws Exception {
        for(PodTakeoverCoordinator.Phase phase:new PodTakeoverCoordinator.Phase[]{PodTakeoverCoordinator.Phase.READY,PodTakeoverCoordinator.Phase.ACTIVATING}) {
            File root=temp();AtomicBoolean blocked=new AtomicBoolean();
            Converter converter=new Converter(){public String blocker(File g){return blocked.get()?"needs_user_confirmation":null;}};
            Properties p=new PodTakeoverCoordinator(root,source,converter,saved->{if(saved==phase)blocked.set(true);}).run();
            check(p.getProperty("phase").equals("BLOCKED"));check(p.getProperty("reason").equals("needs_user_confirmation"));check(!new File(root,"active").exists());
        }
        File root=temp();AtomicBoolean complete=new AtomicBoolean(true);
        Converter converter=new Converter(){public boolean complete(){return complete.get();}};
        Properties p=new PodTakeoverCoordinator(root,source,converter,saved->{if(saved==PodTakeoverCoordinator.Phase.ACTIVATING)complete.set(false);}).run();
        check(p.getProperty("phase").equals("PAUSED"));check(!new File(root,"active").exists());
        // Neither a journal nor its temporary/lock path may redirect writes outside the candidate.
        for(String name:new String[]{"journal","journal.tmp","lock"}) {
            File linked=temp();linked.mkdirs();File outside=Files.createTempFile("takeover-control-link","").toFile();Files.writeString(outside.toPath(),"untouched");
            Files.createSymbolicLink(new File(linked,name).toPath(),outside.toPath());
            try{new PodTakeoverCoordinator(linked,source,new Converter()).run();throw new AssertionError();}catch(IOException expected){}
            check(Files.readString(outside.toPath()).equals("untouched"));
        }
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
    static void backupPublication()throws Exception {
        File sandbox=Files.createTempDirectory("takeover-backup-publication-").toFile();
        File db=new File(sandbox,"databases/watchrss.db");db.getParentFile().mkdirs();Files.writeString(db.toPath(),"native fixture");
        File root=new File(sandbox,"files/package-takeover");AtomicBoolean interrupt=new AtomicBoolean(true);
        PodTakeoverCoordinator.Publisher publisher=new PodTakeoverCoordinator.Publisher(){
            public void beforePublish(File g)throws Exception {
                new dev.podjs.takeover.NativeBackup(sandbox,b->{if(b.equals("backup-move-0")&&interrupt.getAndSet(false))throw new IOException("interrupted");},File::getUsableSpace).prepare(g.getName(),40,HASH);
            }
        };
        PodTakeoverCoordinator c=new PodTakeoverCoordinator(root,source,new Converter(),p->{},publisher);
        Properties state=c.run();check(state.getProperty("phase").equals("PAUSED"));check(state.getProperty("resumePhase").equals("ACTIVATING"));check(!new File(root,"active").exists());
        c=new PodTakeoverCoordinator(root,()->{throw new AssertionError("resume must use journal after native rename");},new Converter(),p->{},publisher);
        check(c.run().getProperty("phase").equals("ACTIVE"));check(!db.exists());
        new dev.podjs.takeover.NativeBackup(sandbox).verify(state.getProperty("generation"));
    }
    static void digestPublicationHooks()throws Exception {
        File root=temp();List<String> order=new ArrayList<>();AtomicBoolean fail=new AtomicBoolean(true);
        Converter converter=new Converter(){public void copy(File g)throws Exception{order.add("copy");super.copy(g);}};
        PodTakeoverCoordinator.Publisher publisher=new PodTakeoverCoordinator.Publisher(){
            public void afterSnapshot(File g)throws Exception {
                Properties p=PodTakeoverCoordinator.read(new File(root,"journal"));
                check(p.getProperty("phase").equals("SNAPSHOTTED")||(p.getProperty("phase").equals("PAUSED")&&p.getProperty("resumePhase").equals("SNAPSHOTTED")));
                if(fail.getAndSet(false))throw new IOException("private source must never be logged");
                order.add("snapshot");
            }
            public void beforePublish(File g){order.add("seal-ready");}
            public void afterActive(File g)throws Exception {
                check(PodTakeoverCoordinator.read(new File(root,"journal")).getProperty("phase").equals("ACTIVE"));
                check(new File(root,"active").isFile());order.add("active");
            }
        };
        PodTakeoverCoordinator coordinator=new PodTakeoverCoordinator(root,source,converter,p->{},publisher);
        Properties paused=coordinator.run();check(paused.getProperty("resumePhase").equals("SNAPSHOTTED"));
        check(!new File(root,"active").exists()&&order.isEmpty());
        check(!Files.readString(new File(root,"journal").toPath()).contains("private"));
        check(coordinator.run().getProperty("phase").equals("ACTIVE"));
        check(order.equals(List.of("snapshot","copy","seal-ready","active")));
        coordinator.run();check(order.equals(List.of("snapshot","copy","seal-ready","active","active")));
    }
    public static void main(String[] args)throws Exception {
        backupPublication();digestPublicationHooks();detectedRestartRejectsPublicationEvidence();
        if(args.length>0){check(new PodTakeoverCoordinator(new File(args[0]),source,new Converter()).run().getProperty("phase").equals("ACTIVE"));return;}
        podjsOnlyIsNotNative();terminalAdmissionCache();concurrentAdmission();noSourceNeverWrites();restartEveryPhase();failures();fresh();atomicPointer();publicationGapAndCorruption();activationRechecksGates();concurrency();System.out.println("takeover JVM: PASS (terminal admission cache, concurrent single resolution, no-source zero writes, phase restart, pause/block, atomic pointer, thread/process locks)");
    }
}
