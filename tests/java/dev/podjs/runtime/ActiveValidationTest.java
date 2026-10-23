package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ActiveValidationTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static final String RECEIPT="a".repeat(64);
 static void expect(String code,PodActiveValidation.Check c)throws Exception{try{c.run();throw new AssertionError("accepted "+code);}catch(IOException e){check(code.equals(e.getMessage()));}}
 public static void main(String[] args)throws Exception {
  File parent=Files.createTempDirectory("active-policy").toFile(),g=new File(parent,"g-test");g.mkdir();
  try {
   AtomicLong clock=new AtomicLong(1000);PodActiveValidation v1=new PodActiveValidation(1,clock::get);
   AtomicInteger fast=new AtomicInteger(),deep=new AtomicInteger();
   v1.require(g,RECEIPT,fast::incrementAndGet);check(fast.get()==1);check(v1.due(g,RECEIPT));
   check(v1.deep(g,RECEIPT,deep::incrementAndGet));check(deep.get()==1);check(!v1.due(g,RECEIPT));
   v1.require(g,RECEIPT,fast::incrementAndGet);check(fast.get()==2);
   PodActiveValidation v2=new PodActiveValidation(2,clock::get);check(!v2.due(g,RECEIPT)); // update cannot defeat weekly gate
   clock.addAndGet(PodActiveValidation.INTERVAL-1);check(!v2.due(g,RECEIPT));clock.incrementAndGet();check(v2.due(g,RECEIPT));
   expect("active_validation_yield",()->v2.deep(g,RECEIPT,()->{throw new PodActiveValidation.Yield();}));
   v2.require(g,RECEIPT,()->{});check(v2.due(g,RECEIPT));
   check(v2.deep(g,RECEIPT,deep::incrementAndGet));check(!v2.due(g,RECEIPT));
   clock.set(0);check(!v2.due(g,RECEIPT));clock.set(1000+2*PodActiveValidation.INTERVAL);
   expect("broken_fixture",()->v2.deep(g,RECEIPT,()->{throw new IOException("broken_fixture");}));
   expect("active_deep_validation_failed",()->new PodActiveValidation(3,clock::get).require(g,RECEIPT,()->{throw new AssertionError("fast ran after fail");}));
   expect("active_validation_record_invalid",()->v2.require(g,"b".repeat(64),()->{}));
   // Real coordinator publication still calls full admission, then fast only across startups.
   File root=new File(parent,"takeover");AtomicInteger full=new AtomicInteger(),quick=new AtomicInteger();
   TakeoverTest.Converter converter=new TakeoverTest.Converter(){
    public void active(File dir){full.incrementAndGet();}
    public void activeFast(File dir){quick.incrementAndGet();}
   };
   PodTakeoverCoordinator c=new PodTakeoverCoordinator(root,TakeoverTest.source,converter).withActiveValidation(v2);
   Properties active=c.run();check("ACTIVE".equals(active.getProperty("phase")));check(full.get()==1);check(quick.get()==0);
   File current=c.activeGeneration(active);check("ACTIVE".equals(c.run().getProperty("phase")));check(full.get()==1&&quick.get()==1);
   Properties pointer=PodTakeoverCoordinator.read(new File(root,"active"));pointer.setProperty("receipt","b".repeat(64));PodTakeoverCoordinator.atomic(new File(root,"active"),pointer);
   expect("pointer_mismatch",c::run);pointer.setProperty("receipt",active.getProperty("receipt"));PodTakeoverCoordinator.atomic(new File(root,"active"),pointer);
   v2.completed(current,active.getProperty("receipt"),false);Properties blocked=c.run();check("BLOCKED".equals(blocked.getProperty("phase")));check("active_deep_validation_failed".equals(blocked.getProperty("reason")));check(new File(root,"active").isFile());
   expect("takeover_pending",()->c.activeGeneration(blocked));check("BLOCKED".equals(c.run().getProperty("phase")));
   // Host schema recognition repair must reopen the exact published data,
   // never replay an old snapshot over writes made after activation.
   File repairRoot=new File(parent,"schema-repair");AtomicBoolean unsupported=new AtomicBoolean();AtomicInteger repairs=new AtomicInteger();
   TakeoverTest.Converter repairedConverter=new TakeoverTest.Converter(){
    public void active(File dir){repairs.incrementAndGet();}
    public void activeFast(File dir)throws IOException{if(unsupported.get())throw new IOException("active_schema_version_invalid");}
   };
   PodTakeoverCoordinator repair=new PodTakeoverCoordinator(repairRoot,TakeoverTest.source,repairedConverter).withActiveValidation(v2);
   Properties published=repair.run();int copiesBefore=repairedConverter.copies.get();File preserved=repair.activeGeneration(published);File userWrite=new File(preserved,"post-activation.txt");Files.writeString(userWrite.toPath(),"keep latest user data");
   unsupported.set(true);Properties schemaBlocked=repair.run();check("active_validation_failed".equals(schemaBlocked.getProperty("reason")));check("BLOCKED".equals(repair.run().getProperty("phase")));
   unsupported.set(false);Properties recovered=repair.run();check("ACTIVE".equals(recovered.getProperty("phase")));check(preserved.equals(repair.activeGeneration(recovered)));check("keep latest user data".equals(Files.readString(userWrite.toPath())));check(repairs.get()==2);check(repairedConverter.copies.get()==copiesBefore);
   // A missing required store still vetoes before any converter can run.
   File missingRoot=new File(parent,"missing-takeover");PodTakeoverCoordinator missing=new PodTakeoverCoordinator(missingRoot,TakeoverTest.source,converter).withActiveValidation(v2);
   Properties healthy=missing.run();File missingGeneration=missing.activeGeneration(healthy);Files.delete(new File(missingGeneration,"podjs/podjs-kv.json").toPath());
   expect("active_store_missing",missing::run);
   // Initial full admission failure cannot become a legacy fast-path bootstrap.
   File failedRoot=new File(parent,"failed-publication");PodTakeoverCoordinator failed=new PodTakeoverCoordinator(failedRoot,TakeoverTest.source,new TakeoverTest.Converter(){public void active(File dir)throws IOException{throw new IOException("initial_full_failed");}}).withActiveValidation(v2);
   expect("initial_full_failed",failed::run);check("BLOCKED".equals(failed.run().getProperty("phase")));
   // Reader cancellation allows the foreground to progress; contention never records FAIL.
   PodActiveValidation.Workload load=new PodActiveValidation.Workload();AtomicBoolean cancel=new AtomicBoolean();
   try(PodActiveValidation.Workload.Reader reader=load.read(()->cancel.set(true))) {
    try(AutoCloseable writer=load.foreground()){check(cancel.get());expect("active_validation_yield",reader::check);expect("active_validation_yield",()->load.read(()->{}));}
   }
   try(PodActiveValidation.Workload.Reader reader=load.read(()->{})){reader.check();}
   System.out.println("ActiveValidationTest PASS: legacy bootstrap, fast admission, receipt/pointer veto, initial full check, durable FAIL/recovery, versions/weekly/clock throttling, cancellation/yield");
  }finally{try(var paths=Files.walk(parent.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
