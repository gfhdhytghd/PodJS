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
    PodTakeoverBackup(Context context,File sandbox){this.context=context;this.sandbox=sandbox;}
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
    static void verifySnapshot(File sandbox,File g)throws IOException {
        Properties p=PodTakeoverCoordinator.read(new File(g,"takeover-digest-snapshot.properties"));
        if(!TakeoverDigest.digestManifest(p,"ACTIVE",g.getName()).equals(TakeoverDigest.activeLines(sandbox,g.getName())))
            throw new IOException("digest_snapshot_changed");
    }
    public void beforePublish(File g) throws Exception {
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
        // A previous admission may already have purged under owner policy.
        // Missing backups otherwise remain an error; no digest is fabricated.
        if(new File(sandbox,NativeBackup.BACKUP+"/"+g.getName()+"/manifest").exists()||threshold<=0||current<threshold)
            try{emit(TakeoverDigest.activeLines(sandbox,g.getName()));}catch(Exception failure){unavailable(failure);}
        if(threshold>0&&current>=threshold) {
            NativeBackup backup=new NativeBackup(sandbox);
            for(String retained:backup.retainedGenerations())backup.purge(retained);
            backup.purgeImportSnapshots(g.getName());
        }
    }
}
