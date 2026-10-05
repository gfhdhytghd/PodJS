package dev.podjs.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Fixed source inventory, per-store atomic candidates and replay verification.
 * All decrypted values stay in memory or encrypted candidate files. */
public final class PodStoreConverter implements PodTakeoverCoordinator.Converter {
    public static final class Locked extends IOException { public Locked(){super("user_locked");} }
    public interface Crypto {
        Map<String,Object> decrypt(String store,Map<String,Object> ciphertext)throws Exception;
        byte[] seal(JSONObject values)throws Exception;
        JSONObject open(byte[] ciphertext)throws Exception;
        default boolean cloudBindingValid(String userId,String deviceId)throws Exception{return false;}
    }
    public interface Hook { void checkpoint(String store,String boundary)throws Exception; }
    static final String[] STORES={"settings","bili_cache","reader_preset_state","home_entry_placement","app_resume_state","douyin_watch_history","douyin_recent_window","douyin_feed_cache","watchrss_announcement","watchrss_push","watch_device_identity","watchrss_installation_identity","watchrss_daily_telemetry","watchrss_download_telemetry","watchrss_cloud_state","watchrss_account_state","llm_secure_prefs","tts_secure_prefs","watchrss_cloud_keys"};
    private final File sandbox;private final Crypto crypto;private final Hook hook;
    public PodStoreConverter(File sandbox,Crypto crypto){this(sandbox,crypto,(s,b)->{});}
    PodStoreConverter(File sandbox,Crypto crypto,Hook hook){this.sandbox=sandbox;this.crypto=crypto;this.hook=hook;}
    public void active(File g)throws Exception {
        validateKv(json(new File(g,"podjs/podjs-kv.json")));
        validateSecure(crypto.open(PodPreferenceReader.bytes(new File(g,"podjs/secure.store"))));
    }
    public String version(){return "watchrss-stores-1";}public boolean complete(){return false;}
    static boolean encrypted(String s){return Arrays.asList("watchrss_account_state","llm_secure_prefs","tts_secure_prefs","watchrss_cloud_keys").contains(s);}
    static void write(File f,byte[] b)throws Exception{if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("candidate_symlink");Files.createDirectories(f.getParentFile().toPath());File tmp=new File(f+".tmp");if(Files.isSymbolicLink(tmp.toPath()))throw new IOException("candidate_symlink");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(b);out.getFD().sync();}Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);PodTakeoverCoordinator.syncDirectory(f.getParentFile());}
    static void json(File f,JSONObject j)throws Exception{write(f,j.toString().getBytes(StandardCharsets.UTF_8));}
    static JSONObject json(File f)throws Exception{return new JSONObject(PodPreferenceReader.utf(PodPreferenceReader.bytes(f)));}
    static String hash(File f)throws Exception{return PodRoomV18Converter.hash(f);}
    private SortedMap<String,File> inventory()throws Exception {
        SortedMap<String,File> result=new TreeMap<>();
        for(String dir:new String[]{"files/datastore","shared_prefs"}){
            File root=new File(sandbox,dir);if(!root.exists())continue;
            if(!root.getCanonicalFile().equals(root.getAbsoluteFile()))throw new IOException("source_symlink");
            File[] files=root.listFiles();if(files==null)throw new IOException("inventory_unreadable");
            for(File f:files){if(!f.isFile()||Files.isSymbolicLink(f.toPath()))throw new IOException("source_not_regular");result.put(dir+"/"+f.getName(),f);}
        }return result;
    }
    public void snapshot(File g)throws Exception {
        File manifest=new File(g,"stores-snapshot.json");if(manifest.exists()){resume(g,PodTakeoverCoordinator.Phase.SNAPSHOTTED);return;}
        JSONObject entries=new JSONObject();SortedMap<String,File> source=inventory();
        for(Map.Entry<String,File> e:source.entrySet()){
            String before=hash(e.getValue());File target=new File(g,"stores-source/"+e.getKey());write(target,PodPreferenceReader.bytes(e.getValue()));
            if(!before.equals(hash(e.getValue()))||!before.equals(hash(target)))throw new IOException("source_changed");entries.put(e.getKey(),before);
        }
        if(!source.keySet().equals(inventory().keySet()))throw new IOException("inventory_changed");
        for(String path:source.keySet())if(!entries.getString(path).equals(hash(source.get(path))))throw new IOException("source_changed");
        json(manifest,new JSONObject().put("version",version()).put("entries",entries));
    }
    public void resume(File g,PodTakeoverCoordinator.Phase phase)throws Exception {
        JSONObject m=json(new File(g,"stores-snapshot.json"));if(!m.getString("version").equals(version()))throw new IOException("version_changed");
        JSONObject entries=m.getJSONObject("entries");for(Iterator<String> it=entries.keys();it.hasNext();){String p=it.next();if(!p.matches("(files/datastore|shared_prefs)/[^/]+")||p.endsWith("/..")||p.endsWith("/."))throw new IOException("snapshot_path_invalid");if(!entries.getString(p).equals(hash(new File(g,"stores-source/"+p))))throw new IOException("snapshot_changed");}
    }
    private SortedMap<String,List<String>> stores(File g)throws Exception {
        JSONObject entries=json(new File(g,"stores-snapshot.json")).getJSONObject("entries");SortedMap<String,List<String>> out=new TreeMap<>();for(String s:STORES)out.put(s,new ArrayList<>());
        for(Iterator<String> it=entries.keys();it.hasNext();){String path=it.next(),name=path.substring(path.lastIndexOf('/')+1),store;
            if(path.startsWith("files/datastore/")&&(name.equals("settings.preferences_pb")||name.equals("settings.preferences_pb.preferences_pb")))store="settings";
            else if(path.startsWith("files/datastore/")&&(name.equals("bili_cache.preferences_pb")||name.equals("bili_cache.preferences_pb.preferences_pb")))store="bili_cache";
            else if(path.startsWith("shared_prefs/")&&(name.endsWith(".xml")||name.endsWith(".xml.bak"))){store=name.substring(0,name.length()-(name.endsWith(".bak")?8:4));if(store.equals("settings")||store.equals("bili_cache"))store="shared_prefs:"+store;}
            else store="unrecognized:"+path;
            out.computeIfAbsent(store,k->new ArrayList<>()).add(path);
        }return out;
    }
    static String slot(String s)throws Exception{return PodRoomV18Converter.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    public void copy(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.COPYING);
        for(Map.Entry<String,List<String>> entry:stores(g).entrySet()){
            String store=entry.getKey();File dir=new File(g,"store-results/"+slot(store)),receipt=new File(dir,"receipt.json");
            if(receipt.exists()){validateResult(dir);continue;}
            Files.createDirectories(dir.toPath());hook.checkpoint(store,"before");
            JSONObject kv=new JSONObject(),secret=new JSONObject(),report=new JSONObject().put("version",1).put("converterVersion",version()).put("store",store).put("status","absent").put("reason","source_absent").put("keyCount",entry.getValue().isEmpty()?0:-1).put("inventoryComplete",entry.getValue().isEmpty()).put("unknownKeys",new JSONArray());
            if(encrypted(store))report.put("present",!entry.getValue().isEmpty()).put("semanticValid",JSONObject.NULL);
            Map<String,Object> values=null;
            if(entry.getValue().size()>1){report.put("status","failed").put("reason","ambiguous_store_files");}
            else if(!entry.getValue().isEmpty()&&entry.getValue().get(0).endsWith(".bak")){report.put("status","failed").put("reason","source_backup_pending");}
            else if(!entry.getValue().isEmpty()){
                try {
                    String path=entry.getValue().get(0);File source=new File(g,"stores-source/"+path);
                    values=path.startsWith("shared_prefs/")&&path.endsWith(".xml")?PodPreferenceReader.xml(source):PodPreferenceReader.protobuf(source);
                    if(encrypted(store)){
                        report.put("present",true);
                        try{values=crypto.decrypt(store,values);}catch(Exception failure){if(failure instanceof Locked)throw failure;report.put("status","needs-reauth").put("reason","source_crypto_unavailable").put("semanticValid",false);values=null;}
                    }
                    if(values!=null){
                        Set<String> unused=new TreeSet<>(values.keySet());report.put("keyCount",values.size()).put("inventoryComplete",true);
                        PodStoreMappings.convert(store,values,unused,kv,secret,report);
                        validateKv(kv);
                        report.put("unknownKeys",new JSONArray(unused));
                        if(!report.getString("status").equals("needs-reauth"))report.put("status",unused.isEmpty()?"migrated":"retained-unsupported").put("reason",unused.isEmpty()?"schema_valid":unsupportedReason(store));
                        if(encrypted(store))report.put("semanticValid",report.getString("status").equals("migrated"));
                    }
                }catch(Exception invalid){if(invalid instanceof Locked)throw invalid;kv=new JSONObject();secret=new JSONObject();report.put("status",encrypted(store)?"needs-reauth":"failed").put("reason",encrypted(store)?"encrypted_schema_invalid":"kv_quota".equals(invalid.getMessage())?"kv_quota_exceeded":"store_schema_invalid");if(values!=null)report.put("keyCount",values.size()).put("inventoryComplete",true).put("unknownKeys",new JSONArray(new TreeSet<>(values.keySet())));if(encrypted(store))report.put("semanticValid",false);}
            }
            if(store.equals("watchrss_cloud_keys")&&values!=null&&!values.isEmpty()&&!report.getString("status").equals("failed")){
                boolean valid=false;try{valid=cloudBinding(g);}catch(Exception unavailable){if(unavailable instanceof Locked)throw unavailable;}
                report.put("bindingValid",valid);
                if(!valid)report.put("status","needs-reauth").put("reason","cloud_binding_unavailable").put("semanticValid",false);
            }
            // Key names for encrypted stores stay in an encrypted inventory, never reports.
            if(encrypted(store)){secret.put("__takeover_inventory__",report.getJSONArray("unknownKeys").toString());if(values!=null)secret.put("__takeover_retained__",new JSONObject(values).toString());report.put("unknownKeyCount",report.getJSONArray("unknownKeys").length()).remove("unknownKeys");}
            validateKv(kv);json(new File(dir,"kv.json"),kv);report.put("kvDigest",hash(new File(dir,"kv.json")));
            JSONObject envelope=new JSONObject().put("values",secret).put("receipt",report).put("snapshot",hash(new File(g,"stores-snapshot.json")));
            if(envelope.toString().getBytes(StandardCharsets.UTF_8).length>256*1024){
                // Keep the original ciphertext snapshot; never truncate a secret or its metadata.
                secret=new JSONObject();kv=new JSONObject();json(new File(dir,"kv.json"),kv);
                report.put("status","failed").put("reason","secure_quota_exceeded").put("kvDigest",hash(new File(dir,"kv.json")));if(encrypted(store))report.put("semanticValid",false);
                envelope=new JSONObject().put("values",secret).put("receipt",report).put("snapshot",hash(new File(g,"stores-snapshot.json")));
            }
            write(new File(dir,"secure.store"),crypto.seal(envelope));hook.checkpoint(store,"files");
            if(!crypto.open(PodPreferenceReader.bytes(new File(dir,"secure.store"))).toString().equals(envelope.toString()))throw new IOException("secure_readback_invalid");
            json(receipt,report);hook.checkpoint(store,"receipt");
        }
        assemble(g);
    }
    void validateResult(File dir)throws Exception {
        JSONObject r=json(new File(dir,"receipt.json"));if(!r.getString("kvDigest").equals(hash(new File(dir,"kv.json"))))throw new IOException("kv_receipt_changed");validateKv(json(new File(dir,"kv.json")));JSONObject envelope=crypto.open(PodPreferenceReader.bytes(new File(dir,"secure.store")));if(!envelope.getJSONObject("receipt").toString().equals(r.toString())||!envelope.getString("snapshot").equals(hash(new File(dir.getParentFile().getParentFile(),"stores-snapshot.json"))))throw new IOException("secure_receipt_changed");
    }
    static void merge(JSONObject to,JSONObject from)throws Exception {for(Iterator<String> i=from.keys();i.hasNext();){String k=i.next();if(to.has(k))throw new IOException("target_key_conflict");to.put(k,from.get(k));}}
    void assemble(File g)throws Exception {
        JSONObject kv=new JSONObject(),secure=new JSONObject(),reports=new JSONObject();
        for(String store:stores(g).keySet()){
            File dir=new File(g,"store-results/"+slot(store));validateResult(dir);merge(kv,json(new File(dir,"kv.json")));
            JSONObject s=crypto.open(PodPreferenceReader.bytes(new File(dir,"secure.store"))).getJSONObject("values");s.remove("__takeover_inventory__");s.remove("__takeover_retained__");merge(secure,s);
            JSONObject r=json(new File(dir,"receipt.json"));r.remove("kvDigest");reports.put(store,r);
        }
        PodStoreMappings.joint(g,kv,secure,reports);json(new File(g,"stores-report.json"),reports);
        File validation=new File(g,"stores-validation.json");
        try {
            validateKv(kv);validateSecure(secure);
            File root=new File(g,"podjs");json(new File(root,"podjs-kv.json"),kv);write(new File(root,"secure.store"),crypto.seal(secure));
            if(!secure.toString().equals(crypto.open(PodPreferenceReader.bytes(new File(root,"secure.store"))).toString()))throw new IOException("secure_readback_invalid");
            json(validation,new JSONObject().put("candidateValid",true).put("activationReady",false));
        }catch(Exception failure){json(validation,new JSONObject().put("candidateValid",false).put("reason","kv_quota".equals(failure.getMessage())?"kv_quota_exceeded":"secure_quota".equals(failure.getMessage())?"secure_quota_exceeded":"candidate_write_or_readback_failed").put("activationReady",false));throw failure;}
    }
    static void validateKv(JSONObject kv)throws Exception {
        if(kv.toString().getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IOException("kv_quota");
        for(Iterator<String> it=kv.keys();it.hasNext();){String k=it.next();if(k.isEmpty()||k.length()>128)throw new IOException("kv_key_invalid");}
    }
    static void validateSecure(JSONObject secure)throws Exception {
        if(secure.toString().getBytes(StandardCharsets.UTF_8).length>256*1024)throw new IOException("secure_quota");
        for(Iterator<String> it=secure.keys();it.hasNext();)if(!(secure.get(it.next()) instanceof String))throw new IOException("secure_schema_invalid");
    }
    public String verify(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.VERIFYING);
        JSONObject expected=new JSONObject(),secret=new JSONObject(),reports=new JSONObject();
        for(String store:stores(g).keySet()){
            File dir=new File(g,"store-results/"+slot(store));validateResult(dir);merge(expected,json(new File(dir,"kv.json")));JSONObject s=crypto.open(PodPreferenceReader.bytes(new File(dir,"secure.store"))).getJSONObject("values");s.remove("__takeover_inventory__");s.remove("__takeover_retained__");merge(secret,s);JSONObject r=json(new File(dir,"receipt.json"));r.remove("kvDigest");reports.put(store,r);
        }
        PodStoreMappings.joint(g,expected,secret,reports);validateSecure(secret);if(!json(new File(g,"stores-validation.json")).getBoolean("candidateValid"))throw new IOException("candidate_invalid");JSONObject actual=json(new File(g,"podjs/podjs-kv.json"));validateKv(actual);
        if(!expected.toString().equals(actual.toString())||!secret.toString().equals(crypto.open(PodPreferenceReader.bytes(new File(g,"podjs/secure.store"))).toString())||!reports.toString().equals(json(new File(g,"stores-report.json")).toString()))throw new IOException("store_readback_invalid");
        // Receipt deliberately excludes secret digests and values.
        return hash(new File(g,"stores-report.json"));
    }
    static String unsupportedReason(String store){
        switch(store){
            case "watchrss_cloud_keys":return "cloud_alias_adoption_pending";
            case "watchrss_cloud_state":return "cloud_state_adapter_pending";
            case "llm_secure_prefs":return "llm_secret_consumer_missing";
            case "app_resume_state":return "native_route_unsupported_home_fallback";
            default:return "unmapped_keys_retained";
        }
    }
    private boolean cloudBinding(File g)throws Exception {
        File accountDir=new File(g,"store-results/"+slot("watchrss_account_state"));
        if(!new File(accountDir,"receipt.json").exists())return false;
        validateResult(accountDir);JSONObject values=crypto.open(PodPreferenceReader.bytes(new File(accountDir,"secure.store"))).getJSONObject("values");
        if(!values.has("state_json"))return false;
        JSONObject account=new JSONObject(values.getString("state_json"));
        return crypto.cloudBindingValid(account.getString("userId"),identity(new File(g,"stores-source")));
    }
    static String identity(File sandbox)throws Exception{Object v=PodPreferenceReader.xml(new File(sandbox,"shared_prefs/watch_device_identity.xml")).get("device_id");if(!(v instanceof String)||((String)v).trim().isEmpty())throw new IOException("identity_invalid");return (String)v;}
}
