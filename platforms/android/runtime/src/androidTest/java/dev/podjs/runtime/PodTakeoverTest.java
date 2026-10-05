package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.Properties;
import static org.junit.Assert.*;

/** Synthetic app-private fixtures only. Compilation is part of P2a; device execution is deferred. */
public final class PodTakeoverTest {
    private Context fixture() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=Files.createTempDirectory(base.getCacheDir().toPath(),"takeover-fixture").toFile();
        return new ContextWrapper(base) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(root.getName()+"-"+name,mode);}
            @Override public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}
            @Override public File getDatabasePath(String name){File dir=new File(root,"databases");dir.mkdirs();return new File(dir,name);}
            @Override public File getNoBackupFilesDir(){File f=new File(root,"no_backup");f.mkdirs();return f;}
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(super.getApplicationInfo());info.dataDir=root.toString();return info;}
        };
    }
    private void header(Context c,int version)throws Exception {
        try(RandomAccessFile f=new RandomAccessFile(c.getDatabasePath("watchrss.db"),"rw")) {
            f.setLength(4096);f.write("SQLite format 3\000".getBytes("US-ASCII"));f.seek(60);f.writeInt(version);
        }
    }
    @Test public void noSourceKeepsLegacyPathsAndCreatesNoJournal()throws Exception {
        Context c=fixture();assertTrue(PodTakeover.ready(c));
        assertEquals(new File(c.getFilesDir(),"podjs"),PodTakeover.dataRoot(c));
        assertEquals(c.getDatabasePath("podjs.sqlite"),PodTakeover.database(c));
        assertFalse(new File(c.getFilesDir(),"package-takeover").exists());
    }
    @Test public void sourceDetectionNeverWritesOrStartsStorage()throws Exception {
        Context c=fixture();header(c,18);byte[] before=Files.readAllBytes(c.getDatabasePath("watchrss.db").toPath());
        assertEquals(18,PodTakeover.detect(c).schema);assertFalse(PodTakeover.ready(c));
        assertEquals("PAUSED",PodTakeover.status(c).getString("phase"));
        try {new PodDeviceServices(c);fail("storage admitted before conversion");}catch(IllegalStateException expected){}
        try {new PodBackgroundScheduler(c);fail("background admitted before conversion");}catch(IllegalStateException expected){}
        assertFalse(c.getDatabasePath("podjs.sqlite").exists());assertFalse(new File(c.getFilesDir(),"podjs").exists());
        assertArrayEquals(before,Files.readAllBytes(c.getDatabasePath("watchrss.db").toPath()));
    }
    @Test public void unsupportedCorruptAndStoreOnlyFailClosed()throws Exception {
        // Detection no longer trusts main-file version bytes; WAL-aware Room validation is in the converter.
        Context c=fixture();header(c,19);assertNull(PodTakeover.detect(c).block);assertFalse(PodTakeover.ready(c));
        c=fixture();Files.write(c.getDatabasePath("watchrss.db").toPath(),new byte[]{1,2});assertNull(PodTakeover.detect(c).block);assertFalse(PodTakeover.ready(c));
        c=fixture();File store=new File(c.getFilesDir(),"datastore/settings.preferences_pb.preferences_pb");store.getParentFile().mkdirs();Files.write(store.toPath(),new byte[]{1});
        assertTrue(PodTakeover.detect(c).present);assertEquals("source_database_missing",PodTakeover.detect(c).block);assertFalse(PodTakeover.ready(c));
    }
    @Test public void allFourRootsResolveSameCommittedGeneration()throws Exception {
        Context c=fixture();File root=new File(c.getFilesDir(),"package-takeover");File g=new File(root,"generations/g-fixture");new File(g,"podjs/files").mkdirs();
        Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("phase","ACTIVE");p.setProperty("generation","g-fixture");p.setProperty("receipt",new String(new char[64]).replace('\0','a'));
        PodTakeoverCoordinator.atomic(new File(root,"active"),p);PodTakeoverCoordinator.atomic(new File(root,"journal"),p);
        assertEquals(new File(g,"podjs.sqlite"),PodTakeover.database(c));
        assertEquals(new File(g,"podjs"),PodTakeover.dataRoot(c)); // Rust KV root
        assertEquals(new File(g,"podjs/files"),PodTakeover.filesRoot(c));
        try(PodDeviceServices device=new PodDeviceServices(c)) {
            java.lang.reflect.Field secure=PodDeviceServices.class.getDeclaredField("secureFile");secure.setAccessible(true);
            assertEquals(new File(g,"podjs/secure.store"),secure.get(device));
        }
        p.setProperty("generation","g-other");PodTakeoverCoordinator.atomic(new File(root,"active"),p);
        // An admitted process pins its generation; subsequent I/O faults or pointer
        // changes are examined on next process admission, never on storage hot paths.
        assertTrue(PodTakeover.ready(c));assertEquals(new File(g,"podjs"),PodTakeover.dataRoot(c));
        try {new PodTakeoverCoordinator(root,()->PodTakeover.detect(c),PodTakeoverCoordinator.EMPTY).run();fail("new admission accepted corrupt pointer");}
        catch(IOException expected){}
    }
}
