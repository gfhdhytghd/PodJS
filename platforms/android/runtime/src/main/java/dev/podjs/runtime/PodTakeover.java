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
        File sandbox=new File(c.getApplicationInfo().dataDir);
        return new PodTakeoverCoordinator(new File(sandbox,"files/package-takeover"),
                () -> detect(c),new PodTakeoverConverters(new PodRoomV18Converter(new File(sandbox,"databases/watchrss.db"),
                    PodRoomAndroidDb::new,(File generation) -> PodStoreConverter.identity(new File(generation,"stores-source"))),
                    new PodStoreConverter(new File(c.getApplicationInfo().dataDir),new PodStoreAndroidCrypto(c)),
                    new PodFileConverter(new File(c.getApplicationInfo().dataDir),PodRoomAndroidDb::new,(file,faces) -> {
                        for(int i=0;i<faces;i++)if(new android.graphics.Typeface.Builder(file).setTtcIndex(i).build()==null)
                            throw new IOException("font_unloadable");
                    })));
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
        // No Context directory getter here: those may mkdir even on read-only preflight.
        android.os.UserManager users=(android.os.UserManager)c.getSystemService(Context.USER_SERVICE);
        return PodTakeoverSource.detect(new File(c.getApplicationInfo().dataDir),users==null||users.isUserUnlocked());
    }
}
