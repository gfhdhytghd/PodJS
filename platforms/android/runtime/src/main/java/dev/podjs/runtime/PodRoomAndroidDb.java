package dev.podjs.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

/** Opens candidate paths only; never supplied a source database path. */
final class PodRoomAndroidDb implements PodRoomV18Converter.Db {
    private final SQLiteDatabase db;
    PodRoomAndroidDb(File file) {
        db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }
    public void exec(String sql,Object... args){db.execSQL(sql,args);}
    public List<Map<String,Object>> query(String sql,Object... args){
        List<Map<String,Object>> result=new ArrayList<>();
        // rawQuery's String[] cannot bind null on all platform versions.
        try(Cursor c=db.rawQueryWithFactory((database,driver,edit,query)->{
            for(int i=0;i<args.length;i++){
                Object v=args[i];if(v==null)query.bindNull(i+1);else if(v instanceof Float||v instanceof Double)query.bindDouble(i+1,((Number)v).doubleValue());else if(v instanceof Number)query.bindLong(i+1,((Number)v).longValue());else query.bindString(i+1,v.toString());
            }
            return new android.database.sqlite.SQLiteCursor(driver,edit,query);
        },sql,new String[0],null)){
            while(c.moveToNext()){
                Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<c.getColumnCount();i++){
                    Object v;switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL:v=null;break;case Cursor.FIELD_TYPE_INTEGER:v=c.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:v=c.getDouble(i);break;case Cursor.FIELD_TYPE_BLOB:v=c.getBlob(i);break;default:v=c.getString(i);}
                    row.put(c.getColumnName(i),v);
                }result.add(row);
            }
        }return result;
    }
    public void close(){db.close();}
}
