package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android filesystem and SQLite; all data belongs to a fresh test cache directory. */
public final class PodMigrationStorageSafetyTest {
    static File root()throws Exception {return Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),"migration-safety-").toFile().getCanonicalFile();}
    static String shell(String command)throws Exception {
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command));
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[1024];for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);return out.toString("UTF-8").trim();
        }
    }
    static void hardlink(File link,File existing)throws Exception {
        try{Files.createLink(link.toPath(),existing.toPath());}
        catch(AccessDeniedException appPolicy) {
            // API 35 forbids app-domain link(2). Inject the abnormal inode only
            // on a verified local emulator, without relaxing SELinux policy.
            if(!"1".equals(shell("getprop ro.kernel.qemu")))throw appPolicy;
            for(File file:new File[]{link,existing})if(!file.getAbsolutePath().matches("/data/data/[A-Za-z0-9_.]+/cache/migration-safety-[0-9]+/[A-Za-z0-9_./-]+"))throw appPolicy;
            shell("su 0 ln "+existing.getAbsolutePath()+" "+link.getAbsolutePath());
            assertTrue("emulator hardlink injection failed",Files.isSameFile(link.toPath(),existing.toPath()));
        }
    }
    static void remove(File root)throws Exception {try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    static PodRoomV18Converter room(File root,PodRoomV18Converter.Factory factory){return new PodRoomV18Converter(new File(root,"source.sqlite"),factory,"test-identity");}
    static void seed(File root)throws Exception {
        try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(new File(root,"source.sqlite"))) {
            JSONArray schema=new JSONObject(PodRoomV18Converter.resource("room-v18.json")).getJSONArray("sql");for(int i=0;i<schema.length();i++)db.exec(schema.getString(i));db.exec("PRAGMA user_version=18");
        }
        File g=new File(root,"g");g.mkdirs();room(root,PodRoomAndroidDb::new).snapshot(g);room(root,PodRoomAndroidDb::new).copy(g);
    }
    @Test public void everyRecoveryDirectoryRejectsBeforeOpeningSQLite()throws Exception {
        for(String name:new String[]{"capture-work","capture-00000000-0000-0000-0000-000000000001","verification-work","verify-00000000-0000-0000-0000-000000000001"})
            for(String kind:new String[]{"symlink","hardlink","unknown","directory-link"}) {
                File root=root();try {
                    seed(root);File source=new File(root,"source.sqlite"),g=new File(root,"g"),snapshot=new File(g,"room.sqlite"),work=new File(g,name);work.mkdirs();
                    String sourceHash=PodRoomV18Converter.hash(source),snapshotHash=PodRoomV18Converter.hash(snapshot);File bad;
                    if(kind.equals("directory-link")){work.delete();File other=new File(root,"other");other.mkdirs();Files.createSymbolicLink(work.toPath(),other.toPath());bad=work;}
                    else {bad=new File(work,kind.equals("unknown")?"unknown-user-file":name.startsWith("capture")?"room.sqlite-journal":"room-progress.sqlite-wal");
                        if(kind.equals("unknown"))Files.write(bad.toPath(),new byte[]{1});
                        else if(kind.equals("symlink"))Files.createSymbolicLink(bad.toPath(),source.toPath());
                        else hardlink(bad,snapshot);
                    }
                    int[] opened={0};String failure="";
                    try{room(root,file->{opened[0]++;return new PodRoomAndroidDb(file);}).verify(g);fail("unsafe recovery admitted");}catch(IOException e){failure=e.getMessage();}
                    assertTrue(failure,failure.equals("migration_storage_invalid")||failure.equals("verification_scratch_invalid"));assertEquals("opened before full preflight",0,opened[0]);
                    assertTrue(Files.exists(bad.toPath(),LinkOption.NOFOLLOW_LINKS));assertEquals(sourceHash,PodRoomV18Converter.hash(source));assertEquals(snapshotHash,PodRoomV18Converter.hash(snapshot));
                }finally{remove(root);}
            }
    }
    @Test public void androidDbRejectsEveryLinkedSidecarBeforeNativeOpen()throws Exception {
        for(String suffix:new String[]{"","-journal","-wal","-shm"})for(boolean symbolic:new boolean[]{false,true}) {
            File root=root();try {
                File original=new File(root,"protected.sqlite");try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(original)){db.exec("CREATE TABLE original(value TEXT)");db.exec("INSERT INTO original VALUES('retained')");}
                String before=PodRoomV18Converter.hash(original);File target=new File(root,"candidate.sqlite"),bad=new File(target+suffix);
                if(symbolic)Files.createSymbolicLink(bad.toPath(),original.toPath());else hardlink(bad,original);
                try{new PodRoomAndroidDb(target).close();fail("linked database opened");}catch(IllegalStateException blocked){assertEquals("migration_storage_invalid",blocked.getMessage());}
                assertEquals(before,PodRoomV18Converter.hash(original));assertTrue(Files.exists(bad.toPath(),LinkOption.NOFOLLOW_LINKS));
            }finally{remove(root);}
        }
    }
    @Test public void targetInitializationClosesForInterruptDeadlineIoAndMemory()throws Exception {
        for(String kind:new String[]{"interrupt","timeout","io","memory"})for(boolean closeFails:new boolean[]{false,true}) {
            File root=root();try {
                seed(root);File g=new File(root,"g");int[] opened={0},closed={0};Throwable[] primary={null};PodTakeoverCoordinator.Work[] budget={null};IOException closing=new IOException("synthetic_close");List<PodRoomV18Converter.Db> acquired=new ArrayList<>();
                PodRoomV18Converter converter=room(root,file->{
                    PodRoomV18Converter.Db actual=new PodRoomAndroidDb(file);if(!file.equals(new File(g,"podjs.sqlite")))return actual;opened[0]++;acquired.add(actual);
                    if(kind.equals("interrupt"))Thread.currentThread().interrupt();
                    if(kind.equals("timeout"))while(!budget[0].expired)try{Thread.sleep(2);}catch(InterruptedException e){}
                    return new PodRoomV18Converter.Db(){
                        public List<Map<String,Object>> query(String sql,Object...args)throws Exception {
                            try {if(kind.equals("memory"))throw new OutOfMemoryError("synthetic");if(kind.equals("io"))throw new IOException("synthetic_io");return actual.query(sql,args);}
                            catch(Exception|Error error){primary[0]=error;throw error;}
                        }
                        public void exec(String sql,Object...args)throws Exception{actual.exec(sql,args);}
                        public void close()throws Exception{closed[0]++;actual.close();if(closeFails)throw closing;}
                    };
                });
                Throwable caught=null;
                try(PodTakeoverCoordinator.Work work=new PodTakeoverCoordinator.Work(kind.equals("timeout")?1000:10000,p->{})) {
                    budget[0]=work;
                    try{converter.resume(g,PodTakeoverCoordinator.Phase.COPYING);fail("initialization failure missing");}catch(Exception|Error e){caught=e;}
                }finally{Thread.interrupted();if(closed[0]==0)for(PodRoomV18Converter.Db actual:acquired)actual.close();}
                assertEquals("real target was not opened",1,opened[0]);assertEquals("real SQLite target leaked",1,closed[0]);assertSame("primary failure replaced",primary[0],caught);
                if(kind.equals("interrupt"))assertEquals("migration_interrupted",caught.getMessage());if(kind.equals("timeout"))assertEquals("migration_timeout",caught.getMessage());if(kind.equals("memory"))assertTrue(caught instanceof OutOfMemoryError);
                if(closeFails)assertTrue(Arrays.asList(caught.getSuppressed()).contains(closing));
            }finally{remove(root);}
        }
    }
    @Test public void backupControlAliasesAreRejectedBeforePublication()throws Exception {
        for(String name:new String[]{"manifest","manifest.tmp","seal","seal.tmp","ready","ready.tmp","unknown-user-file","data"})for(boolean symbolic:new boolean[]{true,false}) {
            File root=root();try {
                File source=new File(root,"source.sqlite");try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(source)){db.exec("CREATE TABLE original(value TEXT)");db.exec("INSERT INTO original VALUES('retained')");}
                File g=new File(root,"files/package-takeover/generations/g-safety"),control=new File(root,"files/.podjs-takeover-backup/g-safety/"+name);g.mkdirs();control.getParentFile().mkdirs();String before=PodRoomV18Converter.hash(source);
                if(symbolic)Files.createSymbolicLink(control.toPath(),source.toPath());else hardlink(control,source);
                try{new PodTakeoverBackup(InstrumentationRegistry.getInstrumentation().getTargetContext(),root).beforePublish(g);fail("unsafe backup control published");}catch(IOException e){assertEquals("migration_storage_invalid",e.getMessage());}
                assertEquals(before,PodRoomV18Converter.hash(source));assertTrue(Files.exists(control.toPath(),LinkOption.NOFOLLOW_LINKS));assertFalse(new File(root,"files/package-takeover/active").exists());
            }finally{remove(root);}
        }
    }

}
