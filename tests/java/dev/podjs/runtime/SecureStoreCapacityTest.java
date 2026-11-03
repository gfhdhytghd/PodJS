package dev.podjs.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Real host operations/AEAD/atomic files, synthetic key, no device or user data. */
public final class SecureStoreCapacityTest {
    private static final SecretKeySpec KEY = new SecretKeySpec(new byte[32], "AES");
    private static PodDeviceServices host(Path directory) {
        return new PodDeviceServices(directory.resolve("files").toFile(), () -> KEY);
    }
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }
    private static String get(PodDeviceServices host, String key) throws Exception {
        return host.execute("secure.get", new JSONObject().put("key", key)).getString("value");
    }
    private static void set(PodDeviceServices host, String key, String value) throws Exception {
        host.execute("secure.set", new JSONObject().put("key", key).put("value", value));
    }
    private static void quota(PodDeviceServices host, String method, JSONObject args, Path file, byte[] before) throws Exception {
        try { host.execute(method, args); throw new AssertionError("over-limit write accepted: " + method); }
        catch (SecureStoreLimits.Failure expected) { check("secure_quota".equals(expected.code), "closed quota code"); }
        check(Arrays.equals(before, Files.readAllBytes(file)), "failed write changed ciphertext: " + method);
        check("sentinel".equals(get(host, "identity")), "failed write changed cached identity");
        try (var names = Files.list(file.getParent())) {
            check(names.noneMatch(p -> p.getFileName().toString().startsWith(".tmp-")), "quota left temp file");
        }
    }
    private static void verifyRestart(Path directory) throws Exception {
        try (PodDeviceServices next = host(directory)) {
            for (int i = 0; i < 4; i++) check(get(next, "part" + i).equals("汉😀".repeat(14000)), "restart entry differs");
        }
    }
    private static void migrationQuota(Path directory) throws Exception {
        Path source = directory.resolve("migration-source"), generation = directory.resolve("migration-candidate");
        Files.createDirectories(source.resolve("shared_prefs"));
        Path account = source.resolve("shared_prefs/watchrss_account_state.xml");
        String state = new JSONObject().put("userId", "synthetic").put("watchAccessToken", "x".repeat(SecureStoreLimits.MAX_PLAIN_BYTES)).toString();
        byte[] original = ("<map><string name=\"state_json\">" + state + "</string></map>").getBytes(StandardCharsets.UTF_8);
        Files.write(account, original);
        PodStoreConverter.Crypto crypto = new PodStoreConverter.Crypto() {
            public java.util.Map<String,Object> decrypt(String store, java.util.Map<String,Object> raw) { return raw; }
            public byte[] seal(JSONObject value) throws Exception { return SecureDecodeTraceTest.encrypt(value.toString(), KEY); }
            public JSONObject open(byte[] bytes) throws Exception { return PodDeviceServices.decodeSecureBytes(bytes, KEY); }
        };
        PodStoreConverter converter = new PodStoreConverter(source.toFile(), crypto);
        converter.snapshot(generation.toFile());
        Path previous = generation.resolve("store-results/" + PodStoreConverter.slot("watchrss_account_state") + "/secure.store");
        Files.createDirectories(previous.getParent());
        byte[] old = SecureDecodeTraceTest.encrypt(new JSONObject().put("preserved", "synthetic").toString(), KEY);
        Files.write(previous, old);
        try { converter.copy(generation.toFile()); throw new AssertionError("migration silently shrank oversized secrets"); }
        catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_quota"), "migration publication quota code"); }
        check(Arrays.equals(old, Files.readAllBytes(previous)), "migration replaced old ciphertext");
        check(Arrays.equals(original, Files.readAllBytes(account)), "migration changed source");
        check(!Files.exists(previous.resolveSibling("receipt.json")), "migration published a false empty receipt");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { verifyRestart(Path.of(args[0])); return; }
        check(PodDeviceServices.MAX_CHUNK_BYTES == 256 * 1024, "per-operation chunk limit changed");
        Path suite = Files.createTempDirectory("podjs-secure-capacity-");
        Path directory = Files.createDirectories(suite.resolve("restart"));
        Path file = directory.resolve("secure.store");
        try {
            try (PodDeviceServices first = host(directory)) {
                for (int i = 0; i < 4; i++) set(first, "part" + i, "汉😀".repeat(14000));
                check(Files.size(file) > 262208, "fixture did not cross old limit");
            }
            byte[] beforeRead = Files.readAllBytes(file);
            verifyRestart(directory);
            Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").toString(),
                    "-Xmx64m", "-ea", "-cp", System.getProperty("java.class.path"),
                    SecureStoreCapacityTest.class.getName(), directory.toString()).inheritIO().start();
            check(child.waitFor() == 0, "fresh JVM restart failed");
            check(Arrays.equals(beforeRead, Files.readAllBytes(file)), "restart rewrote ciphertext");

            // Reproduce the recorded file size, without reading real ciphertext/key.
            // Each cold-load fixture has its own store: ordinary service close
            // deliberately retains the process cache under the new contract.
            directory=Files.createDirectories(suite.resolve("legacy"));file=directory.resolve("secure.store");
            JSONObject legacy = new JSONObject().put("fixture", "");
            legacy.put("fixture", "x".repeat(262255 - 28 - legacy.toString().getBytes(StandardCharsets.UTF_8).length));
            byte[] old = SecureDecodeTraceTest.encrypt(legacy.toString(), KEY);
            check(old.length == 262255, "old-limit fixture length");
            Files.write(file, old);
            check(PodDeviceServices.readSecureFile(file.toFile(), KEY).getString("fixture").equals(legacy.getString("fixture")), "old-limit fixture rejected");
            try (PodDeviceServices next = host(directory)) { check(get(next, "fixture").equals(legacy.getString("fixture")), "production cached reader rejected old-limit fixture"); }
            check(Arrays.equals(old, Files.readAllBytes(file)), "legacy read rewrote file");

            JSONObject full = new JSONObject().put("identity", "sentinel");
            for (int i = 0; i < 7; i++) full.put("entry" + i, "x".repeat(256 * 1024 - 1024));
            full.put("padding", "");
            full.put("padding", "x".repeat(SecureStoreLimits.MAX_PLAIN_BYTES - full.toString().getBytes(StandardCharsets.UTF_8).length));
            check(full.toString().getBytes(StandardCharsets.UTF_8).length == SecureStoreLimits.MAX_PLAIN_BYTES, "exact boundary fixture");
            // Every entry is below the existing 256 KiB chunk size.
            directory=Files.createDirectories(suite.resolve("full"));file=directory.resolve("secure.store");
            Files.write(file, SecureDecodeTraceTest.encrypt("{}", KEY));
            try (PodDeviceServices writer = host(directory)) {
                writer.execute("secure.update", new JSONObject().put("values", full));
                check(Files.size(file) == SecureStoreLimits.MAX_FILE_BYTES, "exact boundary write");
                byte[] before = Files.readAllBytes(file);
                quota(writer, "secure.set", new JSONObject().put("key", "extra").put("value", "x"), file, before);
                quota(writer, "secure.update", new JSONObject().put("values", new JSONObject().put("extra", "x")), file, before);
                quota(writer, "secure.update", new JSONObject().put("values", new JSONObject().put("extra", "x")).put("removePrefixes", new org.json.JSONArray().put("absent.")), file, before);
                quota(writer, "secure.compareAndSet", new JSONObject().put("key", "identity").put("expected", "sentinel").put("value", "sentinelx"), file, before);
                check(!writer.execute("secure.compareAndSet", new JSONObject().put("key", "identity").put("expected", "stale").put("value", "x")).getBoolean("updated"), "stale CAS accepted");
                writer.execute("secure.delete", new JSONObject().put("key", "padding"));
                set(writer, "extra", "x");
                check(writer.execute("secure.compareAndSet", new JSONObject().put("key", "identity").put("expected", "sentinel").put("value", "next")).getBoolean("updated"), "legal CAS rejected");
            }
            try (PodDeviceServices restarted = host(directory)) { check(get(restarted, "identity").equals("next"), "post-quota restart failed"); }

            for (String part : new String[] {"汉😀\\\"\n\t</", "\u0000\ud800x\udc00", "x".repeat(4095) + "</", "x".repeat(4095) + "😀"}) {
                JSONObject shape = new JSONObject().put("escaped\nkey", part.repeat(3)).put("nested", new org.json.JSONArray().put(true).put(1.5).put(JSONObject.NULL));
                check(Arrays.equals(SecureStoreLimits.encode(shape), shape.toString().getBytes(StandardCharsets.UTF_8)), "UTF-8/escaping budget differs");
            }
            // Even a short raw string can exceed the limit after JSON escaping.
            try { SecureStoreLimits.encode(new JSONObject().put("escaped", "\u0000".repeat(SecureStoreLimits.MAX_PLAIN_BYTES / 6 + 1))); throw new AssertionError("escaped quota accepted"); }
            catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_quota"), "escaped quota code"); }
            try { PodStoreConverter.validateSecure(new JSONObject().put("x", "x".repeat(SecureStoreLimits.MAX_PLAIN_BYTES))); throw new AssertionError("migration quota accepted"); }
            catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_quota"), "migration quota code"); }
            PodStoreConverter.validateSecure(legacy);

            try (var sparse = new java.io.RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(SecureStoreLimits.MAX_FILE_BYTES + 1L); }
            try { PodDeviceServices.readSecureFile(file.toFile(), KEY); throw new AssertionError("oversize read accepted"); }
            catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_store_too_large"), "read size confused with auth"); }
            try { PodDeviceServices.decodeSecureBytes(new byte[SecureStoreLimits.MAX_FILE_BYTES + 1], KEY); throw new AssertionError("oversize decode accepted"); }
            catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_store_too_large"), "decode size confused with auth"); }
            try { PodDeviceServices.readSecureFile(directory.resolve("missing").toFile(), KEY); throw new AssertionError("missing read accepted"); }
            catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_read_failed"), "read IO code"); }
            directory=Files.createDirectories(suite.resolve("io-failure"));file=directory.resolve("secure.store");
            Files.createDirectory(file);
            try (PodDeviceServices next = host(directory)) {
                for (String method : new String[] {"secure.get", "secure.set"}) {
                    try { next.execute(method, new JSONObject().put("key", "identity").put("value", "replacement")); throw new AssertionError("IO failure treated as empty store"); }
                    catch (SecureStoreLimits.Failure expected) { check(expected.code.equals("secure_read_failed"), "directory IO code"); }
                }
                check(Files.isDirectory(file), "IO failure replaced store");
            }
            migrationQuota(directory);
            System.out.println("SecureStoreCapacity PASS: multi-entry >256KiB, fresh JVM restart, 262255B legacy, exact 2MiB boundary, set/update/prefix/CAS rejection preserves ciphertext/cache, deletion, UTF-8, migration rejects without empty rewrite, bounded/failed reads; -Xmx64m synthetic key.");
        } finally {
            try (var paths = Files.walk(suite)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
