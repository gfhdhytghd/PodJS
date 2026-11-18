package dev.podjs.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;

/** A verified registration is recorded page by page. The channel keeps a
 * bounded lookup cache so the reader never waits for registration disk I/O. */
final class PodTransferIndex implements Closeable {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAGE_BYTES = 256 * 1024;
    private final RandomAccessFile file;
    private final List<byte[]> pageHashes = new ArrayList<>();

    PodTransferIndex(File directory) throws IOException {
        File path=File.createTempFile("transfer-index-", ".idx", directory);
        RandomAccessFile opened=null;
        try{
            opened=new RandomAccessFile(path,"rw");
            // A process crash closes the unlinked index. Recovery registers
            // the verified manifest again under its new grant.
            Files.delete(path.toPath());
            file=opened;
        }catch(IOException failure){
            if(opened!=null)opened.close();
            Files.deleteIfExists(path.toPath());
            throw failure;
        }
    }

    void append(JSONArray page) throws Exception {
        byte[] bytes = page.toString().getBytes(StandardCharsets.UTF_8);
        long end = file.length();
        if (bytes.length > MAX_PAGE_BYTES || end + 4 + bytes.length > MAX_BYTES)
            throw new IOException("LIMIT_EXCEEDED");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        try {
            file.seek(end);
            file.writeInt(bytes.length);
            file.write(bytes);
        } catch(IOException error) {
            file.setLength(end);
            throw error;
        }
        pageHashes.add(digest);
    }

    void seal() throws Exception {
        file.getFD().sync();
        file.seek(0);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (byte[] digest : pageHashes) {
            int length = file.readInt();
            if (length < 1 || length > MAX_PAGE_BYTES) throw new IOException("JOURNAL_CORRUPT");
            byte[] page = new byte[length];
            file.readFully(page);
            if (!Arrays.equals(digest, hash.digest(page))) throw new IOException("JOURNAL_CORRUPT");
        }
        if (file.getFilePointer() != file.length()) throw new IOException("JOURNAL_CORRUPT");
    }

    void reset() throws IOException {
        file.setLength(0);
        pageHashes.clear();
    }

    @Override public void close() throws IOException {
        file.close();
    }
}
