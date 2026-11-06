package dev.podjs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Immutable host-approved manifest snapshot. The host must authenticate the
 * package and authorize its app identity before constructing this object; hashes
 * establish integrity, not publisher trust. Never expose this constructor or
 * the asset reader to guest JavaScript.
 */
public final class PodBackgroundPackage {
    public interface Assets { InputStream open(String relativePath) throws IOException; }
    private final String app;
    private final JSONObject handlers;
    private final Assets assets;
    private final String[] grants;
    static String[] validateGrants(org.json.JSONArray array) throws Exception {
        if (array==null) return new String[0];
        if (array.length()>6) throw new SecurityException("Too many background grants");
        java.util.HashSet<String> seen=new java.util.HashSet<>();String[] result=new String[array.length()];
        for (int i=0;i<result.length;i++) {
            Object raw=array.get(i);
            if (!(raw instanceof String) || !java.util.Arrays.asList("kv.get","kv.set","kv.delete","kv.keys","cloud.host","debug.cloudHost").contains(raw) || !seen.add((String)raw))
                throw new SecurityException("Invalid background grant");
            result[i]=(String)raw;
        }
        return result;
    }
    /** APK assets belong to the OS-installed application. This path must not be
     * reused for downloaded packages; those need independent authentication. */
    static PodBackgroundPackage installed(android.content.Context context, String target) throws Exception {
        android.content.res.AssetManager manager=context.getAssets();
        JSONObject manifest;
        try(InputStream input=manager.open("pod.manifest.json")) { manifest=PodManifest.read(input); }
        if (!target.equals(manifest.getString("target"))) throw new SecurityException("Background target mismatch");
        if (!manifest.has("background")) manifest.put("background",new JSONObject());
        return new PodBackgroundPackage(context.getPackageName(),manifest,manager::open);
    }
    public PodBackgroundPackage(String authorizedApp, JSONObject verifiedManifest, Assets assets) throws Exception {
        PodBackgroundScheduler.id(authorizedApp);
        if (verifiedManifest.getInt("schema") != 1 || assets == null)
            throw new IllegalArgumentException("Unsupported background package");
        this.app=authorizedApp;
        this.assets=assets;
        this.handlers=new JSONObject(verifiedManifest.getJSONObject("background").toString());
        this.grants=validateGrants(verifiedManifest.has("backgroundServices")?verifiedManifest.getJSONArray("backgroundServices"):null);
        if (handlers.length()>32) throw new IllegalArgumentException("Too many background handlers");
        java.util.Iterator<String> names=handlers.keys();
        while (names.hasNext()) {
            String name=names.next(); PodBackgroundScheduler.id(name);
            JSONObject item=handlers.getJSONObject(name);
            if (!item.getString("file").matches("background/[0-9a-f]{64}\\.js")
                    || !item.getString("sha256").matches("[0-9a-f]{64}")
                    || !(item.get("bytes") instanceof Number)
                    || item.getDouble("bytes") != item.getLong("bytes")
                    || item.getLong("bytes")<1 || item.getLong("bytes")>1024*1024)
                throw new IllegalArgumentException("Invalid background artifact");
        }
    }
    /** Call on a host IO worker. Handler IDs are resolved exclusively in the
     * approved manifest; the caller cannot supply a path, source or hash. */
    public UUID schedule(PodBackgroundScheduler scheduler, String handler, long earliestAt,
            long budgetMs, boolean requiresNetwork, Object payload) throws Exception {
        return schedule(scheduler,handler,handler,earliestAt,budgetMs,requiresNetwork,payload);
    }
    public UUID schedule(PodBackgroundScheduler scheduler, String task, String handler, long earliestAt,
            long budgetMs, boolean requiresNetwork, Object payload) throws Exception {
        PodBackgroundScheduler.id(task);
        PodBackgroundScheduler.id(handler);
        JSONObject item=handlers.optJSONObject(handler);
        if (item==null) throw new SecurityException("Undeclared background handler");
        int expected=item.getInt("bytes");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(expected);
        try (InputStream input=assets.open(item.getString("file"))) {
            byte[] block=new byte[8192]; int count;
            while ((count=input.read(block))!=-1) {
                if (bytes.size()+count>expected) throw new SecurityException("Background artifact size mismatch");
                bytes.write(block,0,count);
            }
        }
        if (bytes.size()!=expected) throw new SecurityException("Background artifact size mismatch");
        String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        return scheduler.scheduleVerified(app,task,source,item.getString("sha256"),earliestAt,budgetMs,requiresNetwork,payload,grants);
    }
    /** Reads the current installed cloud handler, never a persisted guest path. */
    String cloudHostMethod(boolean debugBuild) {
        java.util.List<String> allowed=java.util.Arrays.asList(grants);
        if(!allowed.contains("kv.get"))throw new SecurityException("Cloud background grant missing");
        if(allowed.contains("cloud.host"))return "cloud.host";
        if(debugBuild&&allowed.contains("debug.cloudHost"))return "debug.cloudHost";
        throw new SecurityException("Cloud background grant missing");
    }
    String cloudSource(boolean debugBuild) throws Exception {
        cloudHostMethod(debugBuild);
        JSONObject item=handlers.optJSONObject("watchrss-cloud-sync");
        if(item==null)throw new SecurityException("Cloud background handler missing");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream input=assets.open(item.getString("file"))){byte[] block=new byte[8192];int n;
            while((n=input.read(block))!=-1){if(output.size()+n>item.getInt("bytes"))throw new SecurityException("Cloud source too large");output.write(block,0,n);}}
        String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        if(output.size()!=item.getInt("bytes")||!PodBackgroundScheduler.sha256(source).equals(item.getString("sha256")))throw new SecurityException("Cloud source hash mismatch");
        return source;
    }
    /** Foreground lifecycle calls authenticate the handler but do not execute it. */
    void verifyCloudSource(boolean debugBuild) throws Exception {
        cloudHostMethod(debugBuild);
        JSONObject item=handlers.optJSONObject("watchrss-cloud-sync");
        if(item==null)throw new SecurityException("Cloud background handler missing");
        PodUtf8Artifact.verify(assets.open(item.getString("file")),item.getLong("bytes"),item.getString("sha256"));
    }
    boolean permitsSource(String hash, String[] methods) throws Exception {
        if (!java.util.Arrays.asList(grants).containsAll(java.util.Arrays.asList(methods))) return false;
        java.util.Iterator<String> names=handlers.keys();
        while (names.hasNext()) if (hash.equals(handlers.getJSONObject(names.next()).getString("sha256"))) return true;
        return false;
    }
    String appId() { return app; }
}
