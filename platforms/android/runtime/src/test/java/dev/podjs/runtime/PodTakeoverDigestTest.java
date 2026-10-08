package dev.podjs.runtime;

import dev.podjs.takeover.NativeBackup;
import dev.podjs.takeover.TakeoverDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class PodTakeoverDigestTest {
    private static void remove(Path sandbox)throws Exception {
        try(java.util.stream.Stream<Path> paths=Files.walk(sandbox)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
    }
    private static File generation(Path sandbox)throws Exception {
        File g=sandbox.resolve("files/package-takeover/generations/g-unit").toFile();Files.createDirectories(g.toPath());
        PodStoreConverter.json(new File(g,"files-snapshot.json"),new JSONObject().put("entries",new JSONObject()));
        PodStoreConverter.json(new File(g,"stores-snapshot.json"),new JSONObject().put("entries",new JSONObject()));
        Properties db=new Properties();db.setProperty("sourceDigest","f".repeat(64)+":absent");
        PodTakeoverCoordinator.atomic(new File(g,"snapshot.properties"),db);return g;
    }
    @Test public void releaseLogIsStableEmptyAndPrivateAndBackedBySealedPlan()throws Exception {
        Path sandbox=Files.createTempDirectory("digest-test-");
        try {
            Files.createDirectories(sandbox.resolve("files/nested"));Files.createDirectories(sandbox.resolve("cache"));
            Files.write(sandbox.resolve("files/nested/private-cookie-account.txt"),"synthetic-cookie-value".getBytes());
            Files.write(sandbox.resolve("cache/image.bin"),new byte[]{1,2,3});
            File g=generation(sandbox);NativeBackup backup=new NativeBackup(sandbox.toFile());
            Properties p=PodTakeoverBackup.snapshot(sandbox.toFile(),g);
            List<String> snapshot=TakeoverDigest.digestManifest(p,"SNAPSHOTTED",g.getName());
            assertEquals(snapshot,TakeoverDigest.digestManifest(PodTakeoverBackup.snapshot(sandbox.toFile(),g),"SNAPSHOTTED",g.getName()));
            assertTrue(Files.exists(sandbox.resolve("files/nested/private-cookie-account.txt")));
            assertFalse(Files.exists(sandbox.resolve(NativeBackup.BACKUP+"/g-unit/manifest")));
            try{TakeoverDigest.activeLines(sandbox.toFile(),g.getName());fail("unready backup");}catch(IOException expected){}
            backup.prepare(g.getName(),40,"f".repeat(64));PodTakeoverBackup.verifySnapshot(sandbox.toFile(),g);
            assertFalse(Files.exists(sandbox.resolve("files/nested")));
            assertTrue(Files.exists(sandbox.resolve("cache/image.bin")));
            assertTrue(new File(g,"takeover-digest-snapshot.properties").exists());
            List<String> active=TakeoverDigest.activeLines(sandbox.toFile(),g.getName());
            for(int i=0;i<active.size();i++)assertEquals(snapshot.get(i).replace("SNAPSHOTTED","ACTIVE"),active.get(i));
            // ACTIVE projections read sealed metadata even if payload access is gone.
            Files.delete(sandbox.resolve(NativeBackup.BACKUP+"/g-unit/data/files/nested/private-cookie-account.txt"));
            assertEquals(active,TakeoverDigest.activeLines(sandbox.toFile(),g.getName()));
            ShadowLog.clear();PodTakeoverBackup.emit(snapshot);PodTakeoverBackup.emit(active);
            assertEquals(10,ShadowLog.getLogsForTag("PodTakeoverDigest").size());
            for(ShadowLog.LogItem log:ShadowLog.getLogsForTag("PodTakeoverDigest")) {
                JSONObject json=new JSONObject(log.msg);assertEquals(6,json.length());
                for(String value:new String[]{"private","cookie","account","synthetic",".txt",".bin","cache"})assertFalse(log.msg.contains(value));
                if(json.getString("root").equals("no_backup")) {
                    assertEquals(0,json.getLong("files"));assertEquals(0,json.getLong("bytes"));
                    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",json.getString("sha256"));
                }
            }
        }finally {remove(sandbox);}
    }
    @Test public void converterHashReusedAndWrongHashBlocksPublication()throws Exception {
        Path sandbox=Files.createTempDirectory("digest-reuse-");
        try {
            File g=generation(sandbox);Files.write(sandbox.resolve("files/private-large.bin"),new byte[1024*1024]);
            PodStoreConverter.json(new File(g,"files-snapshot.json"),new JSONObject().put("entries",new JSONObject()
                .put("files/private-large.bin",new JSONObject().put("sha256","a".repeat(64)))));
            Properties p=PodTakeoverBackup.snapshot(sandbox.toFile(),g);
            // Wrong cached hash proves the snapshot did not rehash converter payloads.
            assertEquals("a".repeat(64),p.getProperty("entry.0.sha256"));
            new NativeBackup(sandbox.toFile()).prepare(g.getName(),40,"f".repeat(64));
            try{PodTakeoverBackup.verifySnapshot(sandbox.toFile(),g);fail("wrong cached hash");}catch(IOException expected){assertEquals("digest_snapshot_changed",expected.getMessage());}
        }finally {remove(sandbox);}
    }
    @Test public void snapshotReplayIsImmutableAndSourceChangeBlocksPublication()throws Exception {
        Path sandbox=Files.createTempDirectory("digest-seal-");
        try {
            File g=generation(sandbox);Files.write(sandbox.resolve("files/private.txt"),new byte[]{1});
            Properties before=PodTakeoverBackup.snapshot(sandbox.toFile(),g);
            Files.write(sandbox.resolve("files/private.txt"),new byte[]{2});
            assertEquals(before,PodTakeoverBackup.snapshot(sandbox.toFile(),g));
            new NativeBackup(sandbox.toFile()).prepare(g.getName(),40,"f".repeat(64));
            try{PodTakeoverBackup.verifySnapshot(sandbox.toFile(),g);fail("changed source");}catch(IOException expected){}
        }finally {remove(sandbox);}
    }
    @Test public void activeRejectsTamperedManifestSealReadyAndLinks()throws Exception {
        Path sandbox=Files.createTempDirectory("digest-invalid-");
        try {
            Files.createDirectories(sandbox.resolve("no_backup"));Files.write(sandbox.resolve("no_backup/private.bin"),new byte[]{4});
            new NativeBackup(sandbox.toFile()).prepare("g-unit",40,"f".repeat(64));
            Path base=sandbox.resolve(NativeBackup.BACKUP+"/g-unit");
            assertEquals(5,TakeoverDigest.activeLines(sandbox.toFile(),"g-unit").size());
            for(String name:new String[]{"manifest","seal","ready"}) {
                Path file=base.resolve(name);byte[] good=Files.readAllBytes(file);
                Files.write(file,"sha256=bad\n".getBytes());
                try{TakeoverDigest.activeLines(sandbox.toFile(),"g-unit");fail("bad "+name);}catch(IOException expected){}
                Files.write(file,good);
            }
            Path ready=base.resolve("ready"),target=base.resolve("ready-original");Files.move(ready,target);Files.createSymbolicLink(ready,target);
            try{TakeoverDigest.activeLines(sandbox.toFile(),"g-unit");fail("symlink");}catch(IOException expected){}
        }finally {remove(sandbox);}
    }
}
