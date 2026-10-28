package dev.podjs.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/** Cross-process writer exclusion. A verified local-transfer owner may write
 * while its own background pause remains held; it never bypasses another writer. */
final class CloudWriterLease implements Closeable {
    private RandomAccessFile file;
    private FileLock lock;
    synchronized boolean acquire(File directory, boolean localTransferHeld, boolean verifiedLocalOwner) throws Exception {
        if (localTransferHeld && !verifiedLocalOwner) return false;
        if (lock != null) throw new IllegalStateException("Cloud lease already owned");
        file = new RandomAccessFile(new File(directory, "podjs-cloud-writer.lock"), "rw");
        try {
            try { lock = file.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) { file.close(); file = null; return false; }
            return true;
        } catch (Exception error) { close(); throw error; }
    }
    @Override public synchronized void close() {
        try { if (lock != null) lock.release(); } catch (Exception ignored) {}
        finally { lock = null; try { if (file != null) file.close(); } catch (Exception ignored) {} file = null; }
    }
}
