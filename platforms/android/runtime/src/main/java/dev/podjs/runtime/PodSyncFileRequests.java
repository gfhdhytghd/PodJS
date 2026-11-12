package dev.podjs.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only durable RPC queue. Not yet the file snapshot/transfer scheduler.
 * One pending request per peer prevents dependent file commands overtaking it.
 * Completed observations remain until the host persists its next transfer state
 * and explicitly forgets them. No pending request is automatically evicted.
 */
public final class PodSyncFileRequests implements Closeable {
    // Shared only with the host transfer coordinator so request creation and
    // progress/receipt consumption can commit in one SQLite transaction.
    final SQLiteDatabase db;
    public static final class Request {
        public final String peerId,messageId;
        public final byte[] payload;
        public final JSONObject reply;
        private Request(Cursor row) throws Exception {
            peerId=row.getString(0); messageId=row.getString(1); payload=row.getBlob(2);
            reply=row.isNull(3)?null:new JSONObject(row.getString(3));
        }
    }
    public PodSyncFileRequests(Context context,String appId) throws Exception {
        PodTakeover.requireReady(context);
        identity(appId);
        File root=new File(PodTakeover.sandbox(context),"no_backup/podjs-file-requests");
        if(!root.mkdirs() && !root.isDirectory()) throw new IOException("File request storage unavailable");
        PodRoomV18Converter.checkDatabaseFamily(new File(root,appId+".sqlite"));
        db = SQLiteDatabase.openOrCreateDatabase(new File(root,appId+".sqlite"),null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS requests (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, id TEXT NOT NULL UNIQUE, payload BLOB NOT NULL, reply TEXT, cost INTEGER NOT NULL)");
        } catch(Exception|Error error) { PodTakeoverCoordinator.closeAfterFailure(db,error); throw error; }
    }
    private static void identity(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid sync identity");
    }
    private Request read(String peer,String id) throws Exception {
        try(Cursor row=db.rawQuery("SELECT peer,id,payload,reply FROM requests WHERE peer=? AND id=?",new String[]{peer,id})) {
            return row.moveToFirst()?new Request(row):null;
        }
    }
    public synchronized Request get(String peer,String id) throws Exception { identity(peer); identity(id); return read(peer,id); }
    public synchronized Request next(String peer) throws Exception {
        identity(peer);
        try(Cursor row=db.rawQuery("SELECT peer,id,payload,reply FROM requests WHERE peer=? AND reply IS NULL ORDER BY ordinal LIMIT 1",new String[]{peer})) {
            return row.moveToFirst()?new Request(row):null;
        }
    }
    /** Rediscover durable observations after restart before advancing transfers. */
    public synchronized java.util.List<Request> completed(String peer) throws Exception {
        identity(peer); java.util.ArrayList<Request> result=new java.util.ArrayList<>();
        try(Cursor rows=db.rawQuery("SELECT peer,id,payload,reply FROM requests WHERE peer=? AND reply IS NOT NULL ORDER BY ordinal",new String[]{peer})) {
            while(rows.moveToNext()) { result.add(new Request(rows)); if(result.size()>128) throw new IOException("File request quota exceeded"); }
        }
        return java.util.Collections.unmodifiableList(result);
    }
    /** Caller builds a v1 request; IDs are generated here and never supplied/reused. */
    public synchronized Request enqueue(String peer,JSONObject request) throws Exception {
        identity(peer);
        if(request==null || request.optInt("version",0)!=1 || !java.util.Arrays.asList("offer","status","missing","chunk","finish","cancel").contains(request.optString("method")))
            throw new IllegalArgumentException("Invalid outgoing file request");
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(request.toString()));
        if(encoded.remaining()>96*1024) throw new IllegalArgumentException("File request too large");
        byte[] payload=new byte[encoded.remaining()]; encoded.get(payload);
        String id=UUID.randomUUID().toString(); int cost=payload.length+4096+256;
        db.beginTransaction();
        try {
            if(next(peer)!=null) throw new IOException("Peer has an outstanding file request");
            if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM requests",null)>=128 ||
                DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(cost),0) FROM requests",null)+cost>8*1024*1024)
                throw new IOException("File request quota exceeded");
            ContentValues row=new ContentValues(); row.put("peer",peer); row.put("id",id); row.put("payload",payload); row.put("cost",cost);
            db.insertOrThrow("requests",null,row); Request result=read(peer,id); db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    /** Authenticated peer only. First validated observation wins; later duplicate
     * replies cannot overwrite progress if remote status has since changed. */
    public synchronized String receive(String peer,String id,JSONObject reply) throws Exception {
        identity(peer); identity(id);
        if(reply==null || reply.length()!=4 || !(reply.get("version") instanceof Integer) || reply.getInt("version")!=1 ||
            !"reply".equals(reply.get("type")) || !(reply.get("request_sha256") instanceof String) ||
            !reply.getString("request_sha256").matches("[0-9a-f]{64}") || reply.toString().getBytes(StandardCharsets.UTF_8).length>4096)
            throw new IOException("Invalid file reply");
        JSONObject value=reply.getJSONObject("value");
        if(!(value.get("phase") instanceof String) || !java.util.Arrays.asList("offered","accepting","accepted","complete","cancelling","cancelled").contains(value.getString("phase")))
            throw new IOException("Invalid file phase");
        db.beginTransaction();
        try {
            Request request=read(peer,id);
            if(request==null) { db.setTransactionSuccessful(); return "stale_reply"; }
            if(!hex(MessageDigest.getInstance("SHA-256").digest(request.payload)).equals(reply.getString("request_sha256"))) throw new IOException("File reply does not match request");
            String method=new JSONObject(new String(request.payload,StandardCharsets.UTF_8)).getString("method"), phase=value.getString("phase");
            if(value.length()!=(method.equals("missing")?2:1)) throw new IOException("Unexpected reply fields");
            if(method.equals("missing")) {
                JSONArray missing=value.getJSONArray("missing"); if(missing.length()>256) throw new IOException("Too many chunks");
                int previous=-1;
                for(int n=0;n<missing.length();n++) { if(!(missing.get(n) instanceof Integer)) throw new IOException("Invalid chunk index"); int index=missing.getInt(n); if(index<=previous || index>255) throw new IOException("Invalid chunk order"); previous=index; }
            }
            if((method.equals("finish") && !phase.equals("complete") && !phase.equals("cancelled")) || (method.equals("cancel") && !phase.equals("cancelled")) ||
                ((method.equals("chunk") || method.equals("missing")) && !phase.equals("accepted") && !phase.equals("complete") && !phase.equals("cancelled"))) throw new IOException("Invalid reply phase for method");
            if(request.reply!=null) { db.setTransactionSuccessful(); return "duplicate_reply"; }
            ContentValues values=new ContentValues(); values.put("reply",reply.toString());
            if(db.update("requests",values,"peer=? AND id=? AND reply IS NULL",new String[]{peer,id})!=1) throw new IOException("File request disappeared");
            db.setTransactionSuccessful(); return "reply";
        } finally { db.endTransaction(); }
    }
    /** Only after the host has durably consumed the observation. */
    public synchronized boolean forgetCompleted(String peer,String id) {
        identity(peer); identity(id); return db.delete("requests","peer=? AND id=? AND reply IS NOT NULL",new String[]{peer,id})==1;
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return out.toString(); }
    @Override public synchronized void close() { db.close(); }
}
