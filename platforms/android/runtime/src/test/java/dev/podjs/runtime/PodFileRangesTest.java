package dev.podjs.runtime;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodFileRangesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final byte[] SOURCE = {1, 2, 3, 4};
    private static final byte[] ORIGINAL = {8, 9};

    private File setup() throws IOException {
        File root = folder.getRoot();
        Files.write(new File(root, "source").toPath(), SOURCE);
        Files.write(new File(root, "target").toPath(), ORIGINAL);
        return root;
    }

    private static JSONObject range(int offset, int size) throws Exception {
        return new JSONObject().put("path", "source").put("offset", offset).put("size", size);
    }

    private static JSONObject request(JSONObject second) throws Exception {
        return new JSONObject().put("size", 4).put("target", "target")
            .put("ranges", new JSONArray().put(range(0, 2)).put(second));
    }

    private static JSONObject execute(File root, JSONObject args, CancellationSignal cancel) throws Exception {
        // Robolectric does not emulate reopening Android ParcelFileDescriptor via /proc/self/fd.
        return PodFileRanges.execute(root, args, cancel,
            (directory, path) -> new FileInputStream(PodFileConverter.safe(directory, path)));
    }

    private static void original(File root) throws IOException {
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(root, "target").toPath()));
        assertEquals(2, root.list().length);
    }

    @Test public void laterInvalidRangeDoesNotChangeTarget() throws Exception {
        File root = setup();
        IOException error = assertThrows(IOException.class,
            () -> execute(root, request(range(3, 2)), new CancellationSignal()));
        assertEquals("SOURCE_CHANGED", error.getMessage());
        original(root);
    }

    @Test public void hashFailureAndCancellationDoNotChangeTarget() throws Exception {
        File root = setup();
        JSONObject args = request(range(2, 2)).put("sha256", "0".repeat(64));
        IOException error = assertThrows(IOException.class,
            () -> execute(root, args, new CancellationSignal()));
        assertEquals("HASH_MISMATCH", error.getMessage());
        original(root);

        CancellationSignal cancel = new CancellationSignal();
        cancel.cancel();
        assertThrows(OperationCanceledException.class, () -> execute(root, args, cancel));
        original(root);
    }

    @Test public void cancellationDuringRangeWorkPreservesTarget() throws Exception {
        File root = setup();
        CancellationSignal cancel = new CancellationSignal();
        java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
        assertThrows(OperationCanceledException.class, () -> PodFileRanges.execute(root, request(range(2, 2)),
            cancel, (dir, path) -> new FileInputStream(PodFileConverter.safe(dir, path)), count -> {
                completed.addAndGet(count);
                cancel.cancel();
            }));
        assertEquals(2, completed.get());
        original(root);
    }

    @Test public void validRangesReplaceStagedFile() throws Exception {
        File root = setup();
        String sha = PodFileRanges.hex(MessageDigest.getInstance("SHA-256").digest(SOURCE));
        JSONObject result = execute(root,
            request(range(2, 2)).put("sha256", sha), new CancellationSignal());
        assertEquals(4, result.getLong("size"));
        assertEquals(sha, result.getString("sha256"));
        assertArrayEquals(SOURCE, Files.readAllBytes(new File(root, "target").toPath()));
        assertEquals(2, root.list().length);
    }

    @Test public void publicationRequiresATemporaryTargetFromThisGuest() throws Exception {
        File root = setup();
        PodFileRanges.RangeOpen open = (dir, path) -> new FileInputStream(PodFileConverter.safe(dir, path));
        try (PodDeviceServices caller = new PodDeviceServices(root, null);
             PodDeviceServices other = new PodDeviceServices(root, null)) {
            CancellationSignal cancel = new CancellationSignal();
            JSONObject args = request(range(2, 2));
            SecurityException foreign = assertThrows(SecurityException.class,
                () -> caller.copyFileRanges(args, cancel, open, ignored -> {}));
            assertEquals("file_target_not_owned", foreign.getMessage());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(root, "target").toPath()));

            String otherPath = other.execute("file.createTemp", new JSONObject().put("directory", "books")).getString("path");
            args.put("target", otherPath);
            assertThrows(SecurityException.class, () -> caller.copyFileRanges(args, cancel, open, ignored -> {}));
            assertEquals(0, new File(root, otherPath).length());

            String local = caller.execute("file.createTemp", new JSONObject().put("directory", "books")).getString("path");
            args.put("target", local);
            caller.copyFileRanges(args, cancel, open, ignored -> {});
            assertArrayEquals(SOURCE, Files.readAllBytes(new File(root, local).toPath()));
        }
    }

    @Test public void reportsFileSyncAndPublicationAfterHashing() throws Exception {
        File root = setup();
        java.util.List<String> phases = new java.util.ArrayList<>();
        PodFileProgress progress = new PodFileProgress() {
            long bytes;
            @Override public void accept(long count) { bytes += count; }
            @Override public void phase(String phase) {
                assertEquals(4, bytes);
                phases.add(phase);
            }
        };
        PodFileRanges.execute(root, request(range(2, 2)), new CancellationSignal(),
            (dir, path) -> new FileInputStream(PodFileConverter.safe(dir, path)), progress);
        assertEquals(java.util.List.of("syncFile", "publish"), phases);
    }

    @Test public void commitReportsFileSyncPublicationAndDirectorySync() throws Exception {
        File root = setup();
        java.util.List<String> phases = new java.util.ArrayList<>();
        try (PodDeviceServices caller = new PodDeviceServices(root, null)) {
            PodFileProgress progress = new PodFileProgress() {
                @Override public void accept(long count) {}
                @Override public void phase(String phase) {
                    phases.add(phase);
                    assertEquals(phase.equals("syncDirectory") ? 4 : 2, new File(root, "target").length());
                }
            };
            caller.executeTrackedFile("file.commit", new JSONObject().put("path", "source").put("target", "target")
                .put("expectedSize", 4).put("expectedSha256", PodFileRanges.hex(MessageDigest.getInstance("SHA-256").digest(SOURCE)))
                .put("syncDirectory", true), new CancellationSignal(), progress);
        }
        assertEquals(java.util.List.of("syncFile", "publish", "syncDirectory"), phases);
    }
}
