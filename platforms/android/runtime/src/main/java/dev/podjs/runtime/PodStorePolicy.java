package dev.podjs.runtime;

import java.util.*;
import org.json.*;

/** Narrow activation policy. A retained snapshot alone never justifies skipping user data. */
final class PodStorePolicy {
    /** Exact legacy-only values remain in NativeBackup and sealed per-store receipts.
     * Never treat a new key as disposable merely because its store is recognized. */
    static void retainKnown(String store,Map<String,Object> values,Set<String> unused,JSONObject report)throws Exception {
        int retained=0;
        for(String key:new TreeSet<>(unused)) {
            Object value=values.get(key);boolean known=false;
            if(store.equals("watchrss_library_ownership")&&key.equals("legacy_owner")){PodStoreMappings.require(PodStoreMappings.text(value).matches("[a-f0-9]{64}"));known=true;}
            if(store.equals("settings")&&key.equals("reader_tap_to_scroll")){PodStoreMappings.bool(value);known=true;}
            if(store.equals("llm_secure_prefs")&&key.equals("llm_api_key")){PodStoreMappings.require(PodStoreMappings.text(value).length()<=4096);known=true;}
            if(store.equals("bili_cache")&&(key.matches("bili_feed_cache_v2:.+")||key.matches("bili_feed_shown_v1:.+"))){PodStoreMappings.text(value);known=true;}
            if(store.equals("douyin_request_cooldown")&&key.matches("(feed|detail)\\.(until|strikes)")){PodStoreMappings.integer(value,0,Long.MAX_VALUE);known=true;}
            if(store.equals("watchrss_cloud_controls")){
                if(key.equals("automatic")){PodStoreMappings.bool(value);known=true;}
                if(key.equals("network")){PodStoreMappings.require(Arrays.asList("WIFI_BODIES_ANY_STATE","WIFI_AND_CHARGING","ANY_NETWORK").contains(PodStoreMappings.text(value)));known=true;}
            }
            if(store.equals("watchrss_cloud_state")){
                if(key.matches("([a-f0-9]{64}_)?sequence")){PodStoreMappings.integer(value,0,Long.MAX_VALUE);known=true;}
                if(key.matches("([a-f0-9]{64}_)?(applied|applied_full_notes_v3|applied_state|content_hash|content_hash_full|content_hash_state)")){PodStoreMappings.text(value);known=true;}
            }
            if(store.equals("novel_reading_history")&&key.matches("chapter_[a-f0-9]{64}")){PodStoreMappings.text(value);known=true;}
            if(store.equals("watchrss_cloud_keys")){
                if(key.matches("account_current_[a-f0-9]{24}")){PodStoreMappings.integer(value,1,Integer.MAX_VALUE);known=true;}
                if(key.matches("device_(private|public)_watchrss_cloud_ecdh_[a-f0-9]{24}")){PodStoreMappings.require(java.util.Base64.getDecoder().decode(PodStoreMappings.text(value)).length>0);known=true;}
            }
            if(known){unused.remove(key);retained++;}
        }
        if(retained>0)report.put("retainedKnownKeyCount",retained).put("retention","native-backup-no-delete");
    }

    static boolean nativeAccount(String store){return Arrays.asList("watchrss_account_state","bili_account_store","douyin_account_store").contains(store);}
    static boolean knownRetention(String store,JSONObject r)throws Exception {
        JSONArray keys=r.optJSONArray("unknownKeys");if(keys==null||keys.length()==0)return false;
        for(int i=0;i<keys.length();i++){
            String key=keys.getString(i);
            if(store.equals("WebViewChromiumPrefs")&&key.equals("lastVersionCodeUsed"))continue;
            if(store.equals("watchrss_installation_identity")&&key.equals("first_installed_at"))continue;
            if(store.equals("app_resume_state")&&key.equals("intent_uri")&&r.getString("reason").equals("native_route_unsupported_home_fallback"))continue;
            if(store.equals("settings")&&key.matches("temp_original_mode_hint_(attempts|last_toast)_[1-9][0-9]*"))continue;
            return false;
        }return true;
    }
    static void classify(JSONObject reports,boolean rehearsal)throws Exception {
        for(String store:PodFileConverter.names(reports)){
            JSONObject r=reports.getJSONObject(store);String status=r.getString("status"),reason=r.getString("reason"),d="blocked";
            if(status.equals("migrated")||status.equals("absent"))d="ready";
            else if(status.equals("retained-unsupported")&&((r.optInt("retainedKnownKeyCount",0)>0&&r.optInt("unknownKeyCount",0)==0&&(!r.has("unknownKeys")||r.getJSONArray("unknownKeys").length()==0))||knownRetention(store,r)))d="known-difference";
            else if(status.equals("needs-reauth")){
                if(rehearsal&&PodStoreConverter.encrypted(store)&&reason.equals("isolated_uid_keystore_unavailable"))d="known-difference";
                else if(nativeAccount(store)&&Arrays.asList("source_master_key_missing","source_authentication_failed").contains(reason))d="needs-reauth";
                // Only the isolated missing source key explains a missing dependent TTS config.
                else if(rehearsal&&store.equals("settings")&&reason.equals("tts_secret_missing")&&
                        reports.getJSONObject("tts_secure_prefs").getString("reason").equals("isolated_uid_keystore_unavailable")&&
                        (r.getJSONArray("unknownKeys").length()==0||knownRetention(store,r)))d="known-difference";
            }
            r.put("activationDisposition",d);
        }
    }
    static JSONObject diagnostics(JSONObject reports)throws Exception {
        JSONArray entries=new JSONArray();int blockers=0,differences=0,reauth=0;
        for(String store:PodFileConverter.names(reports)){
            JSONObject r=reports.getJSONObject(store);String d=r.getString("activationDisposition");
            if(d.equals("ready"))continue;
            if(d.equals("blocked"))blockers++;else if(d.equals("known-difference"))differences++;else reauth++;
            // Unknown filenames and preference keys can carry user identifiers. Never project them.
            String label=Arrays.asList(PodStoreConverter.STORES).contains(store)||store.equals("WebViewChromiumPrefs")?store:"unknown_store";
            entries.put(new JSONObject().put("store",label).put("category",d).put("reason",r.getString("reason")));
        }
        return new JSONObject().put("protocol",1).put("blockerCount",blockers).put("knownDifferenceCount",differences).put("needsReauthCount",reauth).put("entries",entries);
    }
}
