package dev.podjs.runtime;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Durable host-only outgoing payload queue. Bytes are already encoded application
 * JSON; this storage layer does not interpret them. Read is never acknowledgement.
 * Host must authenticate the recipient before calling acknowledge.
 */
public final class PodSyncOutbox implements Closeable {
    public static final int MAX_MESSAGES = 1000;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PAYLOAD = 256 * 1024;
    private final SQLiteDatabase db;
    public static final class Message {
        public final String peerId, messageId;
        public final byte[] payload;
        public final long expiresAt;
        public final boolean highPriority;
        private Message(Cursor row) {
            peerId=row.getString(0); messageId=row.getString(1); payload=row.getBlob(2);
            expiresAt=row.getLong(3); highPriority=row.getInt(4)!=0;
        }
    }
    public PodSyncOutbox(Context context, String appId) throws IOException {
        PodTakeover.requireReady(context);
        id(appId);
        File root = new File(PodTakeover.sandbox(context),"no_backup/podjs-outbox");
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Outbox unavailable");
        PodRoomV18Converter.checkDatabaseFamily(new File(root,appId + ".sqlite"));
        db = SQLiteDatabase.openOrCreateDatabase(new File(root,appId + ".sqlite"),null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS outbox (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL, expires INTEGER NOT NULL, priority INTEGER NOT NULL, cost INTEGER NOT NULL, UNIQUE(peer,id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS outbox_delivery ON outbox(peer,priority,ordinal)");
            db.execSQL("CREATE TABLE IF NOT EXISTS ttl_intents (peer TEXT NOT NULL, id TEXT NOT NULL, digest BLOB NOT NULL, ttl INTEGER NOT NULL, expires INTEGER NOT NULL, priority INTEGER NOT NULL, PRIMARY KEY(peer,id))");
        } catch (RuntimeException|Error error) { PodTakeoverCoordinator.closeAfterFailure(db,error); throw error; }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid identity");
    }
    private static void time(long value) {
        if (value < 0 || value > 9007199254740991L) throw new IllegalArgumentException("Invalid timestamp");
    }
    /** First TTL request and its queue row commit atomically. The compact intent
     * survives ACK removal until expiry, preventing a lost service reply from
     * recreating the message with a later expiration. No unexpired intent evicts
     * another: the separate 10,000-row retry ledger rejects overflow. */
    public synchronized long enqueueWithTtl(String peer,String messageId,byte[] payload,long ttl,boolean high,long now) throws Exception {
        id(peer); id(messageId); time(now); time(ttl);
        if (ttl==0 || ttl>9007199254740991L-now || payload==null || payload.length>MAX_PAYLOAD) throw new IllegalArgumentException("Invalid message TTL or payload");
        byte[] bytes=payload.clone(), digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        db.beginTransaction();
        try {
            try(Cursor row=db.rawQuery("SELECT digest,ttl,expires,priority FROM ttl_intents WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if(row.moveToFirst()) {
                    if(row.getLong(1)!=ttl || row.getInt(3)!=(high?1:0) || !java.security.MessageDigest.isEqual(row.getBlob(0),digest))
                        throw new IllegalArgumentException("Message retry identity changed");
                    long expires=row.getLong(2);
                    if(expires<=now) throw new IllegalArgumentException("Message retry expired");
                    db.setTransactionSuccessful(); return expires;
                }
            }
            if(android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM outbox WHERE peer=? AND id=?",new String[]{peer,messageId})!=0)
                throw new IllegalArgumentException("Message ID belongs to an absolute-expiry request");
            db.delete("ttl_intents","expires<=?",new String[]{Long.toString(now)});
            if(android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM ttl_intents",null)>=10000)
                throw new IllegalStateException("Message retry ledger full");
            long expires=now+ttl; enqueue(peer,messageId,bytes,expires,high,now);
            ContentValues intent=new ContentValues(); intent.put("peer",peer); intent.put("id",messageId); intent.put("digest",digest);
            intent.put("ttl",ttl); intent.put("expires",expires); intent.put("priority",high?1:0);
            db.insertOrThrow("ttl_intents",null,intent); db.setTransactionSuccessful(); return expires;
        } finally { db.endTransaction(); }
    }
    public synchronized void enqueue(String peer, String messageId, byte[] payload, long expiresAt, boolean high, long now) {
        id(peer); id(messageId); time(expiresAt); time(now);
        if (expiresAt <= now || payload == null || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Expired or oversized message");
        byte[] stablePayload = payload.clone();
        db.beginTransaction();
        try {
            try(Cursor intent=db.rawQuery("SELECT digest,expires,priority FROM ttl_intents WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if(intent.moveToFirst()) {
                    byte[] digest;
                    try { digest=java.security.MessageDigest.getInstance("SHA-256").digest(stablePayload); }
                    catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
                    if(intent.getLong(1)!=expiresAt || intent.getInt(2)!=(high?1:0) || !java.security.MessageDigest.isEqual(intent.getBlob(0),digest))
                        throw new IllegalArgumentException("Message identity conflicts with TTL request");
                    db.setTransactionSuccessful(); return;
                }
            }
            try (Cursor row = db.rawQuery("SELECT peer,id,payload,expires,priority FROM outbox WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if (row.moveToFirst()) {
                    Message existing = new Message(row);
                    if (!Arrays.equals(existing.payload,stablePayload) || existing.expiresAt != expiresAt || existing.highPriority != high)
                        throw new IllegalArgumentException("Message identity reused with different content");
                    db.setTransactionSuccessful(); return;
                }
            }
            db.delete("outbox","expires<=?",new String[]{Long.toString(now)});
            // Conservative envelope overhead, counted as well as encoded payload.
            int cost = stablePayload.length + peer.length() + messageId.length() + 128;
            long count = DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM outbox",null);
            long used = DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(cost),0) FROM outbox",null);
            if (count >= MAX_MESSAGES || used + cost > MAX_BYTES) throw new IllegalStateException("Message outbox full");
            ContentValues values = new ContentValues(); values.put("peer",peer); values.put("id",messageId);
            values.put("payload",stablePayload); values.put("expires",expiresAt); values.put("priority",high ? 1 : 0); values.put("cost",cost);
            db.insertOrThrow("outbox",null,values); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Bounded batch; all messages remain pending after this read or a failed send. */
    public synchronized List<Message> pending(String peer, long now, int limit) {
        id(peer); time(now);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch limit");
        List<Message> messages = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT peer,id,payload,expires,priority FROM outbox WHERE peer=? AND expires>? ORDER BY priority DESC,ordinal ASC LIMIT ?",
                new String[]{peer,Long.toString(now),Integer.toString(limit)})) {
            while (rows.moveToNext()) messages.add(new Message(rows));
        }
        return messages;
    }
    public synchronized boolean acknowledge(String authenticatedPeer, String messageId) {
        id(authenticatedPeer); id(messageId);
        return db.delete("outbox","peer=? AND id=?",new String[]{authenticatedPeer,messageId}) != 0;
    }
    /** Network ACKs also bind the exact expiry/priority/payload, so an old ACK
     * cannot erase a newly queued message that accidentally reused an ID.
     */
    public synchronized boolean acknowledgeVerified(String peer, String messageId, byte[] digest) throws Exception {
        id(peer); id(messageId);
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("Invalid ACK digest");
        db.beginTransaction();
        try {
            try (Cursor row = db.rawQuery("SELECT peer,id,payload,expires,priority FROM outbox WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if (!row.moveToFirst()) { db.setTransactionSuccessful(); return false; }
                Message message = new Message(row);
                byte[] envelope = java.nio.ByteBuffer.allocate(9+message.payload.length).putLong(message.expiresAt)
                    .put((byte)(message.highPriority ? 1 : 0)).put(message.payload).array();
                byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(envelope);
                if (!java.security.MessageDigest.isEqual(expected,digest)) throw new IllegalArgumentException("ACK content mismatch");
            }
            db.delete("outbox","peer=? AND id=?",new String[]{peer,messageId}); db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    public synchronized int expire(long now) { time(now); return db.delete("outbox","expires<=?",new String[]{Long.toString(now)}); }
    @Override public synchronized void close() { db.close(); }
}
