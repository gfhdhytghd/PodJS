package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import android.os.CancellationSignal;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodActiveValidationTest {
 File root,g,dbFile;
 @Before public void setup()throws Exception {
  root=Files.createTempDirectory("active-readonly").toFile();g=new File(root,"g-test");g.mkdirs();dbFile=new File(g,"podjs.sqlite");
  try(PodRoomAndroidDb db=new PodRoomAndroidDb(dbFile)) {
   JSONArray sql=new JSONArray(PodRoomV18Converter.resource("watchrss-schema.json"));
   for(int i=0;i<sql.length();i++)db.exec(sql.getString(i));
   db.exec("INSERT INTO sync_local_identity(id,device_id,device_order) VALUES(1,'fixture','fixture')");db.exec("PRAGMA user_version=2");
   db.exec("CREATE TABLE concurrent_fixture(id INTEGER PRIMARY KEY,value INTEGER)");db.exec("INSERT INTO concurrent_fixture VALUES(1,0)");
  }
 }
 @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
 @Test public void fastReadsVersionAndIdentityWithoutScanning()throws Exception {
  List<String> queries=new ArrayList<>();
  PodRoomV18Converter.Factory ro=f->{PodRoomAndroidDb db=new PodRoomAndroidDb(f,true,null);return new PodRoomV18Converter.Db(){
   public void exec(String sql,Object...args){throw new AssertionError("fast wrote");}
   public List<Map<String,Object>> query(String sql,Object...args){queries.add(sql);if(sql.contains("quick_check")||sql.contains("foreign_key_check"))throw new AssertionError("deep scan at admission");return db.query(sql,args);}
   public void close(){db.close();}
  };};
  new PodRoomV18Converter(null,ro,"fixture").activeFast(g);assertEquals(6,queries.size());assertEquals("PRAGMA user_version",queries.get(0));
  try(PodRoomAndroidDb db=new PodRoomAndroidDb(dbFile)){db.exec("PRAGMA user_version=3");}
  new PodRoomV18Converter(null,ro,"fixture").activeFast(g);
  try(PodRoomAndroidDb db=new PodRoomAndroidDb(dbFile)){db.exec("PRAGMA user_version=4");}
  try{new PodRoomV18Converter(null,ro,"fixture").activeFast(g);fail();}catch(IOException expected){assertEquals("active_schema_version_invalid",expected.getMessage());}
 }
 @Test public void readonlyConnectionRejectsWritesAndSeesAtomicCommits()throws Exception {
  try(PodRoomAndroidDb reader=new PodRoomAndroidDb(dbFile,true,null)) {
   try{reader.exec("UPDATE concurrent_fixture SET value=999");fail("readonly accepted write");}catch(android.database.sqlite.SQLiteException expected){}
   CountDownLatch started=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
   Thread writer=new Thread(()->{try(PodRoomAndroidDb db=new PodRoomAndroidDb(dbFile)){started.countDown();for(int i=1;i<=50;i++){db.exec("BEGIN IMMEDIATE");db.exec("UPDATE concurrent_fixture SET value=?",i);db.exec("COMMIT");}}catch(Throwable t){failure.set(t);}});
   writer.start();assertTrue(started.await(5,TimeUnit.SECONDS));
   for(int i=0;i<50;i++){assertEquals("ok",PodRoomV18Converter.scalar(reader,"PRAGMA quick_check"));assertTrue(reader.query("PRAGMA foreign_key_check").isEmpty());}
   writer.join(5000);assertFalse(writer.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
   assertEquals(50,PodRoomV18Converter.number(PodRoomV18Converter.scalar(reader,"SELECT value FROM concurrent_fixture")));
  }
 }
 @Test public void foregroundCancelsReadonlyQueryAndLeavesWriterDataIntact()throws Exception {
  PodActiveValidation.Workload workload=new PodActiveValidation.Workload();CancellationSignal signal=new CancellationSignal();
  try(PodActiveValidation.Workload.Reader reader=workload.read(signal::cancel);
      PodRoomAndroidDb db=new PodRoomAndroidDb(dbFile,true,signal)) {
   try(AutoCloseable writer=workload.foreground()) {
    assertTrue(signal.isCanceled());
    try{db.query("PRAGMA quick_check");fail("cancel ignored");}catch(android.os.OperationCanceledException expected){}
    try(PodRoomAndroidDb write=new PodRoomAndroidDb(dbFile)){write.exec("UPDATE concurrent_fixture SET value=51");}
   }
  }
  try(PodRoomAndroidDb verify=new PodRoomAndroidDb(dbFile,true,null)){assertEquals(51,PodRoomV18Converter.number(PodRoomV18Converter.scalar(verify,"SELECT value FROM concurrent_fixture")));}
 }
}
