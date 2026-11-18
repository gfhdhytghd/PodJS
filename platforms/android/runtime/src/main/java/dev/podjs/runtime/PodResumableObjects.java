package dev.podjs.runtime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import org.json.*;

/** Application-private adapter over the existing verified Rust receiver. No
 * companion identity, network offer, host-accept or file-consent API is used.
 * The SQLite/OS lock covers epoch checks, native side effects and request binds.
 * APPLIED belongs exclusively to the calling application's business transaction.
 */
final class PodResumableObjects {
    static final long RETENTION=86400000L, LEASE=30000L, JOURNAL_BYTES=16L*1024*1024;
    private static final String PROCESS=java.util.UUID.randomUUID().toString();
    private final Context context;
    private final String application;
    private final File journal, lock, files;
    PodResumableObjects(Context context)throws Exception {
        PodTakeover.requireReady(context);this.context=context;
        files=PodTakeover.filesRoot(context).getCanonicalFile();
        application="resumable-"+hash((context.getPackageName()+"\n"+files.getAbsolutePath()).getBytes(StandardCharsets.UTF_8));
        File root=new File(PodTakeover.dataRoot(context).getCanonicalFile(),"resumable-objects-v1");
        durableDirectories(root,PodTakeover.dataRoot(context).getCanonicalFile());
        journal=new File(root,"journal.sqlite");lock=new File(root,"journal.lock");
    }
    private static String hash(byte[] bytes)throws Exception {
        byte[] value=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();
        for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static String id(JSONObject a,String key,int length)throws Exception {
        Object value=a.get(key);if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{"+length+"}"))throw new IOException("INVALID_FRAME:"+key);return (String)value;
    }
    private static long integer(JSONObject a,String key,long max)throws Exception {
        Object v=a.get(key);if(!(v instanceof Number))throw new IOException("INVALID_FRAME:"+key);
        double n=((Number)v).doubleValue();if(n<0||n>max||n!=Math.rint(n))throw new IOException("INVALID_FRAME:"+key);return (long)n;
    }
    private static JSONObject manifest(JSONObject object)throws Exception {
        if(object.length()!=4)throw new IOException("OBJECT_CONFLICT");
        String oid=id(object,"id",64),digest=id(object,"sha256",64);long size=integer(object,"size",16777216);
        JSONArray hashes=object.getJSONArray("chunkHashes");if(hashes.length()!=(size+65535)/65536)throw new IOException("OBJECT_CONFLICT");
        JSONArray frozen=new JSONArray();for(int i=0;i<hashes.length();i++){Object h=hashes.get(i);if(!(h instanceof String)||!((String)h).matches("[0-9a-f]{64}"))throw new IOException("OBJECT_CONFLICT");frozen.put(h);}
        return new JSONObject().put("transfer_id",oid).put("size",size).put("sha256",digest).put("chunk_hashes",frozen).put("mime","application/octet-stream");
    }
    private static JSONObject core(PodSyncFileReceiver receiver,JSONObject command)throws Exception {
        return core(receiver,command,null);
    }
    private static JSONObject core(PodSyncFileReceiver receiver,JSONObject command,byte[] bytes)throws Exception {
        JSONObject reply;try{reply=new JSONObject(bytes==null?receiver.command(command.toString()):receiver.commandBytes(command.toString(),bytes));}
        catch(PodSyncFileReceiver.QuotaException capacity){throw new IOException("WINDOW_FULL");}
        if(!reply.getBoolean("ok")){
            String text=reply.optString("message");
            if(text.contains("quota"))throw new IOException("WINDOW_FULL");
            if(text.contains("checksum"))throw new IOException("HASH_MISMATCH");
            if(text.contains("missing"))throw new IOException("CHUNKS_MISSING");
            throw new IOException("LOCAL_IO");
        }
        return reply.getJSONObject("value");
    }
    private static JSONObject command(String method,String oid)throws Exception{return new JSONObject().put("method",method).put("transfer_id",oid);}
    private static String scalar(SQLiteDatabase db,String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    private static void check(SQLiteDatabase db,JSONObject a,String scope)throws Exception {
        String connection=id(a,"connectionId",32);long epoch=integer(a,"epoch",9007199254740991L);
        try(Cursor c=db.rawQuery("SELECT connection,epoch,owner,deadline FROM scopes WHERE scope=?",new String[]{scope})){
            if(!c.moveToFirst()||!connection.equals(c.getString(0))||epoch!=c.getLong(1))throw new IOException("STALE_EPOCH");
            if(!PROCESS.equals(c.getString(2))||SystemClock.elapsedRealtime()>=c.getLong(3))throw new IOException("LEASE_EXPIRED");
        }
    }
    JSONObject execute(String method,JSONObject a)throws Exception {return execute(method,a,null);}
    JSONObject execute(String method,JSONObject a,byte[] fileChunk)throws Exception {
        try{return executeUnchecked(method,a,fileChunk);}catch(IOException error){
            String detail=error.getMessage(),code=detail==null?null:detail.split(":",2)[0];
            if(code!=null && java.util.Arrays.asList("INVALID_FRAME","OBJECT_CONFLICT","STALE_EPOCH","LEASE_EXPIRED","BUSY","WINDOW_FULL","HASH_MISMATCH","CHUNKS_MISSING","LOCAL_IO","REQUEST_CONFLICT","JOB_CANCELLED","JOURNAL_CORRUPT","PUBLICATION_CONFLICT").contains(code))return new JSONObject().put("error",code).put("detail",detail);
            throw error;
        }
    }
    void stageGroup(JSONObject lease,JSONObject[] descriptors,java.util.List<PodReceiveWindow.Verified> chunks)throws Exception {
        executeUnchecked("file.resumable.group",lease,null,descriptors,chunks);
    }
    private JSONObject executeUnchecked(String method,JSONObject a,byte[] fileChunk)throws Exception {
        return executeUnchecked(method,a,fileChunk,null,null);
    }
    private JSONObject executeUnchecked(String method,JSONObject a,byte[] fileChunk,
            JSONObject[] descriptors,java.util.List<PodReceiveWindow.Verified> chunks)throws Exception {
        if("file.resumable.status".equals(method)&&"probe".equals(a.optString("operation")))return new JSONObject().put("version",1).put("elapsedMillis",SystemClock.elapsedRealtime()).put("chunkBytes",65536).put("maxObjectBytes",16777216).put("maxInFlightObjects",4).put("maxStagingBytes",64*1024*1024).put("maxJournalBytes",JOURNAL_BYTES).put("durable",true).put("fencing",true).put("chunkReceipt",true).put("requestBindings",true).put("fileRefs",true).put("methods",new JSONArray(new String[]{"file.resumable.stage","file.resumable.status","file.resumable.missing","file.resumable.finalize","file.resumable.release"}));
        String scope=id(a,"scope",64);
        try(RandomAccessFile lf=new RandomAccessFile(lock,"rw")){
            FileLock held;try{held=lf.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("BUSY");}if(held==null)throw new IOException("BUSY");
            try(FileLock ignored=held;SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(journal,null)){
                db.execSQL("PRAGMA synchronous=FULL");try(Cursor quota=db.rawQuery("PRAGMA max_page_count=2048",null)){if(!quota.moveToFirst()||quota.getLong(0)>2048)throw new IOException("LOCAL_IO");}
                db.execSQL("CREATE TABLE IF NOT EXISTS scopes(scope TEXT PRIMARY KEY,connection TEXT NOT NULL,epoch INTEGER NOT NULL,owner TEXT NOT NULL,deadline INTEGER NOT NULL,retain_until INTEGER NOT NULL,read_only INTEGER NOT NULL DEFAULT 0,retain_elapsed INTEGER NOT NULL,max_window INTEGER NOT NULL,max_staging INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS requests(scope TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(scope,id))");
                db.execSQL("CREATE TABLE IF NOT EXISTS objects(scope TEXT NOT NULL,id TEXT NOT NULL,manifest TEXT NOT NULL,phase TEXT NOT NULL,publication TEXT,PRIMARY KEY(scope,id))");
                db.beginTransaction();try {
                    if("file.resumable.status".equals(method)&&"collect".equals(a.optString("operation"))){collect(db,a);db.setTransactionSuccessful();return new JSONObject().put("collected",true);}
                    if("file.resumable.status".equals(method)&&"acquire".equals(a.optString("operation"))){
                        String connection=id(a,"connectionId",32);long epoch=0;boolean same=false;
                        try(Cursor c=db.rawQuery("SELECT connection,epoch,owner,deadline FROM scopes WHERE scope=?",new String[]{scope})){if(c.moveToFirst()){epoch=c.getLong(1);same=connection.equals(c.getString(0))&&PROCESS.equals(c.getString(2))&&SystemClock.elapsedRealtime()<c.getLong(3);}}
                        if((!same||a.optBoolean("forceNewEpoch"))&&++epoch>9007199254740991L)throw new IOException("LOCAL_IO");
                        db.execSQL("INSERT OR REPLACE INTO scopes VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{scope,connection,epoch,PROCESS,SystemClock.elapsedRealtime()+LEASE,System.currentTimeMillis()+RETENTION,a.optBoolean("readOnly",false)?1:0,SystemClock.elapsedRealtime()+RETENTION,Math.max(1,integer(a,"maxInFlightObjects",4)),Math.max(1,integer(a,"maxStagingBytes",134217728))});
                        db.setTransactionSuccessful();return new JSONObject().put("epoch",epoch);
                    }
                    check(db,a,scope);
                    JSONObject result;
                    if("file.resumable.status".equals(method)&&"readOnly".equals(a.optString("operation"))){db.execSQL("UPDATE scopes SET read_only=1 WHERE scope=?",new Object[]{scope});result=new JSONObject();}
                    else if("file.resumable.status".equals(method)&&"check".equals(a.optString("operation")))result=new JSONObject();
                    else if("file.resumable.status".equals(method)&&"requestBindings".equals(a.optString("operation")))result=requestBindings(db,a,scope);
                    else if(chunks!=null){
                        for(PodReceiveWindow.Verified chunk:chunks) {
                            PodTransferFrames.Chunk header=chunk.header;
                            if(chunk.applied){
                                String prior=scalar(db,"SELECT digest FROM requests WHERE scope=? AND id=?",scope,header.operation);
                                if(prior!=null&&!prior.equals(header.digest))throw new IOException("REQUEST_CONFLICT");
                                db.execSQL("INSERT OR IGNORE INTO requests VALUES(?,?,?)",new Object[]{scope,header.operation,header.digest});
                                continue;
                            }
                            JSONObject block=new JSONObject().put("object",descriptors[header.slot])
                                    .put("index",header.index).put("bindingId",header.operation)
                                    .put("requestDigest",header.digest).put("receiptOnly",true);
                            byte[] bytes=header.size==chunk.bytes.length?chunk.bytes:java.util.Arrays.copyOf(chunk.bytes,header.size);
                            object(db,"file.resumable.stage",block,scope,bytes);
                        }
                        result=new JSONObject();
                    }
                    else result=object(db,method,a,scope,fileChunk);
                    db.execSQL("UPDATE scopes SET deadline=?,retain_until=max(retain_until,?),retain_elapsed=max(retain_elapsed,?) WHERE scope=?",new Object[]{SystemClock.elapsedRealtime()+LEASE,System.currentTimeMillis()+RETENTION,SystemClock.elapsedRealtime()+RETENTION,scope});
                    db.setTransactionSuccessful();return result;
                }finally{db.endTransaction();try(FileChannel fd=FileChannel.open(journal.getParentFile().toPath(),StandardOpenOption.READ)){fd.force(true);}}
            }
        }
    }
    private static JSONObject requestBindings(SQLiteDatabase db,JSONObject args,String scope)throws Exception {
        String after=args.has("after")?id(args,"after",32):"";
        long limit=integer(args,"limit",128);
        if(limit==0)throw new IOException("INVALID_FRAME");
        JSONArray entries=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,digest FROM requests WHERE scope=? AND id>? ORDER BY id LIMIT ?",
                new String[]{scope,after,Long.toString(limit)})){
            while(c.moveToNext())entries.put(new JSONObject().put("id",c.getString(0)).put("digest",c.getString(1)));
        }
        return new JSONObject().put("entries",entries);
    }
    private JSONObject object(SQLiteDatabase db,String method,JSONObject a,String scope,byte[] fileChunk)throws Exception {
        JSONObject m=manifest(a.getJSONObject("object"));String oid=m.getString("transfer_id"),saved=scalar(db,"SELECT manifest FROM objects WHERE scope=? AND id=?",scope,oid);
        if(saved!=null&&!saved.equals(m.toString()))throw new IOException("OBJECT_CONFLICT");
        boolean stage="file.resumable.stage".equals(method),finish="file.resumable.finalize".equals(method),release="file.resumable.release".equals(method);
        if((stage||finish)&&"1".equals(scalar(db,"SELECT read_only FROM scopes WHERE scope=?",scope)))throw new IOException("JOB_CANCELLED");
        byte[] bytes=null;String rid=null,rd=null;int index=-1;
        if(stage){
            index=(int)integer(a,"index",255);if(index>=m.getJSONArray("chunk_hashes").length())throw new IOException("INVALID_FRAME");
            String encoded=fileChunk!=null||a.optBoolean("_podByteMode")?null:a.getString("dataBase64");if(encoded!=null&&encoded.length()>87384)throw new IOException("INVALID_FRAME");
            bytes=fileChunk!=null?fileChunk:encoded==null?PodBytePayload.input(a):Base64.decode(encoded,Base64.NO_WRAP);
            if((encoded!=null&&!encoded.equals(Base64.encodeToString(bytes,Base64.NO_WRAP)))||bytes.length!=Math.min(65536,m.getLong("size")-(long)index*65536)||(fileChunk==null&&!hash(bytes).equals(m.getJSONArray("chunk_hashes").getString(index))))throw new IOException("HASH_MISMATCH");
            rid=id(a,"bindingId",32);rd=id(a,"requestDigest",64);String old=scalar(db,"SELECT digest FROM requests WHERE scope=? AND id=?",scope,rid);
            if(old!=null&&!old.equals(rd))throw new IOException("REQUEST_CONFLICT");
        }
        // Merely inspecting an unstarted object never opens a native offer.
        if(saved==null&&!stage&&!finish&&!release){JSONArray all=new JSONArray();for(int i=0;i<m.getJSONArray("chunk_hashes").length();i++)all.put(i);return new JSONObject().put("state","PENDING").put("missing",all);}
        if(release&&saved==null){try(PodSyncFileReceiver receiver=new PodSyncFileReceiver(context,application,scope)){core(receiver,command("cancel",oid));}discardPublication(a,scope,oid);return new JSONObject().put("released",true);}
        if(saved==null){
            File nativeRoot=new File(new File(context.getNoBackupFilesDir(),"podjs-sync"),application);
            File existing=new File(new File(nativeRoot,scope),oid);int count=0;long reserved=0;
            File[] peers=nativeRoot.listFiles();if(peers!=null)for(File peer:peers)if(peer.isDirectory()){
                if(Files.isSymbolicLink(peer.toPath()))throw new IOException("JOURNAL_CORRUPT");
                File[] transfers=peer.listFiles();if(transfers==null)throw new IOException("LOCAL_IO");
                for(File transfer:transfers)if(transfer.isDirectory()){if(Files.isSymbolicLink(transfer.toPath()))throw new IOException("JOURNAL_CORRUPT");count++;JSONObject descriptor=new JSONObject(new String(Files.readAllBytes(new File(transfer,"manifest.json").toPath()),StandardCharsets.UTF_8));reserved+=integer(descriptor,"size",16777216)*4;}
            }
            // Count the actual durable core, including a crash after the native
            // side effect but before this journal's transaction committed.
            long window=4,budget=67108864;try(Cursor c=db.rawQuery("SELECT MIN(max_window),MIN(max_staging) FROM scopes WHERE scope=? OR scope IN (SELECT scope FROM objects WHERE phase='active')",new String[]{scope})){if(c.moveToFirst()){window=Math.min(window,c.getLong(0));budget=Math.min(budget,c.getLong(1));}}
            if(!existing.isDirectory()&&(count>=window||reserved+m.getLong("size")*4>budget))throw new IOException("WINDOW_FULL");
            // Persist intent before the side effect. A crash leaves a verifiable
            // Rust manifest that the next identical call re-adopts under lock.
            db.execSQL("INSERT INTO objects(scope,id,manifest,phase) VALUES(?,?,?,'active')",new Object[]{scope,oid,m.toString()});
        }
        if("released".equals(scalar(db,"SELECT phase FROM objects WHERE scope=? AND id=?",scope,oid))){if(release){discardPublication(a,scope,oid);return new JSONObject().put("released",true);}throw new IOException("OBJECT_CONFLICT");}
        try(PodSyncFileReceiver receiver=new PodSyncFileReceiver(context,application,scope)){
            if(release){core(receiver,command("cancel",oid));discardPublication(a,scope,oid);db.execSQL("UPDATE objects SET phase='released' WHERE scope=? AND id=?",new Object[]{scope,oid});return new JSONObject().put("released",true);}
            core(receiver,new JSONObject().put("method","offer").put("manifest",m));
            if(stage){
                JSONObject chunk=command("chunk",oid).put("index",index);
                if(fileChunk!=null||a.optBoolean("_podByteMode"))core(receiver,chunk,bytes);
                else core(receiver,chunk.put("data_base64",a.getString("dataBase64")));
                db.execSQL("INSERT OR IGNORE INTO requests VALUES(?,?,?)",new Object[]{scope,rid,rd});
                // The outer FULL transaction commits the request binding
                // before the service delivers this current-block receipt.
                if(a.optBoolean("receiptOnly",false)){
                    return new JSONObject().put("durable",true).put("index",index).put("bytes",bytes.length)
                        .put("sha256",m.getJSONArray("chunk_hashes").getString(index))
                        .put("bindingId",rid).put("requestDigest",rd);
                }
            }
            if(finish){
                File source=new File(core(receiver,command("finish",oid)).getString("path"));
                String path=a.has("path")?a.getString("path"):"resumable-published/"+scope+"/"+oid+".bin";
                if(!path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+") || java.util.Arrays.asList(path.split("/")).contains("..") || java.util.Arrays.asList(path.split("/")).contains("."))throw new IOException("INVALID_FRAME");
                String published=scalar(db,"SELECT publication FROM objects WHERE scope=? AND id=?",scope,oid);if(published!=null&&!published.equals(path))throw new IOException("OBJECT_CONFLICT");
                File parent=new File(files,path).getParentFile();
                durableDirectories(parent,files);
                try{PodSyncGuestFiles.save(files,path,source,m.getLong("size"),m.getString("sha256"),new android.os.CancellationSignal());}catch(android.system.ErrnoException conflict){if(conflict.errno==android.system.OsConstants.EEXIST)throw new IOException("PUBLICATION_CONFLICT");throw conflict;}
                db.execSQL("UPDATE objects SET publication=? WHERE scope=? AND id=?",new Object[]{path,scope,oid});
                return new JSONObject().put("path",path).put("size",m.getLong("size")).put("sha256",m.getString("sha256"));
            }
            JSONArray missing=core(receiver,command("missing",oid)).getJSONArray("missing");
            boolean complete=core(receiver,command("verified_complete",oid)).getBoolean("complete");
            if(!complete && missing.length()==0 && stage){core(receiver,command("finish",oid));complete=true;}
            return new JSONObject().put("missing",missing).put("state",complete?"STAGED":missing.length()==m.getJSONArray("chunk_hashes").length()?"PENDING":"PARTIAL");
        }
    }
    private static void durableDirectories(File directory,File boundary)throws Exception {
        if(directory==null||!directory.toPath().normalize().startsWith(boundary.toPath()))throw new IOException("LOCAL_IO");
        if(directory.equals(boundary))return;
        durableDirectories(directory.getParentFile(),boundary);
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("LOCAL_IO");
        if(!directory.mkdir()&&!directory.isDirectory())throw new IOException("LOCAL_IO");
        try(FileChannel fd=FileChannel.open(directory.getParentFile().toPath(),StandardOpenOption.READ)){fd.force(true);}
    }
    private void discardPublication(JSONObject a,String scope,String oid)throws Exception {
        if(!a.optBoolean("discardPublication",false))return;
        File path=new File(files,"resumable-published/"+scope+"/"+oid+".bin");
        if(Files.isSymbolicLink(path.toPath()))throw new IOException("LOCAL_IO");
        if(Files.deleteIfExists(path.toPath()))try(FileChannel fd=FileChannel.open(path.getParentFile().toPath(),StandardOpenOption.READ)){fd.force(true);}
    }
    /** Only unleased, expired native temporaries/request bindings are collected.
     * Publications are caller-owned; their business references are never guessed.
     * A backward wall clock conservatively delays collection. */
    private void collect(SQLiteDatabase db,JSONObject args)throws Exception {
        java.util.ArrayList<String> expired=new java.util.ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT scope FROM scopes WHERE scope=? AND retain_elapsed<=? AND NOT(owner=? AND deadline>?)",new String[]{id(args,"scope",64),Long.toString(SystemClock.elapsedRealtime()),PROCESS,Long.toString(SystemClock.elapsedRealtime())})){while(c.moveToNext())expired.add(c.getString(0));}
        for(String scope:expired){
            File directory=new File(new File(new File(context.getNoBackupFilesDir(),"podjs-sync"),application),scope);
            try(PodSyncFileReceiver receiver=new PodSyncFileReceiver(context,application,scope)){
                File[] objects=directory.listFiles();if(objects==null)throw new IOException("LOCAL_IO");
                for(File object:objects){if(!object.getName().matches("[0-9a-f]{64}")||Files.isSymbolicLink(object.toPath()))throw new IOException("JOURNAL_CORRUPT");core(receiver,command("cancel",object.getName()));if(args.optBoolean("discardPublication",false))discardPublication(new JSONObject().put("discardPublication",true),scope,object.getName());}
            }
            if(args.optBoolean("discardPublication",false))try(Cursor c=db.rawQuery("SELECT id FROM objects WHERE scope=?",new String[]{scope})){while(c.moveToNext())discardPublication(new JSONObject().put("discardPublication",true),scope,c.getString(0));}
            if(directory.isDirectory() && !directory.delete())throw new IOException("LOCAL_IO");
            File peerLock=new File(directory.getParentFile(),scope+".lock");if(peerLock.exists()&&!peerLock.delete())throw new IOException("LOCAL_IO");
            db.execSQL("DELETE FROM objects WHERE scope=?",new Object[]{scope});db.execSQL("DELETE FROM requests WHERE scope=?",new Object[]{scope});db.execSQL("DELETE FROM scopes WHERE scope=?",new Object[]{scope});
        }
    }
}
