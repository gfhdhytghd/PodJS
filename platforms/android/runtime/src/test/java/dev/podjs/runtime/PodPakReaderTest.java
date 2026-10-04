package dev.podjs.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Standalone JVM checks; run this main with PodPakReader and no Android/JUnit dependency. */
public final class PodPakReaderTest {
    private static final String ASSET = "assets/main.pak";
    private static int checks;

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("pod-pak-reader-test-");
        try {
            byte[] data = new byte[196733];
            new Random(731).nextBytes(data);
            Path stored = archive(directory.resolve("stored.apk"), data, true);
            Path deflated = archive(directory.resolve("deflated.apk"), data, false);
            checkReads(stored, data);
            checkReads(deflated, data);
            checkBounds(stored, data.length);
            checkBounds(deflated, data.length);
            checkEmpty(directory);
            checkSplitSelection(directory);
            checkOpenFailures(directory, stored);
            checkShortReadsAndZeroSkip(stored, data);
            checkTruncatedStream(stored, data.length);
            checkCloseFailures(stored);
            System.out.println("PodPakReaderTest passed (" + checks + " checks)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkReads(Path path, byte[] expected) throws Exception {
        try (PodPakReader reader = new PodPakReader(path.toString(), null)) {
            equal(expected.length, reader.length(), "asset length");
            readMatches(reader, expected, 0, 173);
            readMatches(reader, expected, 60000, 613);
            readMatches(reader, expected, 77, 497);
            readMatches(reader, expected, 77, 65536);
            readMatches(reader, expected, expected.length - 37, 300);
            readMatches(reader, expected, expected.length, 19);
            readMatches(reader, expected, 0, 0);
            readMatches(reader, expected, expected.length, 0);
            Random random = new Random(20260920);
            for (int i = 0; i < 80; i++) {
                readMatches(reader, expected, random.nextInt(expected.length + 1), random.nextInt(65537));
            }
        }
    }

    private static void readMatches(PodPakReader reader, byte[] expected, int offset, int length)
            throws IOException {
        byte[] output = new byte[length + 11];
        int wanted = Math.min(length, expected.length - offset);
        int consumed = 0;
        do {
            Arrays.fill(output, (byte) 0x6d);
            int count = reader.readAt((long) offset + consumed, output, length - consumed);
            if (wanted == 0) equal(0, count, "empty read at " + offset);
            else require(count > 0 && count <= wanted - consumed, "valid short read at " + (offset + consumed));
            for (int i = 0; i < count; i++) {
                if (output[i] != expected[offset + consumed + i]) throw new AssertionError("Wrong byte at " + (offset + consumed + i));
            }
            for (int i = count; i < output.length; i++) {
                if (output[i] != (byte) 0x6d) throw new AssertionError("Read changed bytes outside returned range");
            }
            checks++;
            consumed += count;
        } while (consumed < wanted);
    }

    private static void checkBounds(Path path, int total) throws Exception {
        PodPakReader reader = new PodPakReader(path.toString(), new String[0]);
        try {
            failsDiagnostically(() -> reader.readAt(-1, new byte[1], 1), path, "negative offset");
            failsDiagnostically(() -> reader.readAt((long) total + 1, new byte[1], 1), path, "offset after EOF");
            failsDiagnostically(() -> reader.readAt(Long.MAX_VALUE, new byte[1], 1), path, "overflowing offset");
            failsDiagnostically(() -> reader.readAt(0, new byte[1], -1), path, "negative length");
            failsDiagnostically(() -> reader.readAt(0, new byte[1], 2), path, "output too small");
            failsDiagnostically(() -> reader.readAt(0, new byte[65537], 65537), path, "chunk limit");
            equal(1, reader.readAt(0, new byte[1], 1), "reader survives invalid requests");
        } finally {
            reader.close();
        }
        reader.close();
        failsDiagnostically(() -> reader.readAt(0, new byte[1], 1), path, "read after close");
        failsDiagnostically(() -> reader.readAt(total, new byte[0], 0), path, "empty read after close");
    }

    private static void checkEmpty(Path directory) throws Exception {
        for (boolean stored : new boolean[]{true, false}) {
            Path path = archive(directory.resolve("empty-" + stored + ".apk"), new byte[0], stored);
            try (PodPakReader reader = new PodPakReader(path.toString(), null)) {
                equal(0, reader.length(), "empty asset length");
                equal(0, reader.readAt(0, new byte[10], 10), "empty asset EOF");
                fails(() -> reader.readAt(1, new byte[0], 0), "empty asset offset bounds");
            }
        }
    }

    private static void checkSplitSelection(Path directory) throws Exception {
        byte[] baseBytes = new byte[]{1, 2, 3};
        byte[] firstBytes = new byte[]{11, 12, 13, 14};
        byte[] lastBytes = new byte[]{21, 22, 23, 24, 25};
        Path base = archive(directory.resolve("base.apk"), baseBytes, true);
        Path first = archive(directory.resolve("first.apk"), firstBytes, false);
        Path last = archive(directory.resolve("last.apk"), lastBytes, true);
        Path absent = archive(directory.resolve("without-pak.apk"), null, true);
        try (PodPakReader reader = new PodPakReader(base.toString(), new String[]{first.toString(), last.toString()})) {
            equal(lastBytes.length, reader.length(), "last split takes precedence");
            readMatches(reader, lastBytes, 0, 20);
        }
        try (PodPakReader reader = new PodPakReader(base.toString(), new String[]{first.toString(), absent.toString()})) {
            readMatches(reader, firstBytes, 0, 20);
        }
        try (PodPakReader reader = new PodPakReader(base.toString(), new String[]{absent.toString()})) {
            readMatches(reader, baseBytes, 0, 20);
        }
        failsToOpen(absent.toString(), null, absent.toString(), "missing asset");
    }

    private static void checkOpenFailures(Path directory, Path valid) throws Exception {
        Path missing = directory.resolve("missing.apk");
        Path corrupt = directory.resolve("corrupt.apk");
        Files.write(corrupt, new byte[]{0x50, 0x4b, 3, 4, 1, 2, 3});
        require(failsToOpen(missing.toString(), null, missing.toString(), "missing APK").getCause() != null, "missing APK cause retained");
        require(failsToOpen(corrupt.toString(), null, corrupt.toString(), "corrupt APK").getCause() != null, "corrupt APK cause retained");
        require(failsToOpen(valid.toString(), new String[]{missing.toString()}, missing.toString(), "missing split must not fall back").getCause() != null, "missing split cause retained");
        require(failsToOpen(valid.toString(), new String[]{corrupt.toString()}, corrupt.toString(), "corrupt split must not fall back").getCause() != null, "corrupt split cause retained");
    }

    private static IOException failsToOpen(String base, String[] splits, String failedPath, String description)
            throws Exception {
        IOException failure = fails(() -> {
            try (PodPakReader reader = new PodPakReader(base, splits)) {
                throw new AssertionError("Unexpected successful open: " + reader.length() + " bytes");
            }
        }, description);
        require(failure.getMessage().contains(ASSET), "open error identifies asset");
        require(failure.getMessage().contains(failedPath), "open error identifies APK path");
        return failure;
    }

    private static void checkShortReadsAndZeroSkip(Path path, byte[] data) throws Exception {
        try (PodPakReader reader = new PodPakReader(path.toString(), null)) {
            replaceStream(reader, new ByteArrayInputStream(data) {
                private boolean returnZero;
                @Override public long skip(long count) { return 0; }
                @Override public synchronized int read(byte[] output, int offset, int count) {
                    returnZero = !returnZero;
                    if (returnZero && count > 0) return 0;
                    return super.read(output, offset, Math.min(count, 7));
                }
            });
            readMatches(reader, data, 1000, 4096);
            readMatches(reader, data, 8000, 511);
            readMatches(reader, data, 12, 100);
        }
    }

    private static void checkTruncatedStream(Path path, int total) throws Exception {
        try (PodPakReader reader = new PodPakReader(path.toString(), null)) {
            replaceStream(reader, new ByteArrayInputStream(new byte[3]));
            fails(() -> reader.readAt(20, new byte[10], 10), "premature EOF while seeking");
        }
        try (PodPakReader reader = new PodPakReader(path.toString(), null)) {
            replaceStream(reader, new ByteArrayInputStream(new byte[3]));
            fails(() -> {
                int offset = 0;
                while (offset < 10) {
                    int count = reader.readAt(offset, new byte[10], 10 - offset);
                    require(count > 0, "read progresses before premature EOF");
                    offset += count;
                }
            }, "premature EOF while reading");
            equal(total, reader.length(), "truncated stream does not change declared length");
        }
    }

    private static void checkCloseFailures(Path path) throws Exception {
        PodPakReader reader = new PodPakReader(path.toString(), null);
        FailingStream stream = new FailingStream();
        replaceStream(reader, stream);
        ZipFile original = (ZipFile) field("archive").get(reader);
        original.close();
        FailingArchive archive = new FailingArchive(path);
        field("archive").set(reader, archive);
        IOException failure = fails(reader::close, "close failures propagate");
        equal(1, stream.closes, "stream close attempted");
        equal(1, archive.closes, "archive close still attempted");
        require(contains(failure, stream.failure), "stream failure retained");
        require(contains(failure, archive.failure), "archive failure retained");
        require(failure.getSuppressed().length > 0, "secondary close failure suppressed");
        reader.close();
        equal(1, stream.closes, "second close leaves stream closed");
        equal(1, archive.closes, "second close leaves archive closed");
        fails(() -> reader.readAt(0, new byte[1], 1), "failed close still marks reader closed");
    }

    private static final class FailingStream extends ByteArrayInputStream {
        final IOException failure = new IOException("stream close failure");
        int closes;
        FailingStream() { super(new byte[0]); }
        @Override public void close() throws IOException { closes++; throw failure; }
    }

    private static final class FailingArchive extends ZipFile {
        final IOException failure = new IOException("archive close failure");
        int closes;
        FailingArchive(Path path) throws IOException { super(path.toFile()); }
        @Override public void close() throws IOException { closes++; super.close(); throw failure; }
    }

    private static void replaceStream(PodPakReader reader, InputStream replacement) throws Exception {
        InputStream previous = (InputStream) field("stream").get(reader);
        if (previous != null) previous.close();
        field("stream").set(reader, replacement);
        field("position").setLong(reader, 0);
    }

    private static Field field(String name) throws Exception {
        Field result = PodPakReader.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static boolean contains(Throwable root, Throwable wanted) {
        if (root == wanted) return true;
        for (Throwable child : root.getSuppressed()) if (contains(child, wanted)) return true;
        return root.getCause() != null && contains(root.getCause(), wanted);
    }

    private static Path archive(Path path, byte[] asset, boolean stored) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            byte[] payload = asset == null ? new byte[]{7} : asset;
            ZipEntry entry = new ZipEntry(asset == null ? "assets/unrelated" : ASSET);
            if (stored) {
                CRC32 crc = new CRC32();
                crc.update(payload);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(payload.length);
                entry.setCompressedSize(payload.length);
                entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        }
        return path;
    }

    private static IOException fails(IoAction action, String description) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return expected; }
        throw new AssertionError("Expected IOException: " + description);
    }

    private static void failsDiagnostically(IoAction action, Path path, String description) throws Exception {
        IOException failure = fails(action, description);
        require(failure.getMessage().contains(ASSET), "read error identifies asset");
        require(failure.getMessage().contains(path.toString()), "read error identifies APK path");
        require(failure.getCause() != null, "read error retains cause");
    }

    private static void equal(long expected, long actual, String description) {
        require(expected == actual, description + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
