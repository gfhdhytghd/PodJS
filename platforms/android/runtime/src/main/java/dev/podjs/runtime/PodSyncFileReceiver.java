package dev.podjs.runtime;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;

/** Host-only receiver. Call on an IO worker after authenticating/authorizing the peer.
 * The owner must close this object; no UI or QuickJS runtime is required.
 */
public final class PodSyncFileReceiver implements AutoCloseable {
    static { System.loadLibrary("podjs_android"); }
    private long handle;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private final File parent, root, quotaPath;

    public PodSyncFileReceiver(Context context, String applicationId, String peerId) throws IOException {
        PodTakeover.requireReady(context);
        validateId(applicationId);
        validateId(peerId);
        parent = new File(new File(context.getNoBackupFilesDir(), "podjs-sync"), applicationId);
        root = new File(parent,peerId); quotaPath = new File(parent,"@quota.lock");
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create sync directory");
        lockFile = new RandomAccessFile(new File(parent, peerId + ".lock"), "rw");
        FileLock acquired = null;
        try {
            acquired = lockFile.getChannel().tryLock();
            if (acquired == null) throw new IOException("Receiver already open");
            try (RandomAccessFile quota = new RandomAccessFile(quotaPath,"rw"); FileLock quotaLock = quotaLock(quota)) {
                handle = nativeOpen(root.getAbsolutePath());
            }
            if (handle == 0) throw new IOException("Cannot open receiver");
        } catch (IOException | RuntimeException | Error error) {
            if (handle != 0) { nativeClose(handle); handle = 0; }
            if (acquired != null) { try { acquired.release(); } catch (IOException closing) { error.addSuppressed(closing); } }
            try { lockFile.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            if (error instanceof OverlappingFileLockException) throw new IOException("Receiver already open", error);
            throw error;
        }
        lock = acquired;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid application or peer ID");
    }

    /** Returns the core's JSON success/error envelope. Commands are serialized with close. */
    public synchronized String command(String json) throws IOException {
        if (handle == 0) throw new IllegalStateException("Receiver closed");
        if (json == null || json.length() > 96 * 1024) throw new IllegalArgumentException("Invalid command");
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(json));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        if (bytes.length == 0 || bytes.length > 96 * 1024) throw new IllegalArgumentException("Invalid command size");
        try (RandomAccessFile quota = new RandomAccessFile(quotaPath,"rw"); FileLock quotaLock = quotaLock(quota)) {
            try {
                org.json.JSONObject command = new org.json.JSONObject(json);
                if ("offer".equals(command.optString("method"))) checkQuota(command.getJSONObject("manifest"));
                if ("cancel".equals(command.optString("method")) && command.length() == 2 && command.opt("transfer_id") instanceof String) {
                    String id = command.getString("transfer_id"); validateId(id);
                    // A crash may follow native deletion but precede the host's
                    // journal commit. Absence under the app lock is idempotent.
                    if (!java.nio.file.Files.exists(new File(root,id).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        return "{\"ok\":true,\"value\":{\"state\":\"cancelled\"}}";
                }
            } catch (org.json.JSONException error) { throw new IOException("Invalid file command",error); }
            return new String(nativeCommand(handle, bytes), StandardCharsets.UTF_8);
        }
    }

    private static FileLock quotaLock(RandomAccessFile file) throws IOException {
        try {
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) throw new IOException("Application file storage busy");
            return lock;
        } catch (OverlappingFileLockException error) { throw new IOException("Application file storage busy",error); }
    }
    private static long size(org.json.JSONObject manifest) throws org.json.JSONException, IOException {
        Object value = manifest.get("size");
        if (!(value instanceof Number)) throw new IOException("Invalid file size");
        long size = ((Number)value).longValue();
        if (size < 0 || size > 16L * 1024 * 1024 || ((Number)value).doubleValue() != size) throw new IOException("File quota exceeded");
        return size;
    }
    /** The native receiver enforces its own directory quota. This additional
     * host reservation spans all peers, under one process-independent app lock.
     * Incomplete files reserve chunks + assembly; completed files reserve one
     * copy only after verifying their hash. Metadata does not count as file data.
     */
    private void checkQuota(org.json.JSONObject manifest) throws IOException, org.json.JSONException {
        String id = manifest.getString("transfer_id"); validateId(id); long requested = size(manifest);
        // Native code still validates manifest identity/content on repeat offers.
        if (new File(root,id).exists()) return;
        long reserved = requested * 2; int count = 0;
        File[] peers = parent.listFiles(); if (peers == null) throw new IOException("Cannot inspect file quota");
        for (File peer : peers) {
            if (java.nio.file.Files.isSymbolicLink(peer.toPath())) throw new IOException("Invalid file storage entry");
            if (peer.isFile() && peer.getName().endsWith(".lock")) continue;
            if (!peer.isDirectory()) throw new IOException("Invalid file storage entry");
            File[] transfers = peer.listFiles(); if (transfers == null) throw new IOException("Cannot inspect file quota");
            for (File transfer : transfers) {
                if (java.nio.file.Files.isSymbolicLink(transfer.toPath()) || !transfer.isDirectory()) throw new IOException("Invalid transfer directory");
                File metadata = new File(transfer,"manifest.json");
                if (!java.nio.file.Files.isRegularFile(metadata.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) || metadata.length() > 32768) throw new IOException("Invalid transfer metadata");
                org.json.JSONObject saved = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));
                long fileSize = size(saved); File complete = new File(transfer,"complete");
                long bytes = 0; File[] contents = transfer.listFiles(); if (contents == null) throw new IOException("Cannot inspect transfer");
                for (File child : contents) {
                    if (!java.nio.file.Files.isRegularFile(child.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid transfer data");
                    if (!child.getName().equals("manifest.json") && !child.getName().equals("manifest.tmp")) {
                        long length = child.length(); if (length > 32L * 1024 * 1024 || bytes > 32L * 1024 * 1024 - length) throw new IOException("Application file quota exceeded");
                        bytes += length;
                    }
                }
                reserved += Math.max(bytes,verified(complete,fileSize,saved.getString("sha256")) ? fileSize : fileSize * 2);
                if (reserved > 32L * 1024 * 1024 || ++count >= 128) throw new IOException("Application file quota exceeded");
            }
        }
    }
    private static boolean verified(File file, long size, String expected) throws IOException {
        if (!java.nio.file.Files.isRegularFile(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) || file.length() != size) return false;
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer,0,count);
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return hex.toString().equals(expected);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    @Override public synchronized void close() throws IOException {
        if (handle == 0) return;
        nativeClose(handle);
        handle = 0;
        try { lock.release(); } finally { lockFile.close(); }
    }

    private static native long nativeOpen(String root);
    private static native byte[] nativeCommand(long handle, byte[] command);
    private static native void nativeClose(long handle);
}
