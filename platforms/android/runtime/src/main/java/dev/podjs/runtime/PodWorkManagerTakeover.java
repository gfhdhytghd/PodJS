package dev.podjs.runtime;

import android.content.Context;
import android.util.AtomicFile;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Legacy identities are supplied by the installed app, never by guest JS.
 * Factory creation does not initialize WorkManager or run package migration.
 * Cancellation runs only after successful ACTIVE admission, before guest startup.
 */
public final class PodWorkManagerTakeover {
    private PodWorkManagerTakeover() {}
    private static final Map<String,Config> configs=new HashMap<>();
    private static final Set<String> completed=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Object CLEANUP_LOCK=new Object();
    static final class Config {
        final String appId;
        final List<String> classes,names,tags;
        final String fingerprint;
        Config(JSONObject value)throws Exception {
            appId=value.getString("nativeAppId");
            if(!appId.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))throw new IOException("legacy_config_invalid");
            classes=list(value,"workerClasses",true);names=list(value,"uniqueWorkNames",false);tags=list(value,"tags",false);
            if(classes.isEmpty()||(names.isEmpty()&&tags.isEmpty()))throw new IOException("legacy_config_invalid");
            // Canonical field/list order, independent of JSONObject formatting.
            String canonical=new JSONArray().put(appId).put(new JSONArray(classes)).put(new JSONArray(names)).put(new JSONArray(tags)).toString();
            StringBuilder hash=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(Locale.ROOT,"%02x",b&255));
            fingerprint=hash.toString();
        }
        private static List<String> list(JSONObject value,String key,boolean isClass)throws Exception {
            JSONArray a=value.getJSONArray(key);if(a.length()>64)throw new IOException("legacy_config_invalid");
            ArrayList<String> result=new ArrayList<>();
            for(int i=0;i<a.length();i++) {
                Object raw=a.get(i);if(!(raw instanceof String))throw new IOException("legacy_config_invalid");
                String item=(String)raw;
                if(item.length()>256||!item.matches(isClass?"[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+":"[A-Za-z0-9_.:$-]{1,256}")||item.startsWith("dev.podjs.")||item.startsWith("podjs")||result.contains(item))throw new IOException("legacy_config_invalid");
                result.add(item);
            }
            return Collections.unmodifiableList(result);
        }
    }
    private static synchronized Config installed(Context c) {
        String key=c.getApplicationInfo().dataDir;
        if(configs.containsKey(key))return configs.get(key);
        JSONObject manifest;
        try(InputStream in=c.getAssets().open("pod.manifest.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>1024*1024)throw new IOException("legacy_config_invalid");bytes.write(buffer,0,n);}
            manifest=new JSONObject(bytes.toString("UTF-8"));
        } catch(FileNotFoundException absent) { configs.put(key,null);return null; }
        catch(Exception invalid) { throw new IllegalStateException("legacy_config_invalid"); }
        Config config=null;
        if(manifest.has("androidWorkManagerTakeover"))try {
            Config parsed=new Config(manifest.getJSONObject("androidWorkManagerTakeover"));
            if(c.getPackageName().equals(parsed.appId))config=parsed;
        } catch(Exception invalid) { throw new IllegalStateException("legacy_config_invalid"); }
        configs.put(key,config);return config;
    }
    public static WorkerFactory factory(Context c) {return factory(installed(c));}
    static WorkerFactory factory(Config config) {
        return new WorkerFactory() {
            @Override public ListenableWorker createWorker(Context context,String name,WorkerParameters parameters) {
                // Returning null preserves WorkManager's default factory for every other worker.
                return config!=null&&config.classes.contains(name)?new RetiredWorker(context,parameters):null;
            }
        };
    }
    public static final class RetiredWorker extends Worker {
        public RetiredWorker(Context c,WorkerParameters parameters){super(c,parameters);}
        @Override public Result doWork() {
            // Never initiate migration from the worker: it can race Application/Activity startup.
            if(!"ACTIVE".equals(PodTakeover.status(getApplicationContext()).optString("phase")))return Result.retry();
            WorkManager.getInstance(getApplicationContext()).cancelWorkById(getId());
            return Result.success();
        }
    }
    static boolean completedInProcess(Context c,File admittedData) {
        Config config=installed(c);
        if(config==null||admittedData.equals(new File(c.getApplicationInfo().dataDir,"files/podjs")))return true;
        return completed.contains(key(c,admittedData.getParentFile().getName(),config));
    }
    private static String key(Context c,String generation,Config config){return c.getApplicationInfo().dataDir+":"+generation+":"+config.fingerprint;}
    static void afterAdmission(Context c,String generation)throws Exception {
        Config config=installed(c);if(config!=null)retire(c,generation,config,WorkManager.getInstance(c));
    }
    /** IO-thread only. WorkManager operations and the receipt are awaited in order.
     * Crash before receipt: replay safe cancellation. Crash after: never cancel new work.
     * Receipt and lock writes are in a new PodJS-owned no_backup directory.
     */
    static void retire(Context c,String generation,Config config,WorkManager work)throws Exception {
        synchronized(CLEANUP_LOCK){retireLocked(c,generation,config,work);}
    }
    private static void retireLocked(Context c,String generation,Config config,WorkManager work)throws Exception {
        if(!generation.matches("g-[A-Za-z0-9-]+"))throw new IOException("legacy_generation_invalid");
        String key=key(c,generation,config);if(completed.contains(key))return;
        // Context's trusted dataDir may itself be an Android mount alias. Resolve it once,
        // just like converters; internal receipt links below are still rejected.
        File root=new File(PodTakeover.sandbox(c),"no_backup/podjs-workmanager-takeover");
        if(Files.isSymbolicLink(root.toPath())||!root.getCanonicalFile().equals(root.getAbsoluteFile()))throw new IOException("legacy_receipt_symlink");
        if(!root.mkdirs()&&!root.isDirectory())throw new IOException("legacy_receipt_unavailable");
        File lock=new File(root,"lock"),receipt=new File(root,generation+"-"+config.fingerprint+".done");
        for(File path:new File[]{lock,receipt,new File(receipt+".bak"),new File(receipt+".new")})
            if(Files.isSymbolicLink(path.toPath())||!path.getCanonicalFile().equals(path.getAbsoluteFile()))throw new IOException("legacy_receipt_symlink");
        try(RandomAccessFile file=new RandomAccessFile(lock,"rw");FileLock ignored=file.getChannel().lock()) {
            AtomicFile atomic=new AtomicFile(receipt);
            if(receipt.exists()||new File(receipt+".bak").exists()) {
                if(!Arrays.equals(atomic.readFully(),config.fingerprint.getBytes(StandardCharsets.US_ASCII)))throw new IOException("legacy_receipt_invalid");
            } else {
                for(String name:config.names)work.cancelUniqueWork(name).getResult().get(30,TimeUnit.SECONDS);
                // WorkManager adds the worker class itself as a tag, including one-shot work.
                Set<String> tags=new LinkedHashSet<>(config.tags);tags.addAll(config.classes);
                for(String tag:tags)work.cancelAllWorkByTag(tag).getResult().get(30,TimeUnit.SECONDS);
                // Do not prune unrelated application history or touch the SQLite DB directly.
                FileOutputStream out=null;
                try {out=atomic.startWrite();out.write(config.fingerprint.getBytes(StandardCharsets.US_ASCII));out.getFD().sync();atomic.finishWrite(out);
                    if(!Arrays.equals(atomic.readFully(),config.fingerprint.getBytes(StandardCharsets.US_ASCII)))throw new IOException("legacy_receipt_invalid");}
                catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}
            }
            completed.add(key);
        }
    }
}
