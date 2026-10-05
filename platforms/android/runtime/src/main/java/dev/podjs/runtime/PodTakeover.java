package dev.podjs.runtime;

import android.content.Context;
import java.io.*;
import java.util.Properties;
import org.json.JSONObject;

/** All Android entry points and production storage roots use this fail-closed gate.
 * No runtime can coexist with activation: blocked entries never construct a runtime,
 * ACTIVE is terminal, and there is deliberately no guest mutation/activation API. */
public final class PodTakeover {
    private PodTakeover() {}
    private static PodTakeoverCoordinator coordinator(Context c) {
        return new PodTakeoverCoordinator(new File(c.getFilesDir(),"package-takeover"),
                () -> detect(c),new PodTakeoverConverters(new PodRoomV18Converter(c.getDatabasePath("watchrss.db"),
                    PodRoomAndroidDb::new,(File generation) -> PodStoreConverter.identity(new File(generation,"stores-source"))),
                    new PodStoreConverter(new File(c.getApplicationInfo().dataDir),new PodStoreAndroidCrypto(c))));
    }
    // Keyed by Android's in-memory sandbox path, not Activity identity. Distinct
    // fixture/storage contexts must not accidentally share an admitted generation.
    private static final java.util.concurrent.ConcurrentHashMap<String,PodTakeoverAdmission> admissions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static PodTakeoverAdmission.Paths paths(Context c) throws Exception {
        PodTakeoverAdmission admission=admissions.computeIfAbsent(c.getApplicationInfo().dataDir,
                ignored -> new PodTakeoverAdmission());
        return admission.paths(() -> PodTakeoverAdmission.Paths.resolve(coordinator(c),
                new File(c.getFilesDir(),"podjs"),c.getDatabasePath("podjs.sqlite")));
    }
    private static PodTakeoverAdmission.Paths requiredPaths(Context c) {
        try { return paths(c); }
        catch(Exception failure){throw new IllegalStateException("takeover_pending");}
    }
    public static boolean ready(Context c) {
        try { paths(c); return true; }
        catch(Exception failure){return false;}
    }
    public static void requireReady(Context c) { requiredPaths(c); }
    public static File dataRoot(Context c) { return requiredPaths(c).data; }
    public static File filesRoot(Context c) { return requiredPaths(c).files; }
    public static File database(Context c) { return requiredPaths(c).database; }
    /** Read-only public projection: no paths, source values, secrets or mutable operation. */
    public static JSONObject status(Context c) {
        JSONObject result=new JSONObject();
        try {
            Properties p=PodTakeoverCoordinator.read(new File(c.getFilesDir(),"package-takeover/journal"));
            result.put("protocol",1).put("phase",p.getProperty("phase","NONE"));
            for(String key:new String[]{"reason","resumePhase","requiredBytes","availableBytes"})
                if(p.containsKey(key))result.put(key,p.getProperty(key));
        } catch(Exception error){try{result.put("phase","BLOCKED").put("reason","journal_unreadable");}catch(Exception ignored){}}
        return result;
    }
    /** Native fallback is necessary because ordinary guest JS must not run before admission. */
    public static boolean showRecoveryIfNeeded(android.app.Activity activity) {
        if(ready(activity))return false;
        android.widget.TextView view=new android.widget.TextView(activity);
        view.setGravity(android.view.Gravity.CENTER); view.setText("数据迁移尚未完成\n"+status(activity).optString("phase","BLOCKED"));
        activity.setContentView(view); return true;
    }
    static PodTakeoverCoordinator.Source detect(Context c) throws IOException {
        File db=c.getDatabasePath("watchrss.db");
        File prefs=new File(c.getApplicationInfo().dataDir,"shared_prefs");
        File stores=new File(c.getFilesDir(),"datastore");
        Properties inventory=new Properties();
        inventory.setProperty("source.database",Boolean.toString(db.exists()));
        inventory.setProperty("source.wal",Boolean.toString(new File(db+"-wal").exists()));
        boolean present=db.exists() || new File(db+"-wal").exists();
        // Native source supplies a suffix to preferencesDataStoreFile; retain both
        // known names so a version that corrected the doubled suffix is detected.
        for(String name:new String[]{"settings.preferences_pb","settings.preferences_pb.preferences_pb","bili_cache.preferences_pb","bili_cache.preferences_pb.preferences_pb"}) {
            boolean exists=new File(stores,name).exists();present |= exists;
            inventory.setProperty("source.store."+name,Boolean.toString(exists));
        }
        for(String name:new String[]{"reader_preset_state","home_entry_placement","app_resume_state","douyin_watch_history","douyin_recent_window","douyin_feed_cache","watchrss_announcement","watchrss_push","watch_device_identity","watchrss_installation_identity","watchrss_daily_telemetry","watchrss_download_telemetry","watchrss_cloud_state","watchrss_account_state","llm_secure_prefs","tts_secure_prefs","watchrss_cloud_keys"})
        {
            boolean exists=new File(prefs,name+".xml").exists();present |= exists;
            inventory.setProperty("source.store."+name,Boolean.toString(exists));
        }
        for(String name:new String[]{"article_content","offline","reader_assets","notes","watch-cloud-cache"})
            present |= new File(c.getFilesDir(),name).exists();
        present |= nonempty(prefs) || nonempty(stores);
        if(!present)return new PodTakeoverCoordinator.Source(false,null,0,0,0);
        int schema=-1; String block=null;
        // Version and Room identity/columns are checked on the recovered private
        // snapshot, including uncheckpointed WAL. Never inspect a stale header.
        if(!db.isFile())block="source_database_missing";
        else schema=18; // expected contract; converter proves it before COPYING
        if(c.getDatabasePath("podjs.sqlite").exists() || nonempty(new File(c.getFilesDir(),"podjs")))block="target_conflict";
        File[] auxiliary=c.getNoBackupFilesDir().listFiles();
        if(auxiliary==null)throw new IOException("target_unreadable");
        for(File target:auxiliary)if(target.getName().startsWith("podjs-") && nonempty(target))block="target_conflict";
        android.os.UserManager users=(android.os.UserManager)c.getSystemService(Context.USER_SERVICE);
        if(users!=null && !users.isUserUnlocked())block="user_locked";
        long bytes=size(db)+size(new File(db+"-wal"))+size(prefs)+size(stores);
        for(String name:new String[]{"article_content","offline","reader_assets","notes","watch-cloud-cache"})bytes=Math.addExact(bytes,size(new File(c.getFilesDir(),name)));
        long required=Math.addExact(Math.multiplyExact(bytes,3),32L*1024*1024);
        PodTakeoverCoordinator.Source result=new PodTakeoverCoordinator.Source(true,block,schema,required,c.getFilesDir().getUsableSpace());
        result.inventory.putAll(inventory);return result;
    }
    private static boolean nonempty(File f) throws IOException {return f.exists() && (!f.isDirectory() || f.list()==null || f.list().length>0);}
    private static long size(File f) throws IOException {
        if(java.nio.file.Files.isSymbolicLink(f.toPath()))throw new IOException("source_symlink");
        if(!f.exists())return 0;
        if(!f.isDirectory())return f.length();
        File[] children=f.listFiles();if(children==null)throw new IOException("source_unreadable");
        long total=0;for(File child:children)total=Math.addExact(total,size(child));return total;
    }
}
