package dev.podjs.runtime;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded reads of the default pak from the installed package's base/split APKs.
 * Runtime AssetManager overlays and resource loaders are not part of this path.
 * Splits are searched in reverse order, followed by the base APK, matching the
 * package asset precedence. Android 11 ZipFile maps only the central directory;
 * entry payloads are read through file I/O, unlike AssetManager.open/openFd.
 * Stored entries can skip without reading their payload. Compressed entries
 * decompress sequentially and backward reads reopen their entry stream.
 */
final class PodPakReader implements Closeable {
    private static final String ASSET = "assets/main.pak";
    private static final int MAX_READ = 64 * 1024;
    private ZipFile archive;
    private InputStream stream;
    private ZipEntry entry;
    private String packagePath;
    private long assetLength;
    private long position;
    private boolean closed;
    private final byte[] skipBuffer = new byte[8192];

    PodPakReader(String sourceDir, String[] splitSourceDirs) throws IOException {
        if (sourceDir == null || sourceDir.isEmpty()) {
            throw new IOException(ASSET + ": missing base APK path");
        }
        StringBuilder searched = new StringBuilder();
        if (splitSourceDirs != null) {
            for (int index = splitSourceDirs.length - 1; index >= 0; index--) {
                String path = splitSourceDirs[index];
                if (path == null || path.isEmpty()) {
                    throw new IOException(ASSET + ": missing split APK path at index " + index);
                }
                if (searched.length() != 0) searched.append(", ");
                searched.append(path);
                if (open(path)) return;
            }
        }
        if (searched.length() != 0) searched.append(", ");
        searched.append(sourceDir);
        if (open(sourceDir)) return;
        throw new FileNotFoundException(ASSET + " not found in package APKs: " + searched);
    }

    private boolean open(String path) throws IOException {
        ZipFile candidate = null;
        InputStream candidateStream = null;
        try {
            candidate = new ZipFile(path);
            ZipEntry selected = candidate.getEntry(ASSET);
            if (selected == null) {
                ZipFile unused = candidate;
                candidate = null;
                unused.close();
                return false;
            }
            if (selected.isDirectory() || selected.getSize() < 0) {
                throw new IOException("Invalid pak entry size or type");
            }
            candidateStream = candidate.getInputStream(selected);
            if (candidateStream == null) throw new IOException("Pak entry stream unavailable");
            archive = candidate;
            stream = candidateStream;
            entry = selected;
            packagePath = path;
            assetLength = selected.getSize();
            return true;
        } catch (IOException | RuntimeException error) {
            IOException failure = new IOException(ASSET + " in " + path + ": open failed: "
                    + error.getMessage(), error);
            closeSuppressed(candidateStream, failure);
            closeSuppressed(candidate, failure);
            throw failure;
        } catch (Error error) {
            closeSuppressed(candidateStream, error);
            closeSuppressed(candidate, error);
            throw error;
        }
    }

    long length() {
        return assetLength;
    }

    /** Returns a possibly short read, or zero at the declared end of the pak. */
    int readAt(long offset, byte[] out, int length) throws IOException {
        try {
            if (closed) throw new IOException("Reader is closed");
            if (out == null) throw new IOException("Destination buffer is null");
            if (offset < 0 || offset > assetLength) throw new IOException("Offset outside pak");
            if (length < 0 || length > out.length || length > MAX_READ) {
                throw new IOException("Read length outside destination or 64 KiB limit");
            }
            if (length == 0 || offset == assetLength) return 0;
            seek(offset);
            int wanted = (int) Math.min((long) length, assetLength - position);
            int count = stream.read(out, 0, wanted);
            if (count == 0) {
                int value = stream.read();
                if (value >= 0) {
                    out[0] = (byte) value;
                    count = 1;
                } else {
                    count = -1;
                }
            }
            if (count < 0) throw new EOFException("Pak stream ended before its declared size");
            if (count > wanted) throw new IOException("Pak stream exceeded requested read length");
            position += count;
            return count;
        } catch (IOException | RuntimeException error) {
            throw new IOException(ASSET + " in " + packagePath + ": read at " + offset
                    + " for " + length + " bytes failed: " + error.getMessage(), error);
        }
    }

    private void seek(long offset) throws IOException {
        if (stream == null || offset < position) {
            if (stream != null) stream.close();
            stream = null;
            position = 0;
            stream = archive.getInputStream(entry);
            if (stream == null) throw new IOException("Pak entry stream unavailable on reopen");
        }
        while (position < offset) {
            long remaining = offset - position;
            long skipped = stream.skip(remaining);
            if (skipped < 0 || skipped > remaining) throw new IOException("Invalid pak stream skip");
            if (skipped > 0) {
                position += skipped;
                continue;
            }
            int count = stream.read(skipBuffer, 0, (int) Math.min(remaining, skipBuffer.length));
            if (count == 0) {
                if (stream.read() < 0) throw new EOFException("Pak stream ended while seeking");
                count = 1;
            }
            if (count < 0) throw new EOFException("Pak stream ended while seeking");
            if (count > remaining) throw new IOException("Pak stream exceeded seek distance");
            position += count;
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        InputStream closingStream = stream;
        ZipFile closingArchive = archive;
        stream = null;
        archive = null;
        Throwable failure = null;
        try {
            if (closingStream != null) closingStream.close();
        } catch (Throwable error) {
            failure = error instanceof Error ? error
                    : new IOException(ASSET + " in " + packagePath + ": close stream failed: "
                            + error.getMessage(), error);
        }
        try {
            if (closingArchive != null) closingArchive.close();
        } catch (Throwable error) {
            if (failure != null) failure.addSuppressed(error);
            else failure = error instanceof Error ? error
                    : new IOException(ASSET + " in " + packagePath + ": close APK failed: "
                            + error.getMessage(), error);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (IOException) failure;
    }

    private static void closeSuppressed(Closeable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Throwable closeError) {
            if (closeError != failure) failure.addSuppressed(closeError);
        }
    }
}
