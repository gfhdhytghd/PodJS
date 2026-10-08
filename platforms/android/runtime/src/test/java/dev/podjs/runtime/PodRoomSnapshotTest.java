package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodRoomSnapshotTest {
    @Test public void androidSnapshotOfClosedNativeWalDatabase() throws Exception {snapshot(false);}
    @Test public void androidSnapshotOfHistoricallyMigratedNativeDatabase() throws Exception {snapshot(true);}
    private void snapshot(boolean migrated) throws Exception {
        File root=Files.createTempDirectory("room-android-").toFile();
        File source=new File(root,"watchrss.db"),g=new File(root,"g-test");g.mkdirs();
        try {
            try(PodRoomAndroidDb db=new PodRoomAndroidDb(source)) {
                db.query("PRAGMA journal_mode=WAL");
                JSONArray sql=new JSONObject(PodRoomV18Converter.resource("room-v18.json")).getJSONArray("sql");
                for(int i=0;i<sql.length();i++) {
                    String statement=sql.getString(i);
                    if(migrated&&statement.startsWith("CREATE TABLE IF NOT EXISTS `rss_items`")) {
                        // Native 6->7 appends this column; current v18 CREATE places it
                        // immediately after content. Identity and named schema are equal.
                        statement=statement.replace("`originalContent` TEXT, ","");
                        db.exec(statement);db.exec("ALTER TABLE rss_items ADD COLUMN originalContent TEXT");
                    }else db.exec(statement);
                }
                db.exec("PRAGMA user_version=18");
            }
            String before=PodRoomV18Converter.hash(source);
            PodRoomV18Converter converter=new PodRoomV18Converter(source,PodRoomAndroidDb::new,"synthetic-device");
            converter.snapshot(g);
            converter.resume(g,PodTakeoverCoordinator.Phase.SNAPSHOTTED);
            // A permissive order check must still fail closed for semantic schema changes.
            for(String column:new String[]{"type","notnull","pk","name","missing","extra"}) {
                try(PodRoomAndroidDb db=new PodRoomAndroidDb(new File(g,"room.sqlite"))) {
                    PodRoomV18Converter.Db altered=new PodRoomV18Converter.Db() {
                        public void exec(String sql,Object... args){throw new AssertionError("read-only schema validation");}
                        public void close(){}
                        public List<Map<String,Object>> query(String sql,Object... args) {
                            List<Map<String,Object>> rows=db.query(sql,args);
                            if(sql.equals("PRAGMA table_info(\"rss_items\")")) {
                                if(column.equals("missing"))rows.remove(rows.size()-1);
                                else if(column.equals("extra"))rows.add(new LinkedHashMap<>(rows.get(0)));
                                else {
                                    Map<String,Object> changed=rows.get(0);
                                    changed.put(column,column.equals("type")?"TEXT":column.equals("name")?"renamed":0L);
                                }
                            }
                            return rows;
                        }
                    };
                    try{PodRoomV18Converter.validateRoom(altered);fail("schema defect "+column);}
                    catch(IOException expected){assertEquals("room_columns_invalid",expected.getMessage());}
                }
            }
            assertEquals(before,PodRoomV18Converter.hash(source));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
                for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);
            }
        }
    }
}
