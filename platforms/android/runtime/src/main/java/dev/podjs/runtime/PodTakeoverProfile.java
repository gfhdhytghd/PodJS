package dev.podjs.runtime;

import java.io.*;
import java.util.*;
import org.json.*;

/** Immutable Compose workspace selection, captured before Room opens a database.
 * Only hashes/relative paths enter the manifest; credentials never do. */
final class PodTakeoverProfile {
    final String scope;
    final boolean legacy;
    PodTakeoverProfile(String scope,boolean legacy){this.scope=scope;this.legacy=legacy;}
    static final Set<String> SCOPED=new HashSet<>(Arrays.asList("settings","bili_cache","reader_preset_state","home_entry_placement","douyin_watch_history","douyin_recent_window","douyin_feed_cache","douyin_request_cooldown","llm_secure_prefs","tts_secure_prefs","watchrss_cloud_keys","bili_account_store","douyin_account_store","watchrss_cloud_state","watchrss_cloud_controls","novel_reading_history"));
    static String space(String environment,String user)throws Exception {
        return PodStoreConverter.slot(environment+":"+(user==null?"guest":"user:"+user.length()+":"+user));
    }
    static String preferenceBase(String name){return name.matches("account_[a-f0-9]{64}_.+")?name.substring(73):name;}
    static String physicalName(String path){String n=path.substring(path.lastIndexOf('/')+1);return n.endsWith(".xml.bak")?n.substring(0,n.length()-8):n.endsWith(".xml")?n.substring(0,n.length()-4):n;}
    String filesPrefix(){return legacy?"":"accounts/"+scope+"/";}
    String database(){return "databases/"+(legacy?"":"accounts/"+scope+"/")+"watchrss.db";}
    JSONObject json()throws Exception{return new JSONObject().put("scope",scope).put("legacy",legacy).put("database",database()).put("inactivePolicy","native-backup-no-merge");}
    static PodTakeoverProfile load(File g)throws Exception {
        File manifest=new File(g,"stores-snapshot.json");if(!manifest.isFile())return new PodTakeoverProfile("",true);
        JSONObject p=PodStoreConverter.json(manifest).optJSONObject("profile");
        if(p==null)return new PodTakeoverProfile("",true); // Published pre-workspace generations remain readable.
        String scope=p.getString("scope");boolean legacy=p.getBoolean("legacy");
        if(!scope.isEmpty()&&!scope.matches("[a-f0-9]{64}")||!legacy&&scope.isEmpty())throw new IOException("profile_invalid");
        PodTakeoverProfile result=new PodTakeoverProfile(scope,legacy);
        if(!result.database().equals(p.getString("database")))throw new IOException("profile_invalid");return result;
    }
    static PodTakeoverProfile capture(File g,PodStoreConverter.Crypto crypto)throws Exception {
        File root=new File(g,"stores-source"),ownership=new File(root,"shared_prefs/watchrss_library_ownership.xml");
        if(!ownership.isFile())return new PodTakeoverProfile("",true); // Before Compose's workspace feature.
        Object owner=PodPreferenceReader.xml(ownership).get("legacy_owner");
        if(owner==null){
            File prefs=new File(root,"shared_prefs");File[] entries=prefs.listFiles();
            if(entries==null)throw new IOException("profile_invalid");
            for(File entry:entries)if(entry.getName().matches("account_[a-f0-9]{64}_.+"))throw new IOException("profile_invalid");
            if(new File(root,"files/accounts").exists())throw new IOException("profile_invalid");
            return new PodTakeoverProfile("",true);
        }
        if(!(owner instanceof String)||!((String)owner).matches("[a-f0-9]{64}"))throw new IOException("profile_invalid");
        String environment="unbound",user=null;
        File account=new File(root,"shared_prefs/watchrss_account_state.xml");
        if(account.isFile()) {
            Map<String,Object> values=crypto.decrypt("watchrss_account_state",PodPreferenceReader.xml(account));
            if(values.containsKey("state_json")) {
                JSONObject state=new JSONObject(PodStoreMappings.text(values.get("state_json")));
                user=state.getString("userId");environment=state.getString("backendBaseUrl").replaceAll("/+$","");
                PodStoreMappings.require(!user.trim().isEmpty()&&!environment.trim().isEmpty());
            }
        }
        String scope=space(environment,user);return new PodTakeoverProfile(scope,scope.equals(owner));
    }
    boolean selectedStore(String path,String base) {
        if(!SCOPED.contains(base))return !physicalName(path).startsWith("account_");
        if(path.startsWith("shared_prefs/"))return physicalName(path).equals((legacy?"":"account_"+scope+"_")+base);
        return path.startsWith("files/"+filesPrefix()+"datastore/");
    }
    /** Return a logical files/ path, or null for a separate, retained workspace. */
    String logicalFile(String path) {
        if(path.startsWith("files/accounts/"))return !legacy&&path.startsWith("files/"+filesPrefix())?"files/"+path.substring(("files/"+filesPrefix()).length()):null;
        if(!legacy&&path.startsWith("files/")&&(PodFileConverter.category(path).equals("asset")||path.startsWith("files/watch-cloud-cache/")))return null;
        return path;
    }
}
