package dev.podjs.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-driven persistent file transfer state machine. Owns no worker or timer.
 * Call advance after a reply or reconnect; explicit pollConsent enables a status
 * request while waiting for local approval. One active transfer per peer.
 * The supplied queue is coordinator-owned: do not manually forget its requests.
 */
public final class PodSyncOutgoingFiles implements AutoCloseable {
    private final PodSyncFileRequests requests;
    private final PodSyncFileSnapshots snapshots;
    private final SQLiteDatabase db;
    private PodSyncFileSnapshots.Reader reader;
    private String readerId;
    private boolean closed;
    public static final class Status {
        public final String peerId,transferId,phase,requestId;
        public final boolean cancelRequested;
        public final long totalBytes, acknowledgedBytes;
        public final boolean progressKnown;
        private Status(Row row) throws Exception {
            peerId=row.peer; transferId=row.id; phase=row.phase; requestId=row.request; cancelRequested=row.cancel;
            totalBytes=row.manifest.getLong("size");
            progressKnown=phase.equals("chunks") || phase.equals("finish") || phase.equals("complete");
            long missingBytes=0; java.util.HashSet<Integer> seen=new java.util.HashSet<>();
            if(progressKnown && !phase.equals("complete")) {
                int count=row.manifest.getJSONArray("chunk_hashes").length();
                for(int n=0;n<row.missing.length();n++) {
                    Object raw=row.missing.get(n);
                    if(!(raw instanceof Number)) throw new IOException("Invalid missing chunk index");
                    int index=((Number)raw).intValue();
                    if(((Number)raw).doubleValue()!=index || index<0 || index>=count || !seen.add(index)) throw new IOException("Invalid missing chunk index");
                    missingBytes+=Math.min(65536L,totalBytes-index*65536L);
                }
            }
            acknowledgedBytes=progressKnown?totalBytes-missingBytes:0;
        }
    }
    private static final class Row {
        String peer,id,phase,request; boolean cancel; JSONObject manifest; JSONArray missing;
        Row(Cursor cursor) throws Exception {
            peer=cursor.getString(0); id=cursor.getString(1); phase=cursor.getString(2); request=cursor.isNull(3)?null:cursor.getString(3);
            cancel=cursor.getInt(4)!=0; manifest=PodSyncIncomingFiles.normalize(new JSONObject(cursor.getString(5))); missing=new JSONArray(cursor.getString(6));
            if(!id.equals(manifest.getString("transfer_id")) || !java.util.Arrays.asList("offer","waiting","missing","chunks","finish","cancelling","complete","cancelled").contains(phase)) throw new IOException("Invalid outgoing transfer journal");
        }
    }
    public PodSyncOutgoingFiles(PodSyncFileRequests requests,PodSyncFileSnapshots snapshots) {
        this.requests=requests; this.snapshots=snapshots; db=requests.db;
        synchronized(requests) { db.execSQL("CREATE TABLE IF NOT EXISTS transfers (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, id TEXT NOT NULL, phase TEXT NOT NULL, request TEXT, cancel INTEGER NOT NULL, manifest TEXT NOT NULL, missing TEXT NOT NULL, UNIQUE(peer,id))"); }
    }
    private void check() throws IOException { if(closed) throw new IOException("Outgoing file coordinator closed"); }
    private static void identity(String id) { if(id==null || !id.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid sync identity"); }
    private Row read(String peer,String id) throws Exception {
        try(Cursor cursor=db.rawQuery("SELECT peer,id,phase,request,cancel,manifest,missing FROM transfers WHERE peer=? AND id=?",new String[]{peer,id})) { return cursor.moveToFirst()?new Row(cursor):null; }
    }
    private Row active(String peer) throws Exception {
        try(Cursor cursor=db.rawQuery("SELECT peer,id,phase,request,cancel,manifest,missing FROM transfers WHERE peer=? AND phase NOT IN ('complete','cancelled') ORDER BY ordinal LIMIT 1",new String[]{peer})) { return cursor.moveToFirst()?new Row(cursor):null; }
    }
    private void save(Row row) throws IOException {
        ContentValues values=new ContentValues(); values.put("phase",row.phase); values.put("request",row.request); values.put("cancel",row.cancel?1:0); values.put("missing",row.missing.toString());
        if(db.update("transfers",values,"peer=? AND id=?",new String[]{row.peer,row.id})!=1) throw new IOException("Outgoing transfer missing");
    }
    public Status start(String peer,String snapshotId) throws Exception {
        identity(peer); identity(snapshotId);
        synchronized(requests) { check(); db.beginTransaction();
            try {
                Row old=read(peer,snapshotId); if(old!=null) { db.setTransactionSuccessful(); return new Status(old); }
                if(active(peer)!=null || requests.next(peer)!=null) throw new IOException("Peer has an active file transfer");
                if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM transfers",null)>=128) throw new IOException("Outgoing transfer journal full");
                closeReader(); JSONObject manifest=snapshots.get(snapshotId);
                ContentValues row=new ContentValues(); row.put("peer",peer); row.put("id",snapshotId); row.put("phase","offer"); row.put("cancel",0); row.put("manifest",manifest.toString()); row.put("missing","[]");
                db.insertOrThrow("transfers",null,row); Status result=new Status(read(peer,snapshotId)); db.setTransactionSuccessful(); return result;
            } finally { db.endTransaction(); }
        }
    }
    public Status status(String peer,String id) throws Exception {
        identity(peer); identity(id); synchronized(requests) { check(); Row row=read(peer,id); if(row==null) throw new IOException("Unknown outgoing transfer"); return new Status(row); }
    }
    /** Bounded cross-peer lookup for the local guest adapter, not a wire API. */
    java.util.List<Status> findTransfer(String id) throws Exception {
        identity(id); synchronized(requests) {
            check(); java.util.ArrayList<Status> result=new java.util.ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,request,cancel,manifest,missing FROM transfers WHERE id=? ORDER BY ordinal LIMIT 2",new String[]{id})) {
                while(rows.moveToNext()) result.add(new Status(new Row(rows)));
            }
            return result;
        }
    }
    java.util.List<String> transferIdsAfter(String after) throws Exception {
        synchronized(requests) {
            check(); java.util.ArrayList<String> result=new java.util.ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT DISTINCT id FROM transfers WHERE id>? ORDER BY id LIMIT 64",new String[]{after})) {
                while(rows.moveToNext()) result.add(rows.getString(0));
            }
            return result;
        }
    }
    /** Rebuild host UI/recovery state without an in-memory transfer ID list. */
    public java.util.List<Status> list(String peer) throws Exception {
        identity(peer); synchronized(requests) { check(); java.util.ArrayList<Status> result=new java.util.ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,request,cancel,manifest,missing FROM transfers WHERE peer=? ORDER BY ordinal",new String[]{peer})) {
                while(rows.moveToNext()) { result.add(new Status(new Row(rows))); if(result.size()>128) throw new IOException("Outgoing transfer journal full"); }
            }
            return java.util.Collections.unmodifiableList(result);
        }
    }
    public void cancel(String peer,String id) throws Exception {
        identity(peer); identity(id); synchronized(requests) { check(); db.beginTransaction();
            try {
                Row row=read(peer,id); if(row==null) throw new IOException("Unknown outgoing transfer");
                if(!row.phase.equals("cancelled")) {
                    Row other=active(peer); if(other!=null && !other.id.equals(id)) throw new IOException("Peer has another active transfer");
                    // A never-enqueued offer has no remote side effects.
                    if(row.phase.equals("offer") && row.request==null) row.phase="cancelled";
                    else { row.cancel=true; if(row.request==null) row.phase="cancelling"; }
                    save(row);
                }
                closeReader(); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    private void apply(Row row,PodSyncFileRequests.Request request) throws Exception {
        JSONObject sent=new JSONObject(new String(request.payload,java.nio.charset.StandardCharsets.UTF_8));
        String method=sent.getString("method"); JSONObject value=request.reply.getJSONObject("value"); String remote=value.getString("phase");
        if(method.equals("cancel") || remote.equals("cancelled")) row.phase="cancelled";
        else if(remote.equals("complete")) row.phase="complete";
        else if(method.equals("offer") || method.equals("status")) row.phase=remote.equals("accepted")?"missing":"waiting";
        else if(method.equals("missing")) {
            row.missing=value.getJSONArray("missing");
            for(int n=0;n<row.missing.length();n++) if(row.missing.getInt(n)>=row.manifest.getJSONArray("chunk_hashes").length()) throw new IOException("Peer requested nonexistent chunk");
            row.phase=row.missing.length()==0?"finish":"chunks";
        } else if(method.equals("chunk")) {
            if(row.missing.length()==0 || sent.getInt("index")!=row.missing.getInt(0)) throw new IOException("Chunk progress mismatch");
            row.missing.remove(0); row.phase=row.missing.length()==0?"finish":"chunks";
        } else throw new IOException("Unexpected outgoing file reply");
        if(row.cancel && !row.phase.equals("cancelled")) row.phase="cancelling";
        row.request=null; save(row);
        if(!requests.forgetCompleted(row.peer,request.messageId)) throw new IOException("Outgoing file observation missing");
    }
    /** Consume a durable observation and enqueue its successor atomically.
     * Returned pending request may already have been sent: retransmission is safe.
     * null means terminal, no active transfer, or waiting without an explicit poll.
     */
    public PodSyncFileRequests.Request advance(String peer,boolean pollConsent) throws Exception {
        identity(peer); synchronized(requests) { check(); db.beginTransaction();
            try {
                Row row=active(peer);
                if(row==null) { closeReader(); db.setTransactionSuccessful(); return null; }
                if(row.request!=null) {
                    PodSyncFileRequests.Request pending=requests.get(peer,row.request);
                    if(pending==null) throw new IOException("Outgoing request journal mismatch");
                    if(pending.reply==null) { db.setTransactionSuccessful(); return pending; }
                    apply(row,pending);
                }
                if(!row.phase.equals("chunks")) closeReader();
                if(row.phase.equals("complete") || row.phase.equals("cancelled") || (row.phase.equals("waiting") && !pollConsent)) { db.setTransactionSuccessful(); return null; }
                if(requests.next(peer)!=null) throw new IOException("Unrelated outgoing request blocks transfer");
                String method=row.phase.equals("waiting")?"status":row.phase.equals("chunks")?"chunk":row.phase.equals("cancelling")?"cancel":row.phase;
                JSONObject command=new JSONObject().put("version",1).put("method",method);
                if(method.equals("offer")) command.put("manifest",row.manifest);
                else {
                    command.put("transfer_id",row.id);
                    if(method.equals("chunk")) {
                        if(reader==null || !row.id.equals(readerId)) { closeReader(); reader=snapshots.reader(row.id); readerId=row.id; }
                        int index=row.missing.getInt(0); command.put("index",index).put("data_base64",Base64.encodeToString(reader.chunk(index),Base64.NO_WRAP));
                    }
                }
                PodSyncFileRequests.Request pending=requests.enqueue(peer,command); row.request=pending.messageId; save(row);
                db.setTransactionSuccessful(); return pending;
            } finally { db.endTransaction(); }
        }
    }
    /** Explicit source release when no peer transfer is active, including a
     * never-offered source. Missing data is idempotent after a lost cleanup reply.
     * Retains terminal journal identities and does not remove remote files. */
    public void releaseSnapshot(String id) throws Exception {
        identity(id); synchronized(requests) { check(); db.beginTransaction();
            try {
                if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM transfers WHERE id=? AND phase NOT IN ('complete','cancelled')",new String[]{id})!=0)
                    throw new IOException("Snapshot still active");
                closeReader(); snapshots.discard(id); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    private void closeReader() throws IOException { if(reader!=null) { try { reader.close(); } finally { reader=null; readerId=null; } } }
    /** Release IO leases without changing persistent transfer/request state. */
    public void pause() throws IOException { synchronized(requests) { check(); closeReader(); } }
    @Override public void close() throws IOException { synchronized(requests) { if(!closed) { closed=true; closeReader(); } } }
}
