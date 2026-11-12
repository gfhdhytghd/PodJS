package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import dev.podjs.takeover.NativeBackup;
import dev.podjs.takeover.TakeoverDigest;
import java.io.*;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONObject;

/** Package owner policy, never guest controlled. Called under the coordinator lock. */
final class PodTakeoverBackup implements PodTakeoverCoordinator.Publisher {
    final Context context;final File sandbox;
    private final java.util.function.Consumer<Runnable> summaries;
    PodTakeoverBackup(Context context,File sandbox){this(context,sandbox,task->PodActiveValidationAndroid.activeSummary(context,task));}
    PodTakeoverBackup(Context context,File sandbox,java.util.function.Consumer<Runnable> summaries){
        this.context=context;this.sandbox=sandbox;this.summaries=summaries;
    }
    private Bundle config() throws Exception {
        Bundle b=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
        return b==null?new Bundle():b;
    }
    static String certificate(Context context) throws Exception {
        android.content.pm.Signature[] signers=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
        if(signers.length!=1)throw new IOException("backup_signer_unsupported");
        return PodRoomV18Converter.hex(java.security.MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
    }
    static Properties snapshot(File sandbox,File g) throws Exception {
        File receipt=new File(g,"takeover-digest-snapshot.properties");
        if(receipt.exists())return PodTakeoverCoordinator.read(receipt);
        // Converter manifests already hold hashes for media, stores and raw DB/WAL.
        Map<String,String> hashes=new HashMap<>();
        JSONObject files=PodStoreConverter.json(new File(g,"files-snapshot.json")).getJSONObject("entries");
        for(String path:PodFileConverter.names(files))hashes.put(path,files.getJSONObject(path).getString("sha256"));
        JSONObject stores=PodStoreConverter.json(new File(g,"stores-snapshot.json")).getJSONObject("entries");
        for(String path:PodFileConverter.names(stores))hashes.put(path,stores.getString(path));
        String[] database=PodTakeoverCoordinator.read(new File(g,"snapshot.properties")).getProperty("sourceDigest","").split(":");
        if(database.length!=2)throw new IOException("digest_source_missing");
        hashes.put("databases/watchrss.db",database[0]);
        if(!"absent".equals(database[1]))hashes.put("databases/watchrss.db-wal",database[1]);
        Properties snapshot=TakeoverDigest.snapshot(sandbox,hashes);
        TakeoverDigest.digestManifest(snapshot,"SNAPSHOTTED",g.getName());
        PodTakeoverCoordinator.atomic(receipt,snapshot);return snapshot;
    }
    public void beforeReclassification()throws Exception {
        // Even a partial seal/move is publication evidence, including an older generation.
        for(String path:new String[]{NativeBackup.BACKUP,NativeBackup.RECOVERED,"files/.podjs-restore-stage","files/.podjs-recovery-state"})
            if(java.nio.file.Files.exists(new File(sandbox,path).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected_activation");
    }
    public void afterSnapshot(File g) throws Exception {
        try{emit(TakeoverDigest.digestManifest(snapshot(sandbox,g),"SNAPSHOTTED",g.getName()));}catch(Exception failure){unavailable(failure);}
    }
    /** Digest lines are evidence for the host-side check, never a gate on the migration itself. */
    private static void unavailable(Exception failure){android.util.Log.w("PodTakeoverDigest","{\"result\":\"unavailable\",\"error\":\""+failure.getClass().getSimpleName()+"\"}");}
    static void emit(java.util.List<String> lines) {
        for(String line:lines)android.util.Log.i("PodTakeoverDigest",line);
    }
    private static void activeSummary(java.util.concurrent.Callable<java.util.List<String>> lines) {
        long began=System.nanoTime();
        try{emit(lines.call());}catch(Exception failure){unavailable(failure);}
        finally{PodTakeoverDiagnostics.timed("activeDigestSummary",System.nanoTime()-began);}
    }
    static void verifySnapshot(File sandbox,File g)throws IOException {
        Properties p=PodTakeoverCoordinator.read(new File(g,"takeover-digest-snapshot.properties"));
        if(!TakeoverDigest.digestManifest(p,"ACTIVE",g.getName()).equals(TakeoverDigest.activeLines(sandbox,g.getName())))
            throw new IOException("digest_snapshot_changed");
    }
    static void checkBackupControls(File sandbox,String generation)throws IOException {
        File directory=new File(sandbox,NativeBackup.BACKUP+"/"+generation);
        PodTakeoverCoordinator.requirePrivateDirectory(directory);
        if(!java.nio.file.Files.exists(directory.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
        File[] children=directory.listFiles();if(children==null)throw new IOException("migration_storage_invalid");
        for(File child:children) {
            if(child.getName().equals("data")){PodTakeoverCoordinator.requirePrivateDirectory(child);continue;}
            if(!java.util.Arrays.asList("manifest","manifest.tmp","seal","seal.tmp","ready","ready.tmp").contains(child.getName()))throw new IOException("migration_storage_invalid");
            PodTakeoverCoordinator.requirePrivateFile(child);
        }
    }
    public void beforePublish(File g) throws Exception {
        checkBackupControls(sandbox,g.getName());
        int version=config().getInt("dev.podjs.takeover.sourceVersionCode",0);
        String digest=new NativeBackup(sandbox).prepare(g.getName(),version,certificate(context));
        // Evidence only: the host-side post check compares digests. A mismatch must never strand
        // a takeover whose sources prepare() has already moved into the sealed backup.
        try{verifySnapshot(sandbox,g);}catch(IOException mismatch){android.util.Log.w("PodTakeoverDigest","{\"result\":\""+mismatch.getMessage()+"\"}");}
        File receipt=new File(g,"native-backup-receipt");Properties previous=PodTakeoverCoordinator.read(receipt);
        if(!previous.isEmpty()&&!digest.equals(previous.getProperty("sha256")))throw new IOException("backup_receipt_changed");
        Properties p=new Properties();p.setProperty("sha256",digest);PodTakeoverCoordinator.atomic(receipt,p);
    }
    public void afterActive(File g) throws Exception {
        int threshold=config().getInt("dev.podjs.takeover.purgeBackupAfter",0);
        long current=context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
        boolean purge=threshold>0&&current>=threshold;
        // Retain raw sealed metadata only when synchronous purge will remove it.
        // Normal admission does not open/parse/hash the manifest at all.
        if(!purge||new File(sandbox,NativeBackup.BACKUP+"/"+g.getName()+"/manifest").exists()) {
            if(purge) {
                try {
                    TakeoverDigest.ActiveMetadata metadata=TakeoverDigest.captureActive(sandbox,g.getName());
                    summaries.accept(()->activeSummary(metadata::lines));
                } catch(Exception failure) {summaries.accept(()->activeSummary(()->{throw failure;}));}
            } else summaries.accept(()->activeSummary(()->TakeoverDigest.activeLines(sandbox,g.getName())));
        }
        if(threshold>0&&current>=threshold) {
            NativeBackup backup=new NativeBackup(sandbox);
            for(String retained:backup.retainedGenerations())backup.purge(retained);
            backup.purgeImportSnapshots(g.getName());
        }
        // Opt-in host policy: "inactive", or comma-separated explicit generation IDs.
        // Absent/empty remains disabled; no installation-specific IDs are built in.
        String approved=config().getString("dev.podjs.takeover.inactiveGenerationGc","");
        if(!approved.isEmpty()) {
            java.util.List<String> policy=java.util.Arrays.asList(approved.split(",",-1));
            // Wait for this admission lock to leave the foreground path. Revalidate
            // pointers/recovery protection under that lock before every idle batch.
            new Thread(()->{
                try {
                    for(int pass=0;pass<16;pass++) {
                        PodInactiveGenerationCollector.Result result=PodTakeover.collectInactiveGenerations(context,policy,128);
                        if(result.deletedEntries<128)break;
                        Thread.sleep(25);
                    }
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch(Exception failure) {
                    // Collection must never change ACTIVE admission on failure.
                    android.util.Log.w("PodTakeoverGc","inactive generation collection deferred");
                }
            },"PodTakeoverGc").start();
        }
    }
}
