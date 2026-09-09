package dev.podjs.runtime;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;

/** Installed-APK-only owner. Invoke after native package validation succeeds.
 * Neither manifest capabilities nor the local identity authorize a remote peer. */
final class PodInstalledSync implements AutoCloseable {
    final PodSyncClient client;
    final PodSyncServices services;
    final PodSyncHostConnection connection;
    private PodInstalledSync(Context context, HashSet<String> grants) throws Exception {
        client=new PodSyncClient(context.getApplicationContext(),context.getPackageName(),localIdentity(context));
        services=new PodSyncServices(client,grants,null,new File(context.getFilesDir(),"podjs/files"));
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        connection=new PodSyncHostConnection(client,services,main::post);
    }
    static PodInstalledSync open(Context context,String target) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream input=context.getAssets().open("pod.manifest.json")) {
            byte[] block=new byte[4096]; int count;
            while((count=input.read(block))!=-1) {
                if(bytes.size()+count>128*1024) throw new IllegalArgumentException("Manifest too large");
                bytes.write(block,0,count);
            }
        }
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        HashSet<String> grants=approvedCapabilities(new JSONObject(json),target);
        return grants.isEmpty()?null:new PodInstalledSync(context,grants);
    }
    static HashSet<String> approvedCapabilities(JSONObject manifest,String target) throws Exception {
        if(manifest.getInt("schema")!=1 || !target.equals(manifest.getString("target"))) throw new SecurityException("Sync package mismatch");
        JSONArray capabilities=manifest.getJSONArray("capabilities");
        HashSet<String> result=new HashSet<>();
        for(int i=0;i<capabilities.length();i++) {
            Object raw=capabilities.get(i);
            if(!(raw instanceof String)) throw new SecurityException("Invalid package capability");
            String cap=(String)raw;
            if(cap.equals("companion.sync.state") || cap.equals("companion.sync.message") || cap.equals("companion.sync.file")) result.add(cap);
        }
        return result;
    }
    /** No backup: restoring another device's state must not clone this identity.
     * Corrupt existing identity fails closed instead of silently rotating it. */
    static synchronized String localIdentity(Context context) throws Exception {
        File root=new File(context.getNoBackupFilesDir(),"podjs-sync-owner");
        if(!root.mkdirs() && !root.isDirectory()) throw new java.io.IOException("Sync identity directory unavailable");
        AtomicFile file=new AtomicFile(new File(root,"identity"));
        if(file.getBaseFile().exists() || new File(root,"identity.bak").exists()) {
            ByteArrayOutputStream stored=new ByteArrayOutputStream();
            try(InputStream input=file.openRead()) {
                byte[] block=new byte[129]; int count;
                while((count=input.read(block))!=-1) {
                    if(stored.size()+count>128) throw new java.io.IOException("Invalid sync identity");
                    stored.write(block,0,count);
                }
            }
            byte[] data=stored.toByteArray();
            String id=new String(data,StandardCharsets.UTF_8);
            if(!id.matches("device-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new java.io.IOException("Invalid sync identity");
            return id;
        }
        String id="device-"+UUID.randomUUID(); FileOutputStream output=file.startWrite();
        try { output.write(id.getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
        catch(Exception error) { file.failWrite(output); throw error; }
        return id;
    }
    @Override public void close() throws Exception { connection.close(); client.close(); }
}
