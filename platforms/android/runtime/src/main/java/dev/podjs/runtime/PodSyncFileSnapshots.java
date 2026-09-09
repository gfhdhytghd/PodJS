package dev.podjs.runtime;

import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only immutable outgoing file snapshots. Reuses the native verified file
 * store under the SAME app quota as incoming files, in a distinct peer namespace.
 * Caller owns the source file. No remote filename or path is accepted here.
 * Native chunk/assembly recovery applies; unfinished snapshots are discoverable
 * and can be explicitly discarded, never sent as complete source files.
 */
public final class PodSyncFileSnapshots {
    private static final String PEER="outgoing-snapshots";
    private final Context context;
    private final String storageApp;
    private final File root;
    public PodSyncFileSnapshots(Context context,String appId) throws Exception {
        this.context=context; storageApp=PodSyncIncomingFiles.storageAppId(appId);
        root=new File(new File(new File(context.getNoBackupFilesDir(),"podjs-sync"),storageApp),PEER);
    }
    private PodSyncFileReceiver open() throws IOException { return new PodSyncFileReceiver(context,storageApp,PEER); }
    private static JSONObject command(PodSyncFileReceiver receiver,JSONObject command) throws Exception {
        JSONObject reply=new JSONObject(receiver.command(command.toString()));
        if(!reply.getBoolean("ok")) throw new IOException("Outgoing snapshot operation failed");
        return reply.getJSONObject("value");
    }
    private static JSONObject operation(String method,String id) throws Exception { return new JSONObject().put("method",method).put("transfer_id",id); }
    private static void identity(String id) { if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid snapshot ID"); }
    private static String hex(byte[] data) { StringBuilder result=new StringBuilder(); for(byte b:data) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return result.toString(); }
    private static int readChunk(FileChannel input,byte[] buffer) throws IOException {
        ByteBuffer chunk=ByteBuffer.wrap(buffer);
        while(chunk.hasRemaining()) { int count=input.read(chunk); if(count<0) break; if(count==0) throw new IOException("Source read made no progress"); }
        return chunk.position();
    }
    /** Two bounded streaming passes on one open file descriptor. Any source
     * mutation that changes bytes between passes fails native chunk/final hashes.
     * Success no longer depends on the source path, inode or its future contents.
     */
    public JSONObject create(File source,String mime) throws Exception {
        if(source==null || !Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException("Snapshot source must be a regular file");
        try(FileChannel input=FileChannel.open(source.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            return create(input,mime);
        }
    }
    /** Caller retains the already-authorized descriptor; no path is reopened. */
    JSONObject create(FileChannel input,String mime) throws Exception {
        String id=UUID.randomUUID().toString();
        {
            input.position(0);
            if(input.size()>16L*1024*1024) throw new IOException("Snapshot source too large");
            MessageDigest total=MessageDigest.getInstance("SHA-256"); JSONArray hashes=new JSONArray();
            byte[] bytes=new byte[65536]; long size=0; int count;
            while((count=readChunk(input,bytes))>0) {
                size+=count; if(size>16L*1024*1024) throw new IOException("Snapshot source too large");
                total.update(bytes,0,count); MessageDigest chunk=MessageDigest.getInstance("SHA-256"); chunk.update(bytes,0,count); hashes.put(hex(chunk.digest()));
            }
            JSONObject manifest=PodSyncIncomingFiles.normalize(new JSONObject().put("transfer_id",id).put("size",size)
                .put("sha256",hex(total.digest())).put("chunk_hashes",hashes).put("mime",mime));
            input.position(0);
            try(PodSyncFileReceiver receiver=open()) {
                boolean offered=false;
                try {
                    // Mark before IO: offer may be durable even if its response fails.
                    offered=true; command(receiver,new JSONObject().put("method","offer").put("manifest",manifest));
                    long copied=0; int index=0;
                    while((count=readChunk(input,bytes))>0) {
                        copied+=count; if(copied>size) throw new IOException("Snapshot source changed");
                        command(receiver,operation("chunk",id).put("index",index++).put("data_base64",Base64.encodeToString(bytes,0,count,Base64.NO_WRAP)));
                    }
                    if(copied!=size) throw new IOException("Snapshot source changed");
                    command(receiver,operation("finish",id)); return manifest;
                } catch(Exception error) {
                    if(offered) try { command(receiver,operation("cancel",id)); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }
    }
    private JSONObject manifest(String id) throws Exception {
        identity(id); File metadata=new File(new File(root,id),"manifest.json");
        if(!Files.isRegularFile(metadata.toPath(),LinkOption.NOFOLLOW_LINKS) || metadata.length()>32768) throw new IOException("Snapshot metadata unavailable");
        JSONObject manifest=PodSyncIncomingFiles.normalize(new JSONObject(new String(Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8)));
        if(!id.equals(manifest.getString("transfer_id"))) throw new IOException("Snapshot identity mismatch"); return manifest;
    }
    /** Verified complete manifest; does not return a host-private path. */
    public JSONObject get(String id) throws Exception {
        try(PodSyncFileReceiver receiver=open()) {
            JSONObject manifest=manifest(id); command(receiver,operation("finish",id)); return manifest;
        }
    }
    /** Convenience one-chunk read; use a retained Reader for a transfer. */
    public byte[] chunk(String id,int index) throws Exception {
        try(Reader reader=reader(id)) { return reader.chunk(index); }
    }
    public Reader reader(String id) throws Exception {
        identity(id); PodSyncFileReceiver receiver=open();
        try {
            JSONObject manifest=manifest(id); command(receiver,operation("finish",id));
            FileChannel file=FileChannel.open(new File(new File(root,id),"complete").toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            return new Reader(receiver,file,manifest);
        } catch(Exception error) { try { receiver.close(); } catch(Exception closing) { error.addSuppressed(closing); } throw error; }
    }
    /** Retains the snapshot namespace lease and one descriptor. Recovery and
     * whole-file verification occur once, chunk hashes remain checked per read.
     * Close before creating/discarding another snapshot in this namespace. */
    public static final class Reader implements AutoCloseable {
        private final PodSyncFileReceiver receiver;
        private final FileChannel input;
        private final JSONObject manifest;
        private boolean closed;
        private Reader(PodSyncFileReceiver receiver,FileChannel input,JSONObject manifest) { this.receiver=receiver; this.input=input; this.manifest=manifest; }
        public synchronized byte[] chunk(int index) throws Exception {
            if(closed) throw new IOException("Snapshot reader closed");
            JSONArray hashes=manifest.getJSONArray("chunk_hashes");
            if(index<0 || index>=hashes.length()) throw new IOException("Invalid snapshot chunk");
            long size=manifest.getLong("size"); if(input.size()!=size) throw new IOException("Snapshot size changed");
            byte[] bytes=new byte[(int)Math.min(65536,size-index*65536L)];
            input.position(index*65536L); if(readChunk(input,bytes)!=bytes.length) throw new IOException("Snapshot truncated");
            if(!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hashes.getString(index))) throw new IOException("Snapshot chunk corrupted");
            return bytes;
        }
        @Override public synchronized void close() throws IOException { if(!closed) { closed=true; try { input.close(); } finally { receiver.close(); } } }
    }
    /** Recovery inventory includes interrupted snapshots; call get to verify
     * completeness before use. This never deletes an unknown/orphan snapshot. */
    public List<String> inventory() throws Exception {
        try(PodSyncFileReceiver receiver=open()) {
            ArrayList<String> ids=new ArrayList<>(); File[] children=root.listFiles(); if(children==null) throw new IOException("Snapshot inventory unavailable");
            for(File child:children) {
                if(!Files.isDirectory(child.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid snapshot entry");
                identity(child.getName()); manifest(child.getName()); ids.add(child.getName());
                if(ids.size()>128) throw new IOException("Snapshot quota exceeded");
            }
            java.util.Collections.sort(ids); return java.util.Collections.unmodifiableList(ids);
        }
    }
    public void discard(String id) throws Exception { identity(id); try(PodSyncFileReceiver receiver=open()) { command(receiver,operation("cancel",id)); } }
}
