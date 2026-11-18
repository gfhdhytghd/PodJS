package dev.podjs.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Tracks live scratch across all guests in this Android process. */
final class PodScratchFiles {
    private static final Set<Path> LIVE = new HashSet<>();
    private static final Pattern NAME = Pattern.compile("\\.pod-(?:write-[^/]+\\.part|range-[^/]+\\.tmp)");

    private PodScratchFiles() {}

    static synchronized Path create(Path directory, String prefix, String suffix) throws IOException {
        Path path = Files.createTempFile(directory, prefix, suffix);
        LIVE.add(path);
        return path;
    }

    static synchronized void release(Path path) {
        LIVE.remove(path);
    }

    /** A missing directory is empty. Failed removals remain chargeable. */
    static synchronized long orphanBytes(Path directory, boolean collect) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (!directory.toRealPath().equals(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("invalid_scratch_directory");
        long bytes = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (!NAME.matcher(path.getFileName().toString()).matches() || LIVE.contains(path)) continue;
                BasicFileAttributes attr;
                try { attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException removed) { continue; }
                if (!attr.isRegularFile()) continue;
                if (collect) {
                    try { Files.deleteIfExists(path); continue; }
                    catch (IOException ignored) { /* Charge the bytes until collection succeeds. */ }
                }
                bytes = Math.addExact(bytes, attr.size());
            }
        }
        return bytes;
    }
}
