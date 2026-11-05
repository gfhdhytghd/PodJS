package dev.podjs.runtime;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;

/** Device-free deletion tests; payloads are synthetic, no accounts or device input. */
public final class InactiveGenerationGcTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void write(File root,String name,String value)throws Exception{File f=new File(root,name);Files.createDirectories(f.getParentFile().toPath());Files.writeString(f.toPath(),value);}
 static File setup()throws Exception {
  File s=Files.createTempDirectory("inactive-gc").toFile(),r=new File(s,"files/package-takeover"),g=new File(r,"generations/g-live");
  for(String name:new String[]{"podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store","podjs/files/keep"})write(g,name,"live user data");
  write(r,"lock","");Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("phase","ACTIVE");p.setProperty("generation","g-live");p.setProperty("receipt","a".repeat(64));PodTakeoverCoordinator.atomic(new File(r,"journal"),p);
  Properties a=new Properties();a.setProperty("generation","g-live");a.setProperty("receipt","a".repeat(64));PodTakeoverCoordinator.atomic(new File(r,"active"),a);PodTakeoverCoordinator.atomic(new File(g,"activation-receipt"),a);
  write(r,"generations/g-old/a/b","obsolete");write(r,"generations/g-old/activation-receipt","old metadata");write(r,"generations/g-unapproved/keep","unapproved");return s;
 }
 interface Action{void run()throws Exception;}
 static void fails(String message,Action a)throws Exception{try{a.run();throw new AssertionError("accepted "+message);}catch(IOException expected){check(expected.getMessage().equals(message));}}
 static PodTakeoverCoordinator coordinator(File s){return new PodTakeoverCoordinator(new File(s,"files/package-takeover"),()->{throw new AssertionError("must not migrate");},PodTakeoverCoordinator.EMPTY);}
 static void validAndBudget()throws Exception {
  File s=setup();String body=Files.readString(new File(s,"files/package-takeover/generations/g-live/podjs/files/keep").toPath());
  PodInactiveGenerationCollector.Result first=coordinator(s).collectInactiveGenerations(List.of("g-old"),1);check(first.deletedEntries==1);check(new File(s,"files/package-takeover/generations/g-old").exists());
  int total=first.deletedEntries;for(int i=0;i<10&&new File(s,"files/package-takeover/generations/g-old").exists();i++)total+=coordinator(s).collectInactiveGenerations(List.of("g-old"),1).deletedEntries;
  check(total==4);check(!new File(s,"files/package-takeover/generations/g-old").exists());check(new File(s,"files/package-takeover/generations/g-unapproved/keep").isFile());check(body.equals(Files.readString(new File(s,"files/package-takeover/generations/g-live/podjs/files/keep").toPath())));
  check(coordinator(s).collectInactiveGenerations(List.of("g-old"),10).deletedEntries==0);
 }
 static void protectedAndRecovery()throws Exception {
  File s=setup();fails("generation_gc_protected",()->coordinator(s).collectInactiveGenerations(List.of("g-old","g-live"),100));check(new File(s,"files/package-takeover/generations/g-old/a/b").exists());
  write(s,"files/.podjs-takeover-backup/g-old/payload","recovery payload");fails("generation_gc_protected",()->coordinator(s).collectInactiveGenerations(List.of("g-old"),100));check(new File(s,"files/.podjs-takeover-backup/g-old/payload").isFile());
  for(String recovery:new String[]{".podjs-recovered",".podjs-restore-stage",".podjs-recovery-state"}){File t=setup();write(t,"files/"+recovery,"recovery evidence");fails("generation_gc_recovery_present",()->coordinator(t).collectInactiveGenerations(List.of("g-old"),100));check(new File(t,"files/package-takeover/generations/g-old/a/b").exists());}
 }
 static void malformedAndSymlinks()throws Exception {
  for(String bad:new String[]{"../g-old","g-old/../../escape","","g_old"}){File s=setup();fails("generation_gc_invalid_id",()->coordinator(s).collectInactiveGenerations(List.of("g-old",bad),100));check(new File(s,"files/package-takeover/generations/g-old/a/b").exists());}
  File s=setup(),outside=Files.createTempDirectory("outside-gc").toFile();write(outside,"keep","external payload");Files.createSymbolicLink(new File(s,"files/package-takeover/generations/g-old/z-link").toPath(),outside.toPath());fails("generation_gc_symlink",()->coordinator(s).collectInactiveGenerations(List.of("g-old"),100));check(new File(s,"files/package-takeover/generations/g-old/a/b").exists());check(Files.readString(new File(outside,"keep").toPath()).equals("external payload"));
  File t=setup();fails("generation_gc_duplicate_id",()->coordinator(t).collectInactiveGenerations(List.of("g-old","g-old"),100));fails("generation_gc_policy_invalid",()->coordinator(t).collectInactiveGenerations(List.of("g-old"),0));
  Properties p=PodTakeoverCoordinator.read(new File(t,"files/package-takeover/journal"));p.setProperty("phase","PAUSED");PodTakeoverCoordinator.atomic(new File(t,"files/package-takeover/journal"),p);fails("generation_gc_not_active",()->coordinator(t).collectInactiveGenerations(List.of("g-old"),100));
 }
 static void recoveryLock()throws Exception {
  File s=setup(),lock=new File(s,"files/.podjs-recovery-lock");write(s,"files/.podjs-recovery-lock","stable recovery lock");
  try(java.nio.channels.FileChannel c=java.nio.channels.FileChannel.open(lock.toPath(),StandardOpenOption.WRITE);java.nio.channels.FileLock held=c.lock()) {
   try{coordinator(s).collectInactiveGenerations(List.of("g-old"),100);throw new AssertionError();}catch(java.nio.channels.OverlappingFileLockException expected){check(new File(s,"files/package-takeover/generations/g-old/a/b").isFile());}
  }
  coordinator(s).collectInactiveGenerations(List.of("g-old"),100);check(Files.readString(lock.toPath()).equals("stable recovery lock"));
 }
 static void interruptedAndConcurrent()throws Exception {
  File s=setup();final int[] unlinks={0};try{new PodInactiveGenerationCollector(s,()->{if(++unlinks[0]==1)throw new IOException("synthetic interruption");}).collect(List.of("g-old"),100);throw new AssertionError();}catch(IOException e){check(e.getMessage().equals("synthetic interruption"));}
  coordinator(s).collectInactiveGenerations(List.of("g-old"),100);check(!new File(s,"files/package-takeover/generations/g-old").exists());check(new File(s,"files/package-takeover/generations/g-live/podjs.sqlite").isFile());
  File t=setup();ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<PodInactiveGenerationCollector.Result> a=pool.submit(()->coordinator(t).collectInactiveGenerations(List.of("g-old"),100)),b=pool.submit(()->coordinator(t).collectInactiveGenerations(List.of("g-old"),100));check(a.get().deletedEntries+b.get().deletedEntries==4);}finally{pool.shutdownNow();}
 }
 static void inactiveProtectsAndFilters()throws Exception {
  File s=setup(),r=new File(s,"files/package-takeover"),g=new File(r,"generations");
  write(r,"generations/g-retained/nested/keep","retained user data");write(s,"files/.podjs-takeover-backup/g-retained/payload","sealed backup");
  write(r,"generations/g-pending/nested/keep","pending backup data");write(s,"files/.podjs-takeover-backup/.purging-g-pending/payload","retained trash");
  write(r,"generations/g-another-user-42/data","another user's obsolete generation");
  String[] ignored={"g_ignored","g-","g-bad.name",".staging","other"};for(String name:ignored)write(g,name+"/keep","unowned entry");
  write(g,"g-file","not a generation directory");File outside=Files.createTempDirectory("inactive-ignored-link").toFile();write(outside,"keep","external data");Files.createSymbolicLink(new File(g,"not-a-generation").toPath(),outside.toPath());
  PodInactiveGenerationCollector.Result result=coordinator(s).collectInactiveGenerations(List.of("inactive"),100);
  check(result.deletedEntries>0);for(String name:new String[]{"g-old","g-unapproved","g-another-user-42"})check(!new File(g,name).exists());
  check(Files.readString(new File(g,"g-live/podjs/files/keep").toPath()).equals("live user data"));
  check(Files.readString(new File(g,"g-retained/nested/keep").toPath()).equals("retained user data"));check(new File(s,"files/.podjs-takeover-backup/g-retained/payload").isFile());
  check(new File(g,"g-pending/nested/keep").isFile());check(new File(s,"files/.podjs-takeover-backup/.purging-g-pending/payload").isFile());
  for(String name:ignored)check(Files.readString(new File(g,name+"/keep").toPath()).equals("unowned entry"));
  check(Files.readString(new File(g,"g-file").toPath()).equals("not a generation directory"));check(Files.isSymbolicLink(new File(g,"not-a-generation").toPath()));check(Files.readString(new File(outside,"keep").toPath()).equals("external data"));
 }
 static void inactiveRecoveryAndPreflight()throws Exception {
  for(String recovery:new String[]{".podjs-recovered",".podjs-restore-stage",".podjs-recovery-state"}){
   File s=setup();Files.createDirectories(new File(s,"files/"+recovery).toPath());fails("generation_gc_recovery_present",()->coordinator(s).collectInactiveGenerations(List.of("inactive"),100));check(new File(s,"files/package-takeover/generations/g-old/a/b").isFile());
  }
  File s=setup(),outside=Files.createTempDirectory("inactive-rejected-link").toFile();write(outside,"keep","external data");Files.createSymbolicLink(new File(s,"files/package-takeover/generations/g-z-link").toPath(),outside.toPath());
  fails("generation_gc_symlink",()->coordinator(s).collectInactiveGenerations(List.of("inactive"),100));check(new File(s,"files/package-takeover/generations/g-old/a/b").isFile());check(new File(outside,"keep").isFile());
  File t=setup();fails("generation_gc_invalid_id",()->coordinator(t).collectInactiveGenerations(List.of("g-old","inactive"),100));check(new File(t,"files/package-takeover/generations/g-old/a/b").isFile());
  Properties journal=PodTakeoverCoordinator.read(new File(t,"files/package-takeover/journal"));journal.setProperty("phase","PAUSED");PodTakeoverCoordinator.atomic(new File(t,"files/package-takeover/journal"),journal);fails("generation_gc_not_active",()->coordinator(t).collectInactiveGenerations(List.of("inactive"),100));
  File u=setup();write(u,"files/package-takeover/generations/g-live/activation-receipt","generation=g-other\nreceipt="+"a".repeat(64));fails("activation_receipt_mismatch",()->coordinator(u).collectInactiveGenerations(List.of("inactive"),100));check(new File(u,"files/package-takeover/generations/g-old/a/b").isFile());
 }
 static void inactiveRestartNoTraversal()throws Exception {
  File s=setup(),root=new File(s,"files/package-takeover/generations");write(root,"g-retained/nested/keep","retained");write(s,"files/.podjs-takeover-backup/g-retained/payload","backup");
  int deleted=0;for(int pass=0;pass<20;pass++){PodInactiveGenerationCollector.Result batch=coordinator(s).collectInactiveGenerations(List.of("inactive"),1);check(batch.deletedEntries<=1);deleted+=batch.deletedEntries;if(batch.deletedEntries==0)break;}
  check(deleted==6);check(!new File(root,"g-old").exists());check(!new File(root,"g-unapproved").exists());
  // Fresh collector/coordinator objects model subsequent startups, not a warm
  // in-memory result. Assert actual list operations, not just deletion counts.
  for(int startup=0;startup<2;startup++){
   List<File> listed=new ArrayList<>();PodInactiveGenerationCollector collector=new PodInactiveGenerationCollector(s,new PodInactiveGenerationCollector.Hook(){public void deleted(){throw new AssertionError("clean startup deleted an entry");}public void listed(File directory){listed.add(directory);}});
   PodInactiveGenerationCollector.Result clean=collector.collect(List.of("inactive"),128);check(clean.deletedEntries==0&&clean.deletedBytes==0);check(listed.equals(List.of(root)));check(coordinator(s).collectInactiveGenerations(List.of("inactive"),128).deletedEntries==0);
  }
  // A permanent done flag would miss this new generation after cleanup.
  write(root,"g-created-later/body","new obsolete generation");check(coordinator(s).collectInactiveGenerations(List.of("inactive"),128).deletedEntries==2);check(!new File(root,"g-created-later").exists());check(new File(root,"g-retained/nested/keep").isFile());
 }
 public static void main(String[] args)throws Exception{validAndBudget();protectedAndRecovery();malformedAndSymlinks();recoveryLock();interruptedAndConcurrent();inactiveProtectsAndFilters();inactiveRecoveryAndPreflight();inactiveRestartNoTraversal();System.out.println("InactiveGenerationGcTest: "+checks+" assertions passed");}
}
