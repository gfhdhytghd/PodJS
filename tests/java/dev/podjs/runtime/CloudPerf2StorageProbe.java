package dev.podjs.runtime;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic files, actual Android SQLite and production file/crypto services. */
public final class CloudPerf2StorageProbe {
 static SQLiteDatabase db;
 static Object sql(String method,JSONObject args)throws Exception{
  if(method.equals("sql.query")){
   JSONArray params=args.optJSONArray("params");String[] values=new String[params==null?0:params.length()];for(int i=0;i<values.length;i++)values[i]=params.isNull(i)?null:params.get(i).toString();
   JSONArray result=new JSONArray();try(Cursor c=db.rawQuery(args.getString("sql"),values)){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getType(i)==Cursor.FIELD_TYPE_FLOAT?c.getDouble(i):c.getString(i));result.put(row);}}return result;
  }
  if(method.equals("sql.transaction")){JSONArray changes=new JSONArray(),ss=args.getJSONArray("statements");db.beginTransaction();try{for(int i=0;i<ss.length();i++){execute(ss.getJSONObject(i));try(Cursor c=db.rawQuery("SELECT changes()",null)){c.moveToFirst();changes.put(c.getLong(0));}}db.setTransactionSuccessful();}finally{db.endTransaction();}return args.optBoolean("reportChanges")?changes:JSONObject.NULL;}
  if(method.equals("sql.notify"))return JSONObject.NULL;
  execute(args);return JSONObject.NULL;
 }
 static void execute(JSONObject args)throws Exception{try(SQLiteStatement s=db.compileStatement(args.getString("sql"))){JSONArray p=args.optJSONArray("params");for(int i=0;p!=null&&i<p.length();i++){Object v=p.get(i);if(v==JSONObject.NULL)s.bindNull(i+1);else if(v instanceof Number){double n=((Number)v).doubleValue();if(n==Math.rint(n))s.bindLong(i+1,((Number)v).longValue());else s.bindDouble(i+1,n);}else s.bindString(i+1,v.toString());}s.execute();}}
 public static void main(String[] args)throws Exception{
  String source=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8),directory=args[1];JSONObject payload=new JSONObject(new String(Files.readAllBytes(Paths.get(directory,"fixtures.json")),StandardCharsets.UTF_8));
  db=SQLiteDatabase.openOrCreateDatabase(directory+"/probe.sqlite",null);db.execSQL("PRAGMA foreign_keys=ON");
  ExecutorService executor=Executors.newSingleThreadExecutor();Map<String,Integer> calls=new TreeMap<>();Map<String,Long> hostNanos=new TreeMap<>();
  try(PodDeviceServices device=new PodDeviceServices(new java.io.File(directory,"files"),()->new SecretKeySpec(new byte[32],"AES"));PodBackgroundRun run=new PodBackgroundRun("cloudperf2-storage","storage",source,300000,256*1024*1024,payload,new String[]{"perf","debug.cloudHost"})){
   Future<JSONObject> result=executor.submit(run::run);long at=0,cpu=0;
   while(!result.isDone()){JSONObject request=run.waitRequest(50);if(request==null)continue;JSONObject a=request.getJSONObject("args");Object value=JSONObject.NULL;boolean ok=true;String error="";
    try{if("perf".equals(request.getString("method"))){String event=a.optString("event");if("start".equals(event)){at=System.nanoTime();cpu=android.os.Process.getElapsedCpuTime();}else if("end".equals(event))a.put("elapsedNs",System.nanoTime()-at).put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpu);System.out.println(a);}
     else{String method=a.getString("method");long hostStart=System.nanoTime();JSONObject input=a.getJSONObject("args");calls.merge(method,1,Integer::sum);if(method.startsWith("sql."))value=sql(method,input);else if(method.equals("runtime.delay"))Thread.sleep(input.optLong("milliseconds",1));else value=device.execute(method,input);hostNanos.merge(method,System.nanoTime()-hostStart,Long::sum);}
    }catch(Exception e){ok=false;error=e.toString();}
    JSONObject reply=new JSONObject().put("id",request.getLong("id")).put("ok",ok);if(ok)reply.put("value",value);else reply.put("code",error);run.reply(reply);
   }
   JSONObject outcome=result.get(5,TimeUnit.SECONDS);System.out.println(new JSONObject().put("event","host-counts").put("calls",new JSONObject(calls)).put("hostNanos",new JSONObject(hostNanos)));System.out.println(outcome);if(!"success".equals(outcome.optString("status")))throw new AssertionError("guest failed");
  }finally{db.close();executor.shutdownNow();}
 }
}
