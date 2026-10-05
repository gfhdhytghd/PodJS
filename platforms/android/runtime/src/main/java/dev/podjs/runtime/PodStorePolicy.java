package dev.podjs.runtime;

import java.util.*;
import org.json.*;

/** Narrow activation policy. A retained snapshot alone never justifies skipping user data. */
final class PodStorePolicy {
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
            else if(status.equals("retained-unsupported")&&knownRetention(store,r))d="known-difference";
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
