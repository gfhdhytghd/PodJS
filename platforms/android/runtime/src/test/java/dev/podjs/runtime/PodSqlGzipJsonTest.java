package dev.podjs.runtime;
import android.database.sqlite.SQLiteDatabase;import android.os.CancellationSignal;import java.io.File;import java.nio.file.Files;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.Config;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodSqlGzipJsonTest {
 SQLiteDatabase db;File root;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("gzip-sql").toFile();db=SQLiteDatabase.openOrCreateDatabase(new File(root,"db.sqlite"),null);db.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY,html TEXT,body TEXT,expected TEXT)");db.execSQL("INSERT INTO sample VALUES(1,NULL,'first / 中文',NULL),(2,'<p>second</p>','second',NULL)");}
 @After public void close(){db.close();}
 JSONObject args()throws Exception{return new JSONObject().put("format","android-json-gzip-v1").put("sql","SELECT id AS k,html,body,expected FROM sample WHERE id>? ORDER BY id").put("params",new JSONArray().put(0)).put("keyColumn","k").put("expectedHashColumn","expected").put("fields",new JSONArray().put(new JSONObject().put("name","contentHtml").put("column","html").put("omitNull",true)).put(new JSONObject().put("name","contentText").put("column","body"))).put("maxRows",32).put("chunkSize",7);}
 @Test public void actualSqlProjectionIsReadOnlyAndBodyEditsChangeOnlyTheirDigest()throws Exception {
  JSONArray a=PodSqlGzipJson.project(db,root,args(),new CancellationSignal());assertEquals(2,a.length());assertEquals("1",a.getJSONObject(0).getString("key"));assertTrue(a.getJSONObject(0).getJSONArray("chunkHashes").length()>1);
  db.execSQL("UPDATE sample SET body='new' WHERE id=1");JSONArray b=PodSqlGzipJson.project(db,root,args(),new CancellationSignal());assertNotEquals(a.getJSONObject(0).getString("sha256"),b.getJSONObject(0).getString("sha256"));assertEquals(a.getJSONObject(1).getString("sha256"),b.getJSONObject(1).getString("sha256"));
  try(android.database.Cursor c=db.rawQuery("SELECT count(*) FROM sample",null)){assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0));}
 }
 @Test public void rowBoundsAndCancellationNeverReturnPartialProofs()throws Exception {
  try{PodSqlGzipJson.project(db,root,args().put("maxRows",1),new CancellationSignal());fail();}catch(IllegalArgumentException expected){}
  CancellationSignal c=new CancellationSignal();c.cancel();try{PodSqlGzipJson.project(db,root,args(),c);fail();}catch(android.os.OperationCanceledException expected){}
 }
 @Test public void numericQueryParametersStayNumeric()throws Exception{
  JSONObject a=args().put("sql","SELECT MAX(id) AS k,NULL AS html,'x' AS body,NULL AS expected FROM sample GROUP BY (id>0) HAVING MAX(id)>?").put("params",new JSONArray().put(1));assertEquals(1,PodSqlGzipJson.project(db,root,a,new CancellationSignal()).length());
  a.put("params",new JSONArray().put(9));assertEquals(0,PodSqlGzipJson.project(db,root,a,new CancellationSignal()).length());
 }
}
