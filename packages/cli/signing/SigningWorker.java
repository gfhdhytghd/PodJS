import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Isolated credential reader. Never forwards signer output or exception text. */
class SigningWorker {
    record Signer(String file, String alias, String storePassword, String keyPassword) {}
    static Signer properties(String file) throws Exception {
        Path path = Path.of(file).toAbsolutePath();
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path)) { p.load(input); }
        for (String key : List.of("storeFile", "storePassword", "keyAlias", "keyPassword")) {
            if (p.getProperty(key) == null || p.getProperty(key).isEmpty()) throw new Exception();
        }
        return new Signer(path.getParent().resolve(p.getProperty("storeFile")).normalize().toString(),
            p.getProperty("keyAlias"), p.getProperty("storePassword"), p.getProperty("keyPassword"));
    }
    static Signer oldSigner(Map<String,String> options) throws Exception {
        if (options.containsKey("old-properties")) return properties(options.get("old-properties"));
        if (options.containsKey("old-debug-keystore")) {
            // The documented public Android debug credentials, only when explicitly selected.
            return new Signer(options.get("old-debug-keystore"), "androiddebugkey", "android", "android");
        }
        String storePassword = System.getenv(options.getOrDefault("old-store-pass-env", "POD_OLD_STORE_PASSWORD"));
        String keyPassword = System.getenv(options.getOrDefault("old-key-pass-env", "POD_OLD_KEY_PASSWORD"));
        if (storePassword == null || keyPassword == null || !options.containsKey("old-keystore") || !options.containsKey("old-key-alias")) throw new Exception();
        return new Signer(options.get("old-keystore"), options.get("old-key-alias"), storePassword, keyPassword);
    }
    static void signer(List<String> args, Map<String,String> env, Signer signer, String prefix) {
        args.addAll(List.of("--ks", signer.file(), "--ks-key-alias", signer.alias(),
            "--ks-pass", "env:" + prefix + "_STORE", "--key-pass", "env:" + prefix + "_KEY"));
        env.put(prefix + "_STORE", signer.storePassword()); env.put(prefix + "_KEY", signer.keyPassword());
    }
    static void execute(List<String> args, Map<String,String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.environment().putAll(env);
        // Even malformed credential files or hostile tool output cannot reach logs.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        child.getOutputStream().close();
        if (child.waitFor() != 0) throw new Exception();
    }
    public static void main(String[] args) {
        Path temporary = null;
        try {
            Map<String,String> options = new HashMap<>();
            for (int i = 1; i < args.length; i += 2) options.put(args[i].substring(2), args[i+1]);
            Map<String,String> env = new HashMap<>();
            List<String> command = new ArrayList<>(List.of(options.get("apksigner"), args[0]));
            Path output = Path.of(options.get("out")).toAbsolutePath();
            if (args[0].equals("rotate")) {
                if (Files.exists(output)) throw new Exception();
                Files.createDirectories(output.getParent());
                temporary = Files.createTempFile(output.getParent(), ".pod-lineage-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                command.addAll(List.of("--out", temporary.toString(), "--old-signer"));
                signer(command, env, oldSigner(options), "POD_SIGN_OLD");
                command.add("--new-signer");
                signer(command, env, properties(options.get("properties")), "POD_SIGN_NEW");
            } else if (args[0].equals("sign")) {
                Files.createDirectories(output.getParent());
                temporary = Files.createTempFile(output.getParent(), ".pod-signed-", ".apk");
                command.addAll(List.of("--out", temporary.toString(), "--v1-signing-enabled", "false",
                    "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                    "--debuggable-apk-permitted", "false", "--alignment-preserved", "true"));
                if (options.containsKey("lineage")) {
                    command.addAll(List.of("--lineage", options.get("lineage"), "--rotation-min-sdk-version", options.getOrDefault("rotation-min-sdk", "28")));
                    signer(command, env, oldSigner(options), "POD_SIGN_OLD");
                    command.add("--next-signer");
                }
                signer(command, env, properties(options.get("properties")), "POD_SIGN_NEW");
                command.add(options.get("in"));
            } else throw new Exception();
            execute(command, env);
            // Validate output before publishing; verifier diagnostics also remain private.
            if (args[0].equals("sign")) execute(List.of(options.get("apksigner"), "verify", "--verbose", temporary.toString()), Map.of());
            if (args[0].equals("rotate")) Files.move(temporary, output); // Never replace an existing lineage.
            else Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            System.out.println("pod: signing operation succeeded");
        } catch (Exception error) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            System.err.println("pod: signing operation failed (credentials and signer diagnostics withheld)");
            System.exit(1);
        }
    }
}
