package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import dev.podjs.takeover.NativeBackup;
import dev.podjs.takeover.TakeoverDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodDeferredActiveSummaryTest {
    Path sandbox; File g; Context context;
    List<Runnable> scheduled; PodAfterFirstFrameTasks jobs; PodTakeoverBackup publisher;
    @Before public void setup()throws Exception {
        sandbox=Files.createTempDirectory("deferred-active-");
        g=sandbox.resolve("files/package-takeover/generations/g-unit").toFile();Files.createDirectories(g.toPath());
        Files.createDirectories(sandbox.resolve("files/native"));Files.write(sandbox.resolve("files/native/fixture"),new byte[]{1,2,3});
        new NativeBackup(sandbox.toFile()).prepare(g.getName(),40,"f".repeat(64));
        context=RuntimeEnvironment.getApplication();policy(0);
        scheduled=new ArrayList<>();jobs=new PodAfterFirstFrameTasks(scheduled::add);
        publisher=new PodTakeoverBackup(context,sandbox.toFile(),task->jobs.submitOnce(sandbox.toString(),task));
        ShadowLog.clear();
    }
    void policy(int threshold)throws Exception {
        PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);p.setLongVersionCode(1007);
        p.applicationInfo.metaData=new Bundle();p.applicationInfo.metaData.putInt("dev.podjs.takeover.purgeBackupAfter",threshold);
        Shadows.shadowOf(context.getPackageManager()).installPackage(p);
    }
    static void write(Path p,String value)throws IOException{Files.write(p,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String read(Path p)throws IOException{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
    void frame(){jobs.afterFirstFrame(sandbox.toString());}
    List<String> logs(){List<String> out=new ArrayList<>();for(ShadowLog.LogItem log:ShadowLog.getLogsForTag("PodTakeoverDigest"))out.add(log.msg);return out;}
    @After public void cleanup()throws Exception {
        try(java.util.stream.Stream<Path> paths=Files.walk(sandbox)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
    }
    @Test public void admissionDoesNotReadDigestAndFirstFrameEmitsExactSixOnce()throws Exception {
        List<String> expected=TakeoverDigest.activeLines(sandbox.toFile(),g.getName());assertEquals(6,expected.size());
        Path manifest=sandbox.resolve(NativeBackup.BACKUP+"/g-unit/manifest");byte[] original=Files.readAllBytes(manifest);
        write(manifest,"invalid until after admission");
        publisher.afterActive(g);publisher.afterActive(g);
        assertTrue(logs().isEmpty());assertTrue(scheduled.isEmpty());
        Files.write(manifest,original);frame();frame();publisher.afterActive(g);
        assertEquals(1,scheduled.size());scheduled.remove(0).run();frame();publisher.afterActive(g);
        assertTrue(scheduled.isEmpty());assertEquals(expected,logs());
    }
    @Test public void failureIsDeferredUnavailableWithoutPartialLinesOrWrites()throws Exception {
        Path seal=sandbox.resolve(NativeBackup.BACKUP+"/g-unit/seal");write(seal,"sha256=bad\n");
        byte[] before=Files.readAllBytes(seal);publisher.afterActive(g);assertTrue(logs().isEmpty());
        frame();scheduled.remove(0).run();
        assertEquals(List.of("{\"result\":\"unavailable\",\"error\":\"IOException\"}"),logs());
        assertArrayEquals(before,Files.readAllBytes(seal));assertFalse(new File(g,"active-summary.properties").exists());
    }
    @Test public void exitBeforeFrameLeavesNoSummaryStateAndNewProcessRetries()throws Exception {
        List<String> expected=TakeoverDigest.activeLines(sandbox.toFile(),g.getName());
        Set<String> before=new TreeSet<>();try(java.util.stream.Stream<Path> paths=Files.walk(sandbox)){paths.forEach(p->before.add(p.toString()));}
        publisher.afterActive(g);assertTrue(logs().isEmpty());assertTrue(scheduled.isEmpty());
        Set<String> after=new TreeSet<>();try(java.util.stream.Stream<Path> paths=Files.walk(sandbox)){paths.forEach(p->after.add(p.toString()));}assertEquals(before,after);
        PodAfterFirstFrameTasks nextProcess=new PodAfterFirstFrameTasks(scheduled::add);
        new PodTakeoverBackup(context,sandbox.toFile(),task->nextProcess.submitOnce(sandbox.toString(),task)).afterActive(g);
        nextProcess.afterFirstFrame(sandbox.toString());assertEquals(1,scheduled.size());scheduled.remove(0).run();assertEquals(expected,logs());
    }
    @Test public void purgeRemainsSynchronousAndEvidenceSurvivesDeletion()throws Exception {
        List<String> expected=TakeoverDigest.activeLines(sandbox.toFile(),g.getName());
        write(new File(g,"room.sqlite").toPath(),"duplicate source");
        Files.createDirectories(new File(g,"podjs/files").toPath());write(new File(g,"podjs/files/own").toPath(),"new user write");
        policy(1007);publisher.afterActive(g);
        assertFalse(sandbox.resolve(NativeBackup.BACKUP+"/g-unit").toFile().exists());assertFalse(new File(g,"room.sqlite").exists());
        assertEquals("new user write",read(new File(g,"podjs/files/own").toPath()));assertTrue(logs().isEmpty());assertTrue(scheduled.isEmpty());
        frame();scheduled.remove(0).run();assertEquals(expected,logs());
        publisher.afterActive(g);assertTrue(scheduled.isEmpty());assertEquals(expected,logs());
    }
    @Test public void alreadyPurgedBackupKeepsExistingOwnerPolicySkip()throws Exception {
        new NativeBackup(sandbox.toFile()).purge(g.getName());policy(1007);publisher.afterActive(g);frame();
        assertTrue(logs().isEmpty());assertTrue(scheduled.isEmpty());
    }
    @Test public void belowPurgeThresholdKeepsBackupAndImportSnapshot()throws Exception {
        write(new File(g,"room.sqlite").toPath(),"duplicate source");policy(1008);publisher.afterActive(g);
        assertTrue(new File(g,"room.sqlite").exists());assertTrue(sandbox.resolve(NativeBackup.BACKUP+"/g-unit/manifest").toFile().exists());
        frame();scheduled.remove(0).run();assertEquals(6,logs().size());
    }
    @Test public void purgeFailureStillPropagatesBeforeAdmissionReturns()throws Exception {
        policy(1007);write(sandbox.resolve(NativeBackup.BACKUP+"/g-unit/data/files/native/fixture"),"tampered");
        try{publisher.afterActive(g);fail("purge failure swallowed");}catch(IOException expected){}
        assertTrue(sandbox.resolve(NativeBackup.BACKUP+"/g-unit/manifest").toFile().exists());assertTrue(logs().isEmpty());
    }
    @Test public void frameBeforeSubmissionAndConcurrentRepeatsStillScheduleOnce()throws Exception {
        frame();ExecutorService threads=Executors.newFixedThreadPool(4);AtomicInteger ran=new AtomicInteger();
        try{List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<40;i++)tasks.add(threads.submit(()->jobs.submitOnce(sandbox.toString(),ran::incrementAndGet)));for(Future<?> task:tasks)task.get();}
        finally{threads.shutdownNow();}
        frame();assertEquals(1,scheduled.size());scheduled.remove(0).run();assertEquals(1,ran.get());
    }
    @Test public void capturedMetadataStillRejectsInvalidSealAndLinks()throws Exception {
        Path seal=sandbox.resolve(NativeBackup.BACKUP+"/g-unit/seal");write(seal,"sha256=bad\n");
        TakeoverDigest.ActiveMetadata metadata=TakeoverDigest.captureActive(sandbox.toFile(),g.getName());
        try{metadata.lines();fail("invalid seal accepted");}catch(IOException expected){}
        Path saved=seal.resolveSibling("original-seal");Files.move(seal,saved);Files.createSymbolicLink(seal,saved);
        try{TakeoverDigest.captureActive(sandbox.toFile(),g.getName());fail("link accepted");}catch(IOException expected){}
    }
    @Test public void profilePublisherRemainingWork()throws Exception {
        // Synthetic inventory only; no device/private files enter this microbenchmark.
        Path files=sandbox.resolve("files/profile");Files.createDirectories(files);
        for(int i=0;i<256;i++)Files.write(files.resolve(String.format(java.util.Locale.ROOT,"entry-%04d",i)),new byte[64]);
        File profile=new File(g.getParentFile(),"g-profile");Files.createDirectories(profile.toPath());
        new NativeBackup(sandbox.toFile()).prepare(profile.getName(),40,"f".repeat(64));
        long[] old=new long[60],now=new long[60],policy=new long[60];
        // Same real sealed fixture and logging, alternating paths to reduce warmup bias.
        for(int i=0;i<70;i++) {
            long began=System.nanoTime();PodTakeoverBackup.emit(TakeoverDigest.activeLines(sandbox.toFile(),profile.getName()));long previous=System.nanoTime()-began;
            began=System.nanoTime();publisher.afterActive(profile);long remaining=System.nanoTime()-began;
            began=System.nanoTime();
            context.getPackageManager().getApplicationInfo(context.getPackageName(),android.content.pm.PackageManager.GET_META_DATA).metaData.getInt("dev.podjs.takeover.purgeBackupAfter",0);
            context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();long ownerPolicy=System.nanoTime()-began;
            ShadowLog.clear();if(i>=10){old[i-10]=previous;now[i-10]=remaining;policy[i-10]=ownerPolicy;}
        }
        Arrays.sort(old);Arrays.sort(now);Arrays.sort(policy);
        System.out.println("T27_PROFILE publisher fixture=256-files samples=60 oldSummaryMedianNs="+old[30]+" remainingMedianNs="+now[30]+" remainingP95Ns="+now[56]+" ownerPolicyMedianNs="+policy[30]);
        assertTrue(scheduled.isEmpty());
    }
}
