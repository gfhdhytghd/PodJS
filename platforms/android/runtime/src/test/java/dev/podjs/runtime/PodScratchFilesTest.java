package dev.podjs.runtime;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PodScratchFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void chargesAndCollectsOnlyAbandonedScratch() throws Exception {
        Path directory = folder.getRoot().getCanonicalFile().toPath();
        Path writing = PodScratchFiles.create(directory, ".pod-write-", ".part");
        Path copying = PodScratchFiles.create(directory, ".pod-range-", ".tmp");
        try {
            Files.write(writing, new byte[5]);
            Files.write(copying, new byte[7]);
            Path abandonedWrite = Files.write(directory.resolve(".pod-write-crash.part"), new byte[10]);
            Path abandonedCopy = Files.write(directory.resolve(".pod-range-dead.tmp"), new byte[20]);
            Path book = Files.write(directory.resolve("book.jsonl"), new byte[40]);
            Path link = Files.createSymbolicLink(directory.resolve(".pod-write-42.part"), book);
            assertEquals(30, PodScratchFiles.orphanBytes(directory, false));
            assertTrue(Files.exists(abandonedWrite));
            assertEquals(0, PodScratchFiles.orphanBytes(directory, true));
            assertFalse(Files.exists(abandonedWrite));
            assertFalse(Files.exists(abandonedCopy));
            assertTrue(Files.exists(writing));
            assertTrue(Files.exists(copying));
            assertTrue(Files.exists(book));
            assertTrue(Files.isSymbolicLink(link));
        } finally {
            PodScratchFiles.release(writing);
            PodScratchFiles.release(copying);
        }
    }
}
