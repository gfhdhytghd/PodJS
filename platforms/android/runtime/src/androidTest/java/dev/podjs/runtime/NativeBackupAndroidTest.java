package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.takeover.NativeBackup;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Synthetic cache sandbox only; compiled on host, device execution is PENDING. */
public final class NativeBackupAndroidTest {
    @Test public void interruptedRestoreBeforeOpeningNativeDatabase() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=Files.createTempDirectory(context.getCacheDir().toPath(),"backup-android-fixture-").toFile();
        try {
            File db=new File(root,"databases/watchrss.db");db.getParentFile().mkdirs();
            try(SQLiteDatabase source=SQLiteDatabase.openOrCreateDatabase(db,null)) {
                source.execSQL("CREATE TABLE fixture(id INTEGER PRIMARY KEY, value TEXT)");
                source.execSQL("INSERT INTO fixture VALUES(42,'synthetic')");
            }
            byte[] original=Files.readAllBytes(db.toPath());
            File prefs=new File(root,"files/datastore/settings.preferences_pb");prefs.getParentFile().mkdirs();Files.write(prefs.toPath(),new byte[]{10,0});
            File guest=new File(root,"files/package-takeover/generations/g-fixture/podjs/files/new-note");guest.getParentFile().mkdirs();Files.write(guest.toPath(),new byte[]{1,2,3});
            String cert=new String(new char[64]).replace('\0','a');
            new NativeBackup(root).prepare("g-fixture",40,cert);assertFalse(db.exists());
            final boolean[] interrupted={false};
            try {
                new NativeBackup(root,b->{if(b.startsWith("archive-")&&!interrupted[0]){interrupted[0]=true;throw new IOException("interrupted");}},File::getUsableSpace).restore("g-fixture",cert);
                fail("fault did not interrupt");
            }catch(IOException expected){assertTrue(interrupted[0]);}
            new NativeBackup(root).restore("g-fixture",cert);assertArrayEquals(original,Files.readAllBytes(db.toPath()));
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(root,NativeBackup.RECOVERED+"/g-fixture/files/package-takeover/generations/g-fixture/podjs/files/new-note").toPath()));
            try(SQLiteDatabase restored=SQLiteDatabase.openDatabase(db.toString(),null,SQLiteDatabase.OPEN_READONLY);
                android.database.Cursor rows=restored.rawQuery("SELECT id FROM fixture",null)) {assertTrue(rows.moveToFirst());assertEquals(42,rows.getInt(0));}
        }finally{delete(root);}
    }
    private static void delete(File f)throws IOException {
        if(f.isDirectory())for(File child:f.listFiles())delete(child);Files.deleteIfExists(f.toPath());
    }
}
