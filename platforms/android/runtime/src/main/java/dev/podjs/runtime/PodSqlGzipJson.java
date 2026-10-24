package dev.podjs.runtime;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import java.io.File;
import org.json.*;
/** Read-only bounded projection; caller owns the serialized SQL connection. */
final class PodSqlGzipJson {
 static JSONArray project(SQLiteDatabase db,File root,JSONObject args,CancellationSignal cancellation)throws Exception {
            if(!"android-json-gzip-v1".equals(args.optString("format")))throw new IllegalArgumentException("invalid_projection_format");
            int maximum=args.optInt("maxRows",32);if(maximum<1||maximum>64)throw new IllegalArgumentException("invalid_projection_rows");
            String text=args.getString("sql"),key=args.getString("keyColumn"),expected=args.optString("expectedHashColumn","");
            JSONArray params=args.optJSONArray("params"),fields=args.getJSONArray("fields"),result=new JSONArray();
            try(Cursor cursor=db.rawQueryWithFactory((database,driver,editTable,query)->{
                if(params!=null)for(int i=0;i<params.length();i++){Object v=params.opt(i);if(v==null||v==JSONObject.NULL)query.bindNull(i+1);else if(v instanceof Float||v instanceof Double)query.bindDouble(i+1,((Number)v).doubleValue());else if(v instanceof Number)query.bindLong(i+1,((Number)v).longValue());else query.bindString(i+1,String.valueOf(v));}
                return new android.database.sqlite.SQLiteCursor(driver,editTable,query);
            },text,null,null,cancellation)){
                while(cursor.moveToNext()){
                    cancellation.throwIfCanceled();if(result.length()>=maximum)throw new IllegalArgumentException("projection_row_limit");
                    JSONObject row=new JSONObject();for(int i=0;i<fields.length();i++){String column=fields.getJSONObject(i).getString("column");int at=cursor.getColumnIndexOrThrow(column);row.put(column,cursor.isNull(at)?JSONObject.NULL:cursor.getString(at));}
                    String hash=expected.isEmpty()?"":cursor.getString(cursor.getColumnIndexOrThrow(expected));
                    JSONObject projected=PodGzipJson.fingerprint(row,fields,root,hash==null?"":hash,args.optInt("chunkSize",131072),args.optInt("maxInputBytes",1048576),cancellation::throwIfCanceled);
                    String rowKey=cursor.getString(cursor.getColumnIndexOrThrow(key));if(rowKey==null||rowKey.length()>512)throw new IllegalArgumentException("invalid_projection_key");projected.put("key",rowKey);result.put(projected);
                }
            }return result;

 }
}
