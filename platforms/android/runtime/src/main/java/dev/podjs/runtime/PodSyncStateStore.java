package dev.podjs.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-owned replicated JSON state. Every mutation reloads inside a SQLite
 * transaction, including logical clock and authenticated-peer receive cursor.
 * No cache, transport, polling thread, guest identity or implicit ACK lives here.
 */
public final class PodSyncStateStore implements Closeable {
    private static final long MAX_COUNTER = 9007199254740991L;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    private final SQLiteDatabase db;
    private final String deviceId;

    public PodSyncStateStore(Context context, String appId, String deviceId) throws Exception {
        PodTakeover.requireReady(context);
        id(appId); id(deviceId); this.deviceId = deviceId;
        File root = new File(context.getNoBackupFilesDir(), "podjs-state");
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("State storage unavailable");
        db = SQLiteDatabase.openOrCreateDatabase(new File(root, appId + ".sqlite"), null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS snapshot (slot INTEGER PRIMARY KEY CHECK(slot=1), device TEXT NOT NULL, data TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS outgoing (peer TEXT PRIMARY KEY, acked INTEGER NOT NULL DEFAULT 0, sent_hash BLOB, cycle TEXT, cycle_hash BLOB, position INTEGER NOT NULL DEFAULT 0, pending BLOB, batch_id TEXT)");
            db.execSQL("CREATE TABLE IF NOT EXISTS incoming_batches (peer TEXT PRIMARY KEY, from_cursor INTEGER NOT NULL, to_cursor INTEGER NOT NULL, message_id TEXT NOT NULL, digest BLOB NOT NULL)");
            ContentValues initial = new ContentValues(); initial.put("slot", 1); initial.put("device", deviceId);
            initial.put("data", new JSONObject().put("version", 1).put("clock", 0).put("entries", new JSONArray()).put("cursors", new JSONObject()).toString());
            db.insertWithOnConflict("snapshot", null, initial, SQLiteDatabase.CONFLICT_IGNORE);
            load();
        } catch (Exception error) { db.close(); throw error; }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid state identity");
    }
    private static long counter(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid state counter");
        Number number = (Number)value; long result = number.longValue();
        if (result < 0 || result > MAX_COUNTER || number.doubleValue() != (double)result)
            throw new IllegalArgumentException("Invalid state counter");
        return result;
    }
    private static String quote(String value) {
        // Android's JSON encoder can retain lone UTF-16 surrogates which the
        // UTF-8 encoder would silently replace. Reject before persisting/ACKing.
        for (int n = 0; n < value.length(); n++) {
            char character = value.charAt(n);
            if (Character.isHighSurrogate(character)) {
                if (++n >= value.length() || !Character.isLowSurrogate(value.charAt(n))) throw new IllegalArgumentException("Invalid Unicode state string");
            } else if (Character.isLowSurrogate(character)) throw new IllegalArgumentException("Invalid Unicode state string");
        }
        return JSONObject.quote(value);
    }
    static byte[] encodeServiceJson(Object value) throws Exception { return canonical(value,0).getBytes(StandardCharsets.UTF_8); }
    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("State value nesting limit");
        if (value == JSONObject.NULL) return "null";
        if (value instanceof String) return quote((String)value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            if (!Double.isFinite(((Number)value).doubleValue())) throw new IllegalArgumentException("Non-finite state value");
            double number = ((Number)value).doubleValue();
            return number == 0 ? "0" : JSONObject.numberToString(number);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value; StringBuilder out = new StringBuilder("[");
            for (int n = 0; n < array.length(); n++) { if (n > 0) out.append(','); out.append(canonical(array.get(n), depth + 1)); }
            return out.append(']').toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value; ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            StringBuilder out = new StringBuilder("{"); boolean first = true;
            for (String key : keys) {
                if (!first) out.append(','); first = false;
                out.append(quote(key)).append(':').append(canonical(object.get(key), depth + 1));
            }
            return out.append('}').toString();
        }
        throw new IllegalArgumentException("State values must be JSON data");
    }
    private static void validateEntry(JSONObject entry) throws Exception {
        if (!(entry.get("key") instanceof String) || !(entry.get("deviceId") instanceof String) || !(entry.get("deleted") instanceof Boolean))
            throw new IllegalArgumentException("Invalid state entry");
        id(entry.getString("key")); id(entry.getString("deviceId")); counter(entry.get("counter"));
        Object value = entry.get("value");
        if (entry.getBoolean("deleted") && value != JSONObject.NULL) throw new IllegalArgumentException("Invalid tombstone");
        if (canonical(value, 0).length() > 65536) throw new IllegalArgumentException("State value too large");
    }
    private JSONObject load() throws Exception {
        try (Cursor row = db.rawQuery("SELECT device,data FROM snapshot WHERE slot=1", null)) {
            if (!row.moveToFirst() || !deviceId.equals(row.getString(0))) throw new IOException("State device identity mismatch");
            String raw = row.getString(1);
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw new IOException("State snapshot too large");
            JSONObject snapshot = new JSONObject(raw);
            if (counter(snapshot.get("version")) != 1) throw new IOException("Unsupported state version");
            long clock = counter(snapshot.get("clock")); JSONArray entries = snapshot.getJSONArray("entries");
            if (entries.length() > 10000) throw new IOException("State entry quota exceeded");
            java.util.HashSet<String> keys = new java.util.HashSet<>();
            for (int n = 0; n < entries.length(); n++) {
                JSONObject entry = entries.getJSONObject(n); validateEntry(entry);
                if (!keys.add(entry.getString("key")) || counter(entry.get("counter")) > clock) throw new IOException("Corrupt state snapshot");
            }
            JSONObject cursors = snapshot.getJSONObject("cursors"); Iterator<String> peers = cursors.keys();
            while (peers.hasNext()) { String peer = peers.next(); id(peer); counter(cursors.get(peer)); }
            return snapshot;
        }
    }
    private void save(JSONObject snapshot) throws Exception {
        String raw = snapshot.toString();
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw new IOException("State storage quota exceeded");
        ContentValues values = new ContentValues(); values.put("data", raw);
        if (db.update("snapshot", values, "slot=1 AND device=?", new String[]{deviceId}) != 1) throw new IOException("State snapshot missing");
    }
    /** Detached snapshot; callers cannot mutate stored values through this object. */
    public synchronized JSONObject snapshot() throws Exception { return load(); }
    /** Java null means absent/tombstoned; JSONObject.NULL means a stored JSON null. */
    public synchronized Object get(String key) throws Exception {
        id(key); JSONArray entries = load().getJSONArray("entries");
        for (int n = 0; n < entries.length(); n++) {
            JSONObject entry = entries.getJSONObject(n);
            if (key.equals(entry.getString("key"))) return entry.getBoolean("deleted") ? null : entry.get("value");
        }
        return null;
    }
    public synchronized JSONObject set(String key, Object value) throws Exception { return write(key, value == null ? JSONObject.NULL : value, false); }
    public synchronized JSONObject delete(String key) throws Exception { return write(key, JSONObject.NULL, true); }
    private JSONObject write(String key, Object value, boolean deleted) throws Exception {
        id(key); canonical(value, 0);
        db.beginTransaction();
        try {
            JSONObject snapshot = load(); long clock = counter(snapshot.get("clock"));
            if (clock == MAX_COUNTER) throw new IllegalStateException("State clock exhausted");
            JSONObject entry = new JSONObject().put("key", key).put("value", value).put("deleted", deleted).put("counter", clock + 1).put("deviceId", deviceId);
            validateEntry(entry); merge(snapshot, new JSONArray().put(entry)); save(snapshot);
            db.setTransactionSuccessful(); return new JSONObject(entry.toString());
        } finally { db.endTransaction(); }
    }
    /** Peer must come from an authenticated connection. A successful return,
     * including endTransaction, permits acknowledging this persistent cursor. */
    public synchronized long receive(String peer, long from, long to, JSONArray entries) throws Exception {
        id(peer); counter(from); counter(to);
        if (to <= from || entries == null || entries.length() > 512) throw new IllegalArgumentException("Invalid state batch");
        // Validate before JSON serialization can erase non-JSON types.
        for (int n = 0; n < entries.length(); n++) validateEntry(entries.getJSONObject(n));
        JSONArray frozen = new JSONArray(entries.toString());
        db.beginTransaction();
        try {
            JSONObject snapshot = load(); JSONObject cursors = snapshot.getJSONObject("cursors");
            long cursor = cursors.has(peer) ? counter(cursors.get(peer)) : 0;
            if (to <= cursor) { db.setTransactionSuccessful(); return cursor; }
            if (from != cursor) throw new IllegalArgumentException("State sequence gap; request replay");
            merge(snapshot, frozen); cursors.put(peer, to); save(snapshot); db.setTransactionSuccessful(); return to;
        } finally { db.endTransaction(); }
    }
    public static final class Batch {
        public final String messageId;
        public final long from, to;
        public final byte[] payload;
        private Batch(String messageId, byte[] payload, long from) throws Exception {
            id(messageId); this.messageId = messageId; this.payload = payload.clone();
            JSONObject body = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            this.from = counter(body.get("from")); this.to = counter(body.get("to"));
            if (payload.length > MAX_BATCH_BYTES || counter(body.get("version")) != 1 || this.from != from || to != from + 1 || body.getJSONArray("entries").length() > 512)
                throw new IOException("Corrupt pending state batch");
        }
        public byte[] digest() throws Exception { return hash(payload); }
    }
    public static final class Receipt {
        public final long cursor;
        public final byte[] digest;
        public final boolean duplicate;
        private Receipt(long cursor, byte[] digest, boolean duplicate) { this.cursor = cursor; this.digest = digest.clone(); this.duplicate = duplicate; }
    }
    /** Wire entrypoint: bind the last applied batch identity and exact bytes to
     * its cursor in the same transaction as state. One outstanding batch per
     * peer makes the last receipt sufficient for legitimate lost-ACK replay.
     */
    public synchronized Receipt receiveBatch(String peer, String messageId, byte[] payload) throws Exception {
        id(peer); id(messageId);
        if (payload == null || payload.length > MAX_BATCH_BYTES) throw new IllegalArgumentException("Invalid state batch size");
        byte[] frozen = payload.clone(); byte[] digest = hash(frozen);
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(frozen)).toString();
        JSONObject body = new JSONObject(json); long from = counter(body.get("from")), to = counter(body.get("to"));
        JSONArray entries = body.getJSONArray("entries");
        if (counter(body.get("version")) != 1 || to != from + 1 || entries.length() > 512) throw new IllegalArgumentException("Invalid state batch version or range");
        for (int n = 0; n < entries.length(); n++) validateEntry(entries.getJSONObject(n));
        db.beginTransaction();
        try {
            JSONObject snapshot = load(); JSONObject cursors = snapshot.getJSONObject("cursors");
            long cursor = cursors.has(peer) ? counter(cursors.get(peer)) : 0;
            try (Cursor row = db.rawQuery("SELECT from_cursor,to_cursor,message_id,digest FROM incoming_batches WHERE peer=?",new String[]{peer})) {
                boolean prior = row.moveToFirst();
                if (to <= cursor) {
                    if (!prior || row.getLong(0) != from || row.getLong(1) != to || to != cursor ||
                        !messageId.equals(row.getString(2)) || !java.security.MessageDigest.isEqual(digest,row.getBlob(3)))
                        throw new IllegalArgumentException("Conflicting or stale state batch replay");
                    db.setTransactionSuccessful(); return new Receipt(to,digest,true);
                }
                if (from != cursor) throw new IllegalArgumentException("State sequence gap; request replay");
                if (prior && messageId.equals(row.getString(2))) throw new IllegalArgumentException("State batch identity reused");
                if (!prior && DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM incoming_batches",null) >= 128)
                    throw new IllegalStateException("State receipt quota exceeded");
            }
            merge(snapshot,entries); cursors.put(peer,to); save(snapshot);
            ContentValues receipt = new ContentValues(); receipt.put("peer",peer); receipt.put("from_cursor",from); receipt.put("to_cursor",to);
            receipt.put("message_id",messageId); receipt.put("digest",digest);
            if (db.insertWithOnConflict("incoming_batches",null,receipt,SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IOException("State receipt write failed");
            db.setTransactionSuccessful(); return new Receipt(to,digest,false);
        } finally { db.endTransaction(); }
    }
    private static byte[] hash(byte[] bytes) throws Exception { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
    /** Freeze a complete state cycle, then durably prepare its next bounded
     * batch before sending. Until ACK, every reopen returns the same ID/bytes.
     * null means this peer has acknowledged the current complete state.
     */
    public synchronized Batch prepare(String peer) throws Exception {
        id(peer); db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM outgoing WHERE peer=?",new String[]{peer}) == 0) {
                if (DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM outgoing",null) >= 128) throw new IllegalStateException("State peer quota exceeded");
                ContentValues initial = new ContentValues(); initial.put("peer",peer); db.insertOrThrow("outgoing",null,initial);
            }
            try (Cursor row = db.rawQuery("SELECT acked,sent_hash,cycle,cycle_hash,position,pending,batch_id FROM outgoing WHERE peer=?",new String[]{peer})) {
                if (!row.moveToFirst()) throw new IOException("State sender missing");
                long acked = counter(row.getLong(0));
                if (!row.isNull(5)) {
                    Batch batch = new Batch(row.getString(6),row.getBlob(5),acked); db.setTransactionSuccessful(); return batch;
                }
                String cycle = row.getString(2); int position = row.getInt(4); ContentValues update = new ContentValues();
                if (cycle == null) {
                    cycle = load().getJSONArray("entries").toString(); byte[] digest = hash(cycle.getBytes(StandardCharsets.UTF_8));
                    if (java.util.Arrays.equals(digest,row.getBlob(1))) { db.setTransactionSuccessful(); return null; }
                    long reserved = DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(length(CAST(cycle AS BLOB))),0) FROM outgoing",null);
                    // Do not replace unacknowledged cycles to admit another peer.
                    if (reserved + cycle.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024) throw new IllegalStateException("State pending quota exceeded");
                    update.put("cycle",cycle); update.put("cycle_hash",digest); update.put("position",0); position = 0;
                }
                if (acked == MAX_COUNTER) throw new IllegalStateException("State send cursor exhausted");
                JSONArray all = new JSONArray(cycle);
                if (position < 0 || position > all.length()) throw new IOException("Corrupt state sender position");
                JSONArray entries = new JSONArray(); int bytes = 0;
                // Reserve envelope overhead; each value already fits the 64K
                // UTF-16 quota, including its escaped representation.
                while (position + entries.length() < all.length() && entries.length() < 512) {
                    JSONObject entry = all.getJSONObject(position + entries.length()); validateEntry(entry);
                    int size = entry.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                    if (bytes + size > MAX_BATCH_BYTES - 256) break;
                    entries.put(entry); bytes += size;
                }
                if (entries.length() == 0 && position < all.length()) throw new IOException("State entry cannot fit wire batch");
                byte[] payload = new JSONObject().put("version",1).put("from",acked).put("to",acked+1).put("entries",entries).toString().getBytes(StandardCharsets.UTF_8);
                String messageId = java.util.UUID.randomUUID().toString();
                Batch batch = new Batch(messageId,payload,acked);
                update.put("pending",payload); update.put("batch_id",messageId);
                if (db.update("outgoing",update,"peer=?",new String[]{peer}) != 1) throw new IOException("State sender missing");
                db.setTransactionSuccessful(); return batch;
            }
        } finally { db.endTransaction(); }
    }
    /** Call only for an authenticated ACK from peer. Stale/wrong IDs, digests
     * and cursors cannot acknowledge a later batch or a different recipient.
     */
    public synchronized boolean acknowledge(String peer, String messageId, long to, byte[] digest) throws Exception {
        id(peer); id(messageId); counter(to);
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("Invalid state ACK digest");
        byte[] frozen = digest.clone(); db.beginTransaction();
        try (Cursor row = db.rawQuery("SELECT acked,cycle,cycle_hash,position,pending,batch_id FROM outgoing WHERE peer=?",new String[]{peer})) {
            if (!row.moveToFirst() || row.isNull(4)) { db.setTransactionSuccessful(); return false; }
            Batch batch = new Batch(row.getString(5),row.getBlob(4),counter(row.getLong(0)));
            if (!batch.messageId.equals(messageId) || batch.to != to || !java.security.MessageDigest.isEqual(batch.digest(),frozen)) {
                db.setTransactionSuccessful(); return false;
            }
            JSONArray all = new JSONArray(row.getString(1));
            JSONArray entries = new JSONObject(new String(batch.payload,StandardCharsets.UTF_8)).getJSONArray("entries");
            int position = row.getInt(3), next = position + entries.length();
            if (position < 0 || next < position || next > all.length()) throw new IOException("Corrupt state sender position");
            ContentValues update = new ContentValues(); update.put("acked",to); update.putNull("pending"); update.putNull("batch_id"); update.put("position",next);
            if (next == all.length()) {
                update.put("sent_hash",row.getBlob(2)); update.putNull("cycle"); update.putNull("cycle_hash"); update.put("position",0);
            }
            if (db.update("outgoing",update,"peer=?",new String[]{peer}) != 1) throw new IOException("State sender missing");
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    /** Read-only evidence: true only when the peer acknowledged the exact
     * current local entries. Does not prepare a batch or advance any cursor.
     * This alone says nothing about changes still held offline by the peer. */
    public synchronized boolean currentStateAcknowledged(String peer) throws Exception {
        id(peer); db.beginTransaction();
        try (Cursor row=db.rawQuery("SELECT sent_hash,cycle,pending FROM outgoing WHERE peer=?",new String[]{peer})) {
            byte[] current=hash(load().getJSONArray("entries").toString().getBytes(StandardCharsets.UTF_8));
            boolean acknowledged=row.moveToFirst() && !row.isNull(0) && row.isNull(1) && row.isNull(2)
                && java.security.MessageDigest.isEqual(current,row.getBlob(0));
            db.setTransactionSuccessful(); return acknowledged;
        } finally { db.endTransaction(); }
    }
    private static void merge(JSONObject snapshot, JSONArray incoming) throws Exception {
        TreeMap<String, JSONObject> table = new TreeMap<>(); JSONArray saved = snapshot.getJSONArray("entries");
        for (int n = 0; n < saved.length(); n++) { JSONObject entry = saved.getJSONObject(n); table.put(entry.getString("key"), entry); }
        long clock = counter(snapshot.get("clock"));
        for (int n = 0; n < incoming.length(); n++) {
            JSONObject entry = incoming.getJSONObject(n); validateEntry(entry); String key = entry.getString("key");
            JSONObject prior = table.get(key); int order = 1;
            if (prior != null) {
                order = Long.compare(counter(entry.get("counter")), counter(prior.get("counter")));
                if (order == 0) order = entry.getString("deviceId").compareTo(prior.getString("deviceId"));
                if (order == 0 && (entry.getBoolean("deleted") != prior.getBoolean("deleted") || !canonical(entry.get("value"), 0).equals(canonical(prior.get("value"), 0))))
                    throw new IllegalArgumentException("Conflicting payload for same state revision");
            }
            if (order > 0) table.put(key, entry);
            clock = Math.max(clock, counter(entry.get("counter")));
        }
        if (table.size() > 10000) throw new IllegalStateException("State entry quota exceeded");
        JSONArray entries = new JSONArray(); for (JSONObject entry : table.values()) entries.put(entry);
        snapshot.put("clock", clock).put("entries", entries);
    }
    @Override public synchronized void close() { db.close(); }
}
