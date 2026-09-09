package dev.podjs.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only incoming file consent and recovery journal. An authenticated offer
 * records metadata, not consent. Only the local host calls accept. The ledger
 * lock spans intent -> native side effect -> committed result across processes.
 */
public final class PodSyncIncomingFiles implements Closeable {
    private final Context context;
    private final String storageApp;
    private final SQLiteDatabase db;
    private final File lockPath;
    private boolean closed;
    private interface Operation<T> { T run() throws Exception; }
    public static final class Offer {
        public final String peerId, transferId, phase;
        public final JSONObject manifest;
        private Offer(Cursor row) throws Exception {
            peerId=row.getString(0); transferId=row.getString(1); phase=row.getString(2); manifest=normalize(new JSONObject(row.getString(3)));
            identity(peerId);
            if (!transferId.equals(manifest.getString("transfer_id")) || !java.util.Arrays.asList("offered","accepting","accepted","complete","cancelling","cancelled").contains(phase))
                throw new IOException("Corrupt incoming file journal");
        }
    }
    public PodSyncIncomingFiles(Context context, String appId) throws Exception {
        this.context=context; storageApp=storageAppId(appId);
        File directory=new File(context.getNoBackupFilesDir(),"podjs-file-offers");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("File journal unavailable");
        lockPath=new File(directory,storageApp+".lock");
        db=SQLiteDatabase.openOrCreateDatabase(new File(directory,storageApp+".sqlite"),null);
        try {
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE IF NOT EXISTS offers (peer TEXT NOT NULL, id TEXT NOT NULL, phase TEXT NOT NULL, manifest TEXT NOT NULL, PRIMARY KEY(peer,id))");
            // Opening must remain possible when an interrupted acceptance is
            // out of quota, so callers can cancel it instead of being locked out.
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,manifest FROM offers",null)) {
                int count=0; while(rows.moveToNext()) { new Offer(rows); if(++count>128) throw new IOException("Incoming file journal quota exceeded"); }
            }
        } catch (Exception error) { db.close(); throw error; }
    }
    private static void identity(String value) {
        if (value==null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid sync identity");
    }
    private static void transfer(String value) {
        if (value==null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid transfer ID");
    }
    private static String hash(String value) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder(); for(byte b:digest) result.append(String.format(java.util.Locale.ROOT,"%02x",b & 255)); return result.toString();
    }
    static String storageAppId(String appId) throws Exception { identity(appId); return "app-"+hash(appId); }
    static JSONObject normalize(JSONObject input) throws Exception {
        if (input==null || input.length()!=5 || !(input.get("transfer_id") instanceof String) || !(input.get("sha256") instanceof String) || !(input.get("mime") instanceof String) || !(input.get("size") instanceof Number))
            throw new IllegalArgumentException("Invalid file manifest");
        String id=input.getString("transfer_id"), digest=input.getString("sha256"), mime=input.getString("mime"); transfer(id);
        Number number=(Number)input.get("size"); long size=number.longValue();
        if(size<0 || size>16L*1024*1024 || number.doubleValue()!=size || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid file manifest");
        java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(mime));
        if(encoded.remaining()>128) throw new IllegalArgumentException("Invalid file MIME");
        for(int n=0;n<mime.length();n++) if(Character.isISOControl(mime.charAt(n))) throw new IllegalArgumentException("Invalid file MIME");
        JSONArray hashes=input.getJSONArray("chunk_hashes"), frozen=new JSONArray();
        if(hashes.length()!=(size+65535)/65536) throw new IllegalArgumentException("Invalid file chunk count");
        for(int n=0;n<hashes.length();n++) {
            if(!(hashes.get(n) instanceof String) || !hashes.getString(n).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid file chunk hash");
            frozen.put(hashes.getString(n));
        }
        return new JSONObject().put("transfer_id",id).put("size",size).put("sha256",digest).put("chunk_hashes",frozen).put("mime",mime);
    }
    private synchronized <T> T locked(Operation<T> operation) throws Exception {
        if(closed) throw new IOException("File journal closed");
        try(RandomAccessFile file=new RandomAccessFile(lockPath,"rw")) {
            FileLock acquired;
            try { acquired=file.getChannel().tryLock(); } catch(OverlappingFileLockException error) { throw new IOException("File journal busy",error); }
            if(acquired==null) throw new IOException("File journal busy");
            try(FileLock lock=acquired) { return operation.run(); }
        }
    }
    private Offer read(String peer, String id) throws Exception {
        try(Cursor row=db.rawQuery("SELECT peer,id,phase,manifest FROM offers WHERE peer=? AND id=?",new String[]{peer,id})) { return row.moveToFirst()?new Offer(row):null; }
    }
    private Offer require(String peer, String id) throws Exception {
        identity(peer); transfer(id); Offer offer=read(peer,id); if(offer==null) throw new IOException("Unknown incoming file"); return offer;
    }
    private void phase(Offer offer, String phase) throws IOException {
        ContentValues values=new ContentValues(); values.put("phase",phase);
        if(db.update("offers",values,"peer=? AND id=?",new String[]{offer.peerId,offer.transferId})!=1) throw new IOException("Incoming file journal missing");
    }
    private JSONObject nativeCommand(String peer, JSONObject command) throws Exception {
        try(PodSyncFileReceiver receiver=new PodSyncFileReceiver(context,storageApp,"peer-"+hash(peer))) {
            JSONObject reply=new JSONObject(receiver.command(command.toString()));
            if(!reply.getBoolean("ok")) throw new IOException("Native incoming file operation failed");
            return reply.getJSONObject("value");
        }
    }
    private JSONObject command(String method, String id) throws Exception { return new JSONObject().put("method",method).put("transfer_id",id); }
    private void recoverOne(Offer offer) throws Exception {
        if(offer.phase.equals("accepting")) {
            nativeCommand(offer.peerId,new JSONObject().put("method","offer").put("manifest",offer.manifest)); phase(offer,"accepted");
        } else if(offer.phase.equals("cancelling")) {
            nativeCommand(offer.peerId,command("cancel",offer.transferId)); phase(offer,"cancelled");
        }
    }
    public void recover() throws Exception {
        locked(() -> { ArrayList<Offer> offers=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,manifest FROM offers",null)) { while(rows.moveToNext()) offers.add(new Offer(rows)); }
            if(offers.size()>128) throw new IOException("Incoming file journal quota exceeded");
            // Release cancelled reservations before retrying acceptances that
            // may be waiting for exactly that application quota.
            for(Offer offer:offers) if(offer.phase.equals("cancelling")) recoverOne(offer);
            for(Offer offer:offers) if(offer.phase.equals("accepting")) recoverOne(offer); return null;
        });
    }
    /** Authenticated peer only; no native receiver is opened for a new offer. */
    public Offer offer(String peer, JSONObject manifest) throws Exception {
        identity(peer); JSONObject frozen=normalize(manifest); String id=frozen.getString("transfer_id");
        return locked(() -> {
            Offer old=read(peer,id);
            if(old!=null) {
                if(!old.manifest.toString().equals(frozen.toString())) throw new IOException("Transfer identity reused with different manifest");
                return old;
            }
            if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM offers",null)>=128) throw new IOException("Incoming offer quota exceeded");
            ContentValues values=new ContentValues(); values.put("peer",peer); values.put("id",id); values.put("phase","offered"); values.put("manifest",frozen.toString());
            db.insertOrThrow("offers",null,values); return require(peer,id);
        });
    }
    /** Local host consent, never a remote command that self-approves its offer. */
    public Offer accept(String peer, String id) throws Exception {
        return locked(() -> {
            Offer offer=require(peer,id);
            if(offer.phase.equals("cancelled") || offer.phase.equals("cancelling")) throw new IOException("Incoming file cancelled");
            if(offer.phase.equals("offered")) { phase(offer,"accepting"); offer=require(peer,id); }
            recoverOne(offer); return require(peer,id);
        });
    }
    public Offer cancel(String peer, String id) throws Exception {
        return cancel(peer,id,true);
    }
    Offer cancelUnfinished(String peer,String id) throws Exception { return cancel(peer,id,false); }
    private Offer cancel(String peer,String id,boolean removeCompleted) throws Exception {
        return locked(() -> {
            Offer offer=require(peer,id); if(offer.phase.equals("cancelled")) return offer;
            if(!removeCompleted && offer.phase.equals("complete")) throw new IllegalStateException("Completed file must be removed explicitly");
            if(offer.phase.equals("offered")) { phase(offer,"cancelled"); return require(peer,id); }
            phase(offer,"cancelling"); recoverOne(require(peer,id)); return require(peer,id);
        });
    }
    private Offer accepted(String peer, String id) throws Exception {
        Offer offer=require(peer,id); recoverOne(offer); offer=require(peer,id);
        if(!offer.phase.equals("accepted") && !offer.phase.equals("complete")) throw new IOException("Incoming file not accepted"); return offer;
    }
    public JSONArray missing(String peer, String id) throws Exception {
        return locked(() -> { Offer offer=accepted(peer,id); JSONArray missing=nativeCommand(peer,command("missing",id)).getJSONArray("missing");
            if(offer.phase.equals("complete") && missing.length()!=0) phase(offer,"accepted"); return missing;
        });
    }
    public void chunk(String peer, String id, int index, byte[] bytes) throws Exception {
        if(bytes==null || bytes.length>65536 || index<0) throw new IllegalArgumentException("Invalid file chunk"); byte[] frozen=bytes.clone();
        locked(() -> { accepted(peer,id); nativeCommand(peer,command("chunk",id).put("index",index).put("data_base64",Base64.encodeToString(frozen,Base64.NO_WRAP))); return null; });
    }
    public Offer finish(String peer, String id) throws Exception {
        return locked(() -> { Offer offer=accepted(peer,id); nativeCommand(peer,command("finish",id)); phase(offer,"complete"); return require(peer,id); });
    }
    /** Host-private access; the path is never a public transfer/wire status. */
    public File completedFile(String peer, String id) throws Exception {
        return locked(() -> { Offer offer=require(peer,id); if(!offer.phase.equals("complete")) throw new IOException("Incoming file not complete");
            return new File(nativeCommand(peer,command("finish",id)).getString("path"));
        });
    }
    public Offer status(String peer, String id) throws Exception { return locked(() -> require(peer,id)); }
    /** Does not grant consent or recover accepting/cancelling intents. */
    JSONObject serviceStatus(String peer,String id) throws Exception {
        return locked(() -> {
            Offer offer=require(peer,id); long total=offer.manifest.getLong("size"), received=0;
            boolean known=offer.phase.equals("accepted") || offer.phase.equals("complete");
            String state=offer.phase.equals("offered")?"offered":offer.phase.equals("cancelled")?"cancelled":"transferring";
            if(known) {
                JSONArray missing=nativeCommand(peer,command("missing",id)).getJSONArray("missing");
                long absent=0; java.util.HashSet<Integer> seen=new java.util.HashSet<>();
                int count=offer.manifest.getJSONArray("chunk_hashes").length();
                for(int n=0;n<missing.length();n++) {
                    Object raw=missing.get(n);
                    if(!(raw instanceof Number)) throw new IOException("Invalid missing chunk index");
                    int index=((Number)raw).intValue();
                    if(((Number)raw).doubleValue()!=index || index<0 || index>=count || !seen.add(index)) throw new IOException("Invalid missing chunk index");
                    absent+=Math.min(65536L,total-index*65536L);
                }
                received=total-absent;
                if(offer.phase.equals("complete")) state=missing.length()==0?"complete":"failed";
            }
            return new JSONObject().put("transferId",id).put("state",state).put("totalBytes",total)
                .put("receivedBytes",received).put("progressKnown",known);
        });
    }
    java.util.List<Offer> findTransfer(String id) throws Exception {
        identity(id); return locked(() -> {
            ArrayList<Offer> result=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,manifest FROM offers WHERE id=? ORDER BY peer LIMIT 2",new String[]{id})) {
                while(rows.moveToNext()) result.add(new Offer(rows));
            }
            return result;
        });
    }
    java.util.List<String> transferIdsAfter(String after) throws Exception {
        return locked(() -> {
            ArrayList<String> result=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT DISTINCT id FROM offers WHERE id>? ORDER BY id LIMIT 64",new String[]{after})) {
                while(rows.moveToNext()) result.add(rows.getString(0));
            }
            return result;
        });
    }
    /** Host-only recovery inventory, including accepted and terminal transfers.
     * Does not recover intents, open native receivers or imply fresh consent. */
    public java.util.List<Offer> list() throws Exception {
        return locked(() -> {
            ArrayList<Offer> result=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,manifest FROM offers ORDER BY peer,id",null)) {
                while(rows.moveToNext()) { result.add(new Offer(rows)); if(result.size()>128) throw new IOException("Incoming offer quota exceeded"); }
            }
            return java.util.Collections.unmodifiableList(result);
        });
    }
    /** Local host only: rediscover consent prompts after process restart.
     * Does not recover intents or open receivers; returned manifests are copies.
     * Never expose this cross-peer listing through a remote sync command.
     */
    public java.util.List<Offer> pendingConsent() throws Exception {
        return locked(() -> {
            ArrayList<Offer> result=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT peer,id,phase,manifest FROM offers WHERE phase='offered' ORDER BY peer,id",null)) {
                while(rows.moveToNext()) {
                    result.add(new Offer(rows));
                    if(result.size()>128) throw new IOException("Incoming offer quota exceeded");
                }
            }
            return java.util.Collections.unmodifiableList(result);
        });
    }
    @Override public synchronized void close() { if(!closed) { closed=true; db.close(); } }
}
