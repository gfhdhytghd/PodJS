package dev.podjs.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

/** Opens candidate paths only; never supplied a source database path. */
final class PodRoomAndroidDb implements PodRoomV18Converter.Db {
    private final SQLiteDatabase db;
    private final android.os.CancellationSignal cancellation;
    private int scanRows=32;
    PodRoomAndroidDb(File file){this(file,false,null);}
    /** SQLite must recover a killed guest transaction before read-only admission.
     * Only the coordinator's validated published target is supplied here. Opening
     * writable does not run guest/schema migrations or import the source again. */
    static PodRoomAndroidDb recoverPublished(File file)throws Exception {
        PodFileConverter.safe(file.getParentFile(),file.getName());
        if(!file.isFile())throw new java.io.IOException("active_database_invalid");
        try(PodRoomAndroidDb recovery=new PodRoomAndroidDb(file)) {
            recovery.query("PRAGMA user_version");
        }
        return new PodRoomAndroidDb(file,true,null);
    }
    PodRoomAndroidDb(File file,boolean readOnly,android.os.CancellationSignal cancellation) {
        this.cancellation=cancellation;
        try {PodRoomV18Converter.checkDatabaseFamily(file);}catch(java.io.IOException unsafe){throw new IllegalStateException("migration_storage_invalid",unsafe);}
        db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,(readOnly?SQLiteDatabase.OPEN_READONLY:SQLiteDatabase.CREATE_IF_NECESSARY) | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }
    public void exec(String sql,Object... args)throws Exception {
        // Rollback must remain usable after a deadline or interrupted statement.
        if(!sql.equals("ROLLBACK"))PodTakeoverCoordinator.Work.check();
        db.execSQL(sql,args);
    }
    public List<Map<String,Object>> query(String sql,Object... args)throws Exception{return read(sql,args,Long.MAX_VALUE);}
    public List<Map<String,Object>> page(String sql,Object... args)throws Exception {
        // SQLiteCursor counts its whole SQL result when filling the first
        // window. Bound SQL too, then adapt to decoded row sizes: stopping only
        // the Java loop would repeatedly scan up to 1,024 large source rows.
        return read("SELECT * FROM ("+sql+") LIMIT "+scanRows,args,1024*1024);
    }
    private List<Map<String,Object>> read(String sql,Object[] args,long budget)throws Exception {
        PodTakeoverCoordinator.Work.check();
        for(long window=2L*1024*1024;;window*=2)try{return read(sql,args,budget,window);}
        catch(android.database.sqlite.SQLiteBlobTooBigException large) {
            // Retry only read queries, with a bounded CursorWindow. The normal
            // path retains the platform's small window; large notes need more.
            if(window>=16L*1024*1024)throw new java.io.IOException("migration_row_too_large");
        }
    }
    private List<Map<String,Object>> read(String sql,Object[] args,long budget,long window)throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        android.os.CancellationSignal signal=cancellation==null?new android.os.CancellationSignal():cancellation;
        // rawQuery's String[] cannot bind null on all platform versions.
        try(AutoCloseable registration=PodTakeoverCoordinator.Work.cancellable(signal::cancel);
            Cursor c=db.rawQueryWithFactory((database,driver,edit,query)->{
            for(int i=0;i<args.length;i++){
                Object v=args[i];if(v==null)query.bindNull(i+1);else if(v instanceof Float||v instanceof Double)query.bindDouble(i+1,((Number)v).doubleValue());else if(v instanceof Number)query.bindLong(i+1,((Number)v).longValue());else query.bindString(i+1,v.toString());
            }
            android.database.sqlite.SQLiteCursor cursor=new android.database.sqlite.SQLiteCursor(driver,edit,query);
            try {
                if(window>2L*1024*1024)cursor.setWindow(new android.database.CursorWindow("podjs-migration",window));
                return cursor;
            }catch(RuntimeException|Error failure){PodTakeoverCoordinator.closeAfterFailure(cursor,failure);throw failure;}
        },sql,new String[0],null,signal)){
            long decoded=0;
            while(c.moveToNext()){
                PodTakeoverCoordinator.Work.check();
                Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<c.getColumnCount();i++){
                    Object v;switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL:v=null;break;case Cursor.FIELD_TYPE_INTEGER:v=c.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:v=c.getDouble(i);break;case Cursor.FIELD_TYPE_BLOB:v=c.getBlob(i);break;default:v=c.getString(i);}
                    row.put(c.getColumnName(i),v);
                    decoded+=v instanceof String?2L*((String)v).length():v instanceof byte[]?((byte[])v).length:16;
                    decoded+=64;
                }result.add(row);
                if(decoded>=budget)break;
            }
            if(budget!=Long.MAX_VALUE&&!result.isEmpty())scanRows=(int)Math.max(1,Math.min(1024,budget*result.size()/Math.max(1,decoded)));
        }return result;
    }
    public void close(){db.close();}
}
