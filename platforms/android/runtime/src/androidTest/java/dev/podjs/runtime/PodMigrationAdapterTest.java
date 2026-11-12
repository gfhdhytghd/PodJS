package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android SQLite/CursorWindow and cancellation; no application fixture. */
public final class PodMigrationAdapterTest {
    @Test public void boundedPagesAndLargeText()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f=File.createTempFile("migration-pages-",".sqlite",context.getCacheDir()).getCanonicalFile();
        try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(f)) {
            db.exec("CREATE TABLE sample(id INTEGER PRIMARY KEY,body TEXT NOT NULL)");
            String body="x".repeat(256*1024);
            for(int i=1;i<=16;i++)db.exec("INSERT INTO sample VALUES(?,?)",i,body);
            long after=0;int count=0;
            while(true) {
                java.util.List<Map<String,Object>> page=db.page("SELECT * FROM sample WHERE id>? ORDER BY id LIMIT 1024",after);
                if(page.isEmpty())break;
                assertTrue("a decoded page exceeded its memory budget",page.size()<=4);
                for(Map<String,Object> row:page){assertEquals(body,row.get("body"));after=((Number)row.get("id")).longValue();count++;}
            }
            assertEquals(16,count);
            String wide="n".repeat(3*1024*1024);db.exec("INSERT INTO sample VALUES(17,?)",wide);
            assertEquals(wide,db.page("SELECT body FROM sample WHERE id=17").get(0).get("body"));
        }finally{f.delete();}
    }
    @Test public void deadlineCancelsSqlAndClearsWorkerInterrupt()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f=File.createTempFile("migration-timeout-",".sqlite",context.getCacheDir()).getCanonicalFile();
        long began=android.os.SystemClock.uptimeMillis();
        try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(f);PodTakeoverCoordinator.Work work=new PodTakeoverCoordinator.Work(50,p->{})) {
            try {
                db.query("WITH RECURSIVE n(x) AS(VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n");
                fail("slow SQL was not cancelled");
            }catch(Exception error){assertEquals("migration_timeout",work.reason(error));}
        }finally{f.delete();}
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(android.os.SystemClock.uptimeMillis()-began<5000);
    }
    @Test public void oversizedRowRetainsDataAndReturnsStableCode()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f=File.createTempFile("migration-wide-",".sqlite",context.getCacheDir()).getCanonicalFile();
        try(PodRoomV18Converter.Db db=new PodRoomAndroidDb(f)) {
            db.exec("CREATE TABLE sample(body TEXT NOT NULL)");
            db.exec("INSERT INTO sample VALUES(?)","x".repeat(17*1024*1024));
            try {db.page("SELECT body FROM sample");fail("oversized row admitted");}
            catch(java.io.IOException error){assertEquals("migration_row_too_large",error.getMessage());}
            assertEquals(17L*1024*1024,PodRoomV18Converter.number(PodRoomV18Converter.scalar(db,"SELECT length(body) FROM sample")));
        }finally{f.delete();}
    }
}
