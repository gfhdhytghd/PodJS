package dev.podjs.runtime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * Privacy-safe desktop replay for a read-only native sandbox copy. The source is
 * never modified, values and paths are never printed, and the private candidate
 * is removed after each run.
 */
public final class TakeoverBenchmark {
    interface Step { void run() throws Exception; }

    private static long timed(String name, Step step) throws Exception {
        long started=System.nanoTime();
        step.run();
        long millis=(System.nanoTime()-started)/1_000_000L;
        System.out.println("takeover_benchmark step="+name+" elapsed_ms="+millis);
        return millis;
    }

    private static void remove(File root) throws Exception {
        if(root==null||!root.exists())return;
        try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)
                Files.delete(path);
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length==2&&args[0].equals("--resume-room")) {
            File generation=new File(args[1]).getCanonicalFile();
            if(!new File(generation,"snapshot.properties").isFile())throw new IllegalArgumentException("candidate snapshot missing");
            PodRoomV18Converter room=new PodRoomV18Converter(new File(generation,"unused-source"),RoomV18Test.Jdbc::new,"unused-identity");
            long copy=timed("resume_copy_room",()->room.copy(generation));
            long verify=timed("resume_verify_room",()->room.verify(generation));
            System.out.println("takeover_benchmark resume_summary copying_verifying_ms="+(copy+verify)+" completed=true");
            return;
        }
        boolean isolated=args.length==2&&args[0].equals("--isolated-stores");
        if(args.length!=1&&!isolated)throw new IllegalArgumentException("usage: TakeoverBenchmark [--isolated-stores] <read-only-native-copy>");
        File source=new File(args[isolated?1:0]).getCanonicalFile();
        File database=new File(source,"databases/watchrss.db");
        if(!database.isFile())throw new IllegalArgumentException("native database missing");
        Map<String,String> before=StoreTest.digests(source);
        File generation=Files.createTempDirectory("watchrss-takeover-benchmark-").toFile();
        try {
            StoreTest.Crypto crypto=new StoreTest.Crypto();
            // A copied Android sandbox cannot carry UID-bound Keystore keys into
            // the desktop replay. Inject a missing-key fault to test the policy instead of
            // pretending the ciphertext is plaintext.
            crypto.unavailable=true;
            PodRoomV18Converter room=new PodRoomV18Converter(database,RoomV18Test.Jdbc::new,
                    (File g)->PodStoreConverter.identity(new File(g,"stores-source")));
            PodStoreConverter stores=new PodStoreConverter(source,crypto,isolated);
            PodFileConverter files=new PodFileConverter(source,RoomV18Test.Jdbc::new,(file,faces)->{});

            long snapshot=timed("snapshot",()->{
                stores.snapshot(generation);
                room.snapshot(generation);
                if(!PodStoreConverter.identity(new File(generation,"stores-source")).trim().equals(
                        PodTakeoverCoordinator.read(new File(generation,"snapshot.properties")).getProperty("identity")))
                    throw new java.io.IOException("identity_snapshot_mismatch");
                files.snapshot(generation);
            });
            long roomCopy=timed("copy_room",()->room.copy(generation));
            long roomCopyVerify=timed("copy_room_verify",()->room.verify(generation));
            long storeCopy=timed("copy_stores",()->stores.copy(generation));
            long fileCopy=timed("copy_files",()->files.copy(generation));
            long roomVerify=timed("verify_room",()->room.verify(generation));
            long fileVerify=timed("verify_files",()->files.verify(generation));
            if(isolated){
                timed("verify_stores_injected_missing_key",()->stores.verify(generation));
                org.json.JSONObject diagnostics=PodStoreConverter.json(new File(generation,"stores-diagnostics.json"));
                String blocker=new PodTakeoverConverters(room,stores,files).blocker(generation);
                System.out.println("takeover_benchmark injected_missing_key=true android_keystore_verified=false font_runtime_verified=false store_blockers="+diagnostics.getInt("blockerCount")+" known_differences="+diagnostics.getInt("knownDifferenceCount")+" bundle_blocker="+(blocker==null?"none":blocker));
                System.out.println(diagnostics.toString());
                if(blocker==null){
                    String app=System.getenv().getOrDefault("WATCHRSS_APP_SOURCE","/home/wilf/data/OPPO_Develop/PodJS_WatchRSS");
                    File output=new File(generation,"oracle-result.json");
                    ProcessBuilder oracle=new ProcessBuilder("python3",new File(app,"scripts/migration/takeover_oracle.py").toString(),"--native",source.toString(),"--candidate",generation.toString());
                    oracle.environment().put("PYTHONDONTWRITEBYTECODE","1");
                    Process process=oracle.redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    int result=process.waitFor();
                    if(result!=0)throw new AssertionError("private_core_oracle_failed");
                    org.json.JSONObject checked=PodStoreConverter.json(output);
                    if(!checked.getBoolean("pass"))throw new AssertionError("private_core_oracle_failed");
                    System.out.println("takeover_benchmark core_oracle="+checked.toString());
                }
            }
            long copying=roomCopy+roomCopyVerify+fileCopy;
            long verifying=roomVerify+fileVerify;
            if(!before.equals(StoreTest.digests(source)))throw new AssertionError("source bytes changed");
            System.out.println("takeover_benchmark summary scope=p2b_p2d snapshot_ms="+snapshot+" store_copy_ms="+storeCopy+
                    " copying_ms="+copying+" verifying_ms="+verifying+
                    " copying_verifying_ms="+(copying+verifying)+" source_unchanged=true");
        } finally {
            remove(generation);
        }
    }
}
