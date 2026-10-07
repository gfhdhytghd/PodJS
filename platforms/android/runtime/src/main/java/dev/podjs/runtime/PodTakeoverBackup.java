package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import dev.podjs.takeover.NativeBackup;
import java.io.*;
import java.util.Properties;

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
    public void beforePublish(File g) throws Exception {
        int version=config().getInt("dev.podjs.takeover.sourceVersionCode",0);
        String digest=new NativeBackup(sandbox).prepare(g.getName(),version,certificate(context));
        File receipt=new File(g,"native-backup-receipt");Properties previous=PodTakeoverCoordinator.read(receipt);
        if(!previous.isEmpty()&&!digest.equals(previous.getProperty("sha256")))throw new IOException("backup_receipt_changed");
        Properties p=new Properties();p.setProperty("sha256",digest);PodTakeoverCoordinator.atomic(receipt,p);
    }
    public void afterActive(File g) throws Exception {
        int threshold=config().getInt("dev.podjs.takeover.purgeBackupAfter",0);
        long current=context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
        if(threshold>0&&current>=threshold) {
            NativeBackup backup=new NativeBackup(sandbox);
            for(String retained:backup.retainedGenerations())backup.purge(retained);
            backup.purgeImportSnapshots(g.getName());
        }
    }
}
