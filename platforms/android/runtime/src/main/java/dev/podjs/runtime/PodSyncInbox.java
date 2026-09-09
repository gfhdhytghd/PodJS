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

/** Durable authenticated-message receipts. Applied entries survive until expiry.
 * Business side effects must also be idempotent by peer/message ID: a crash between
 * the side effect and markApplied can still redeliver the pending message.
 */
public final class PodSyncInbox implements Closeable {
    public enum Delivery { PENDING, APPLIED, EXPIRED }
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
        /** Binds an explicit acknowledgement to these exact received bytes. */
        public byte[] acknowledgementToken() throws Exception {
            byte[] envelope=java.nio.ByteBuffer.allocate(9+payload.length).putLong(expiresAt).put((byte)(highPriority?1:0)).put(payload).array();
            return java.security.MessageDigest.getInstance("SHA-256").digest(envelope);
        }
    }
    public PodSyncInbox(Context context, String appId) throws IOException {
        id(appId); File root = new File(context.getNoBackupFilesDir(),"podjs-inbox");
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Inbox unavailable");
        db = SQLiteDatabase.openOrCreateDatabase(new File(root,appId + ".sqlite"),null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS inbox (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL, expires INTEGER NOT NULL, priority INTEGER NOT NULL, cost INTEGER NOT NULL, applied INTEGER NOT NULL DEFAULT 0, UNIQUE(peer,id))");
        } catch (RuntimeException error) { db.close(); throw error; }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid identity");
    }
    private static void time(long value) {
        if (value < 0 || value > 9007199254740991L) throw new IllegalArgumentException("Invalid timestamp");
    }
    /** Peer is taken from the authenticated connection, never from untrusted payload. */
    public synchronized Delivery receive(String peer, String messageId, byte[] payload, long expiresAt, boolean high, long now) {
        id(peer); id(messageId); time(expiresAt); time(now);
        if (payload == null || payload.length > PodSyncOutbox.MAX_PAYLOAD) throw new IllegalArgumentException("Oversized message");
        if (expiresAt <= now) return Delivery.EXPIRED;
        byte[] stable = payload.clone();
        db.beginTransaction();
        try {
            try (Cursor row = db.rawQuery("SELECT peer,id,payload,expires,priority,applied FROM inbox WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if (row.moveToFirst()) {
                    Message prior = new Message(row);
                    if (!Arrays.equals(prior.payload,stable) || prior.expiresAt != expiresAt || prior.highPriority != high)
                        throw new IllegalArgumentException("Message identity reused with different content");
                    Delivery delivery = row.getInt(5) == 0 ? Delivery.PENDING : Delivery.APPLIED;
                    db.setTransactionSuccessful(); return delivery;
                }
            }
            db.delete("inbox","expires<=?",new String[]{Long.toString(now)});
            int cost = stable.length + peer.length() + messageId.length() + 128;
            long count = DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM inbox",null);
            long used = DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(cost),0) FROM inbox",null);
            if (count >= PodSyncOutbox.MAX_MESSAGES || used + cost > PodSyncOutbox.MAX_BYTES)
                throw new IllegalStateException("Message inbox full");
            ContentValues values = new ContentValues(); values.put("peer",peer); values.put("id",messageId);
            values.put("payload",stable); values.put("expires",expiresAt); values.put("priority",high ? 1 : 0); values.put("cost",cost);
            db.insertOrThrow("inbox",null,values); db.setTransactionSuccessful(); return Delivery.PENDING;
        } finally { db.endTransaction(); }
    }
    /** Successful return permits an applied ACK; failure must never be acknowledged. */
    public synchronized void markApplied(String peer, String messageId) {
        id(peer); id(messageId);
        ContentValues values = new ContentValues(); values.put("applied",1);
        if (db.update("inbox",values,"peer=? AND id=?",new String[]{peer,messageId}) == 0)
            throw new IllegalArgumentException("Unknown inbox message");
    }
    /** Explicit application ACK. A stale UI delivery cannot acknowledge reused
     * identity with different content. Applied receipts survive for peer retries. */
    public synchronized Message acknowledge(String peer,String messageId,byte[] token,long now) throws Exception {
        id(peer); id(messageId); time(now);
        if(token==null || token.length!=32) throw new IllegalArgumentException("Invalid delivery token");
        byte[] stable=token.clone(); db.beginTransaction();
        try {
            Message message;
            try(Cursor row=db.rawQuery("SELECT peer,id,payload,expires,priority FROM inbox WHERE peer=? AND id=?",new String[]{peer,messageId})) {
                if(!row.moveToFirst()) throw new IOException("Unknown message delivery"); message=new Message(row);
            }
            if(message.expiresAt<=now) throw new IOException("Message delivery expired");
            if(!java.security.MessageDigest.isEqual(stable,message.acknowledgementToken())) throw new IOException("Message delivery changed");
            markApplied(peer,messageId); db.setTransactionSuccessful(); return message;
        } finally { db.endTransaction(); }
    }
    public synchronized List<Message> pending(long now, int limit) {
        return pendingPage(now,limit,0);
    }
    synchronized List<Message> pendingPage(long now, int limit, int offset) {
        time(now); if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch limit");
        if(offset<0 || offset>PodSyncOutbox.MAX_MESSAGES) throw new IllegalArgumentException("Invalid batch offset");
        List<Message> result = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT peer,id,payload,expires,priority FROM inbox WHERE applied=0 AND expires>? ORDER BY priority DESC,ordinal ASC LIMIT ? OFFSET ?",new String[]{Long.toString(now),Integer.toString(limit),Integer.toString(offset)})) {
            while (rows.moveToNext()) result.add(new Message(rows));
        }
        return result;
    }
    public synchronized int expire(long now) { time(now); return db.delete("inbox","expires<=?",new String[]{Long.toString(now)}); }
    @Override public synchronized void close() { db.close(); }
}
