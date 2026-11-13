package dev.podjs.runtime;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Versioned mappings to the current WatchRSS consumers. No network or native stores. */
final class PodStoreMappings {
    static void require(boolean b)throws IOException{if(!b)throw new IOException("schema_invalid");}
    static String text(Object v)throws Exception{require(v instanceof String);return (String)v;}
    static double number(Object v,double min,double max)throws Exception{require(v instanceof Number);double n=((Number)v).doubleValue();require(Double.isFinite(n)&&n>=min&&n<=max);return n;}
    static long integer(Object v,long min,long max)throws Exception{require(v instanceof Integer||v instanceof Long||v instanceof Short||v instanceof Byte);double n=number(v,min,max);require(n==Math.rint(n));return ((Number)v).longValue();}
    static boolean bool(Object v)throws Exception{require(v instanceof Boolean);return (Boolean)v;}
    static Object take(Map<String,Object> p,Set<String> u,String k,Object fallback){if(!p.containsKey(k))return fallback;u.remove(k);return p.get(k);}
    static void simple(Map<String,Object> p,Set<String> u,JSONObject target,String from,String to,Class<?> type)throws Exception{if(p.containsKey(from)){Object v=p.get(from);require(type.isInstance(v));target.put(to,v);u.remove(from);}}
    static void convert(String store,Map<String,Object> p,Set<String> u,JSONObject kv,JSONObject secure,JSONObject report)throws Exception{
        switch(store){
            case "settings":settings(p,u,kv);break;
            case "watchrss_transparency":for(String key:new String[]{"initial_app_disclosure_v1","bili_disclosure_v1","douyin_disclosure_v1"})if(p.containsKey(key)){kv.put("watchrss.transparency."+key,bool(p.get(key)));u.remove(key);}break;
            case "watchrss_library_ownership":if(p.containsKey("legacy_owner"))require(text(p.get("legacy_owner")).matches("[a-f0-9]{64}"));break;
            case "WebViewChromiumPrefs":if(p.containsKey("lastVersionCodeUsed"))integer(p.get("lastVersionCodeUsed"),0,Integer.MAX_VALUE);break;
            case "bili_cache":bili(p,u,kv);break;
            case "app_resume_state":
                if(p.containsKey("intent_uri")){
                    JSONObject target=PodNativeResume.target(text(p.get("intent_uri")));
                    kv.put("watchrss.resume-state.v1",target==null?PodNativeResume.home():target);
                    if(target!=null)u.remove("intent_uri");
                }break;
            case "home_entry_placement":
                if(p.containsKey("notes_sort_order")){kv.put("watchrss.home.notes-sort-order",integer(p.get("notes_sort_order"),1,9007199254740991L));u.remove("notes_sort_order");}
                simple(p,u,kv,"notes_pinned","watchrss.home.notes-pinned",Boolean.class);break;
            case "watchrss_announcement":simple(p,u,kv,"dismissed_version","announcement.dismissed_version",String.class);break;
            case "watchrss_push":if(p.containsKey("last_seen_notification_id")){kv.put("announcement.last_seen_notification_id",integer(p.get("last_seen_notification_id"),0,9007199254740991L));u.remove("last_seen_notification_id");}break;
            case "watch_device_identity":if(p.containsKey("device_id")){require(!text(p.get("device_id")).trim().isEmpty());u.remove("device_id");}break; // P2b SQLite snapshot
            case "watchrss_installation_identity":if(p.containsKey("first_installed_at"))integer(p.get("first_installed_at"),0,9007199254740991L);if(p.containsKey("install_id"))require(!text(p.get("install_id")).trim().isEmpty());simple(p,u,kv,"install_id","watchrss.telemetry.install-id",String.class);break;
            case "watchrss_download_telemetry":if(p.containsKey("release_oobe_recorded")){kv.put("watchrss.telemetry.release-oobe-recorded",Boolean.toString(bool(p.get("release_oobe_recorded"))));u.remove("release_oobe_recorded");}break;
            case "watchrss_daily_telemetry":telemetry(p,u,kv);break;
            case "reader_preset_state":reader(p,u,kv);break;
            case "douyin_watch_history":history(p,u,kv);break;
            case "douyin_recent_window":case "douyin_feed_cache":douyinFeed(store,p,u,kv);break;
            case "bili_account_store":
                if(p.containsKey("account_json")){
                    JSONObject nativeAccount=new JSONObject(text(p.get("account_json"))),cookies=nativeAccount.optJSONObject("cookies");
                    if(cookies==null)cookies=new JSONObject();
                    for(Iterator<String> i=cookies.keys();i.hasNext();)text(cookies.get(i.next()));
                    JSONObject account=new JSONObject().put("cookies",cookies).put("updatedAt",nativeAccount.isNull("updatedAtMillis")?0:integer(nativeAccount.get("updatedAtMillis"),0,9007199254740991L));
                    if(!nativeAccount.isNull("refreshToken"))account.put("refreshToken",text(nativeAccount.get("refreshToken")));
                    if(!nativeAccount.isNull("cookieRefreshCheckedAtMillis"))account.put("cookieRefreshCheckedAt",integer(nativeAccount.get("cookieRefreshCheckedAtMillis"),0,9007199254740991L));
                    secure.put("watchrss.bili.session.v1",account.toString());
                    if(!nativeAccount.isNull("wbiImgKey")&&!nativeAccount.isNull("wbiSubKey"))secure.put("watchrss.bili.session.v1.wbi",new JSONObject().put("imgKey",text(nativeAccount.get("wbiImgKey"))).put("subKey",text(nativeAccount.get("wbiSubKey"))).toString());
                    u.remove("account_json");
                }break;
            case "douyin_account_store":if(p.containsKey("cookie")){secure.put("douyin.cookie",text(p.get("cookie")));u.remove("cookie");}break;
            case "llm_secure_prefs":if(p.containsKey("llm_api_key"))require(text(p.get("llm_api_key")).length()<=4096);break;
            case "watchrss_cloud_keys":JSONObject legacy=new JSONObject();for(String key:p.keySet()){if(key.matches("account_[a-f0-9]{24}(_v[1-9][0-9]*)?")){require(java.util.Base64.getDecoder().decode(text(p.get(key))).length==32);legacy.put(key,p.get(key));u.remove(key);}else if(key.matches("account_current_[a-f0-9]{24}"))integer(p.get(key),1,Integer.MAX_VALUE);}if(legacy.length()>0)secure.put("__takeover.cloud-legacy",legacy.toString());break;
            case "watchrss_account_state":
                if(p.containsKey("state_json")){String raw=text(p.get("state_json"));JSONObject account=new JSONObject(raw);require(!text(account.get("userId")).trim().isEmpty());String token=account.has("watchAccessToken")?text(account.get("watchAccessToken")):"";if(token.isEmpty()&&account.has("watchDeviceToken"))token=text(account.get("watchDeviceToken"));require(!token.trim().isEmpty());for(String key:new String[]{"accessTokenExpiresAtMillis","tokenExpiresAtMillis","refreshTokenExpiresAtMillis","updatedAtMillis"})if(account.has(key))integer(account.get(key),0,9007199254740991L);secure.put("state_json",raw);u.remove("state_json");}break;
            case "tts_secure_prefs":for(String engine:new String[]{"minimax","azure","doubao"}){String key="tts_api_key_"+engine;if(p.containsKey(key)){String value=text(p.get(key));require(value.length()<=4096);secure.put("watchrss.tts.api-key."+engine,value);u.remove(key);}}break;
            // No proven consumer for LLM secrets/cloud legacy aliases, unsafe native Intents,
            // WebView scope or unsupported feed DTOs: preserve source and enumerate every key.
            default:break;
        }
    }
    static void settings(Map<String,Object> p,Set<String> u,JSONObject kv)throws Exception {
        for(String key:new String[]{"reading_font_size_sp","llm_prompt_preset","media_playback_start_volume_limit_percent","rss_inline_image_prefetch_mode","oobe_seen_version","privacy_policy_agreed_version"})if(p.containsKey(key))require(p.get(key) instanceof Integer);
        for(String key:new String[]{"tts_speed","reader_auto_scroll_lines_per_second"})if(p.containsKey(key))require(p.get(key) instanceof Float);
        if(p.containsKey("cache_limit_bytes"))require(p.get("cache_limit_bytes") instanceof Long);
        // Preserve native per-channel toast throttling separately from user settings.
        JSONObject hints=new JSONObject();
        for(String key:p.keySet()){
            if(key.matches("temp_original_mode_hint_attempts_[1-9][0-9]*")) {
                String id=key.substring("temp_original_mode_hint_attempts_".length());JSONObject row=hints.optJSONObject(id);if(row==null){row=new JSONObject();hints.put(id,row);}
                row.put("attempts",text(p.get(key)));u.remove(key);
            } else if(key.matches("temp_original_mode_hint_last_toast_[1-9][0-9]*")) {
                String id=key.substring("temp_original_mode_hint_last_toast_".length());JSONObject row=hints.optJSONObject(id);if(row==null){row=new JSONObject();hints.put(id,row);}
                row.put("lastToast",integer(p.get(key),0,9007199254740991L));u.remove(key);
            }
        }
        if(hints.length()>0)kv.put("watchrss.temporary-original-hints.v1",hints);
        JSONObject s=new JSONObject();
        String[][] booleans={{"phone_connection_enabled","phoneConnectionEnabled","true"},{"llm_enabled","llmEnabled","true"},{"llm_auto_summarize","llmAutoSummarize","false"},{"llm_show_token_usage","llmShowTokenUsage","false"},{"reader_auto_scroll_enabled","readerAutoStart","false"},{"media_volume_control_enabled","mediaVolumeControl","true"},{"media_volume_guard_enabled","mediaVolumeGuard","false"},{"sync_media_keep_alive_enabled","syncMediaKeepAlive","false"},{"share_use_system","shareUseSystem","false"}};
        for(String[] b:booleans)s.put(b[1],bool(take(p,u,b[0],Boolean.valueOf(b[2]))));
        long font=integer(take(p,u,"reading_font_size_sp",14),12,32);require(font%2==0);s.put("fontSizeSp",font);
        s.put("readerPageMode",bool(take(p,u,"reader_page_mode",false)));
        s.put("readerPageSeconds",integer(take(p,u,"reader_page_seconds",15),5,120));
        boolean autoPage=bool(take(p,u,"reader_auto_page_enabled",false));if(s.getBoolean("readerPageMode"))s.put("readerAutoStart",autoPage);
        s.put("llmPromptPreset",integer(take(p,u,"llm_prompt_preset",0),0,3)+1);
        s.put("readerScrollSpeed",number(take(p,u,"reader_auto_scroll_lines_per_second",2.0),.5,10));
        double speed=number(take(p,u,"tts_speed",1.0),.5,2);s.put("speechRate",speed);
        long limit=p.containsKey("media_playback_start_volume_limit_percent")?integer(take(p,u,"media_playback_start_volume_limit_percent",null),Integer.MIN_VALUE,Integer.MAX_VALUE):(s.getBoolean("mediaVolumeGuard")?10:-1);
        s.put("playbackStartVolumeLimit",limit==-1?JSONObject.NULL:limit>=0&&limit<=100&&limit%5==0?limit:10);
        long bytes=integer(take(p,u,"cache_limit_bytes",512L*1024*1024),0,4096L*1024*1024);require(bytes%1048576==0);long mb=bytes/1048576;require(Arrays.asList(512L,768L,1024L,1536L,2048L,2560L,3072L,4096L).contains(mb));s.put("cacheLimitMb",mb);
        s.put("mediaCacheMode",new String[]{"关闭","前 4 项","全部"}[(int)integer(take(p,u,"rss_inline_image_prefetch_mode",1),0,2)]);
        for(String[] pair:new String[][]{{"llm_provider","llmProvider"},{"llm_model","llmModel"},{"llm_base_url","llmBaseUrl"},{"llm_system_prompt","llmSystemPrompt"}})
            s.put(pair[1],text(take(p,u,pair[0],"")));
        kv.put("watchrss.settings",s);
        if(p.containsKey("reading_theme_dark"))kv.put("__takeover.reader-dark",bool(take(p,u,"reading_theme_dark",true)));
        JSONObject onboarding=new JSONObject();for(String[] pair:new String[][]{{"oobe_seen_version","oobeSeenVersion"},{"privacy_policy_agreed_version","privacyPolicyAgreedVersion"}})if(p.containsKey(pair[0]))onboarding.put(pair[1],integer(take(p,u,pair[0],null),0,Integer.MAX_VALUE));if(onboarding.length()>0)kv.put("watchrss.onboarding",onboarding);
        if(p.containsKey("tts_engine")||p.containsKey("tts_model")||p.containsKey("tts_voice_id")||p.containsKey("tts_base_url")){
            String engine=text(p.getOrDefault("tts_engine",""));if(engine.isEmpty())engine="local";if(Arrays.asList("local","backend_default","minimax","azure","doubao").contains(engine)){
                JSONObject t=new JSONObject().put("engine",engine).put("speed",speed);u.remove("tts_engine");
                for(String[] pair:new String[][]{{"tts_model","model"},{"tts_voice_id","voiceId"},{"tts_base_url","baseUrl"}}){String v=text(take(p,u,pair[0],""));require(v.length()<=(pair[1].equals("baseUrl")?1024:256));if(pair[1].equals("baseUrl"))require(v.isEmpty()||v.startsWith("https://")&&!v.matches(".*\\s.*"));t.put(pair[1],v);}kv.put("watchrss.tts.config.v1",t);
            }
        }
    }
    static void reader(Map<String,Object> p,Set<String> u,JSONObject kv)throws Exception {
        kv.put("__takeover.reader-mode-explicit",p.containsKey("reader_theme_mode"));
        String mode=text(take(p,u,"reader_theme_mode","DARK"));require(Arrays.asList("DARK","LIGHT","SYSTEM").contains(mode));
        Object active=take(p,u,"active_preset_id",JSONObject.NULL),light=take(p,u,"light_preset_id",active),dark=take(p,u,"dark_preset_id",JSONObject.NULL);
        for(Object id:new Object[]{active,light,dark})require(id==JSONObject.NULL||id instanceof String);
        if("".equals(active))active=JSONObject.NULL;if("".equals(light)||light==JSONObject.NULL)light=active;if("".equals(dark))dark=JSONObject.NULL;
        kv.put("watchrss.reader-presets.selection",new JSONObject().put("mode",mode).put("lightPresetId",light).put("darkPresetId",dark).put("darkFollowsLight",bool(take(p,u,"dark_follows_light",true))));
        String schedule=text(take(p,u,"watch_theme_schedule_mode","FIXED_TIME"));require(Arrays.asList("FIXED_TIME","SUNRISE_SUNSET").contains(schedule));
        JSONObject t=new JSONObject().put("mode",schedule).put("lightStartMinutes",integer(take(p,u,"watch_light_start_minutes",420),0,1439)).put("darkStartMinutes",integer(take(p,u,"watch_dark_start_minutes",1140),0,1439)).put("locationUpdatedAt",integer(take(p,u,"watch_sun_location_updated_at",0L),0,9007199254740991L));
        for(String[] pair:new String[][]{{"watch_sun_latitude","latitude"},{"watch_sun_longitude","longitude"}}){Object raw=take(p,u,pair[0],JSONObject.NULL);int bound=pair[1].equals("latitude")?90:180;Object value=raw==JSONObject.NULL||"".equals(raw)?JSONObject.NULL:number(Double.valueOf(text(raw)),-bound,bound);t.put(pair[1],value);}kv.put("watchrss.reader-presets.schedule",t);
    }
    static void history(Map<String,Object> p,Set<String> u,JSONObject secure)throws Exception{
        if(p.containsKey("watched_ids")){require(p.get("watched_ids") instanceof Set);Set<?> ids=(Set<?>)p.get("watched_ids");for(Object id:ids)text(id);require(ids.size()<=1000);secure.put("douyin.watched.v1",new JSONArray(ids).toString());u.remove("watched_ids");}
        if(p.containsKey("history_json")){
            JSONArray input=new JSONArray(text(p.get("history_json"))),output=new JSONArray();require(input.length()<=200);
            for(int i=0;i<input.length();i++){
                JSONObject n=input.getJSONObject(i),item=new JSONObject();for(String[] pair:new String[][]{{"aweme_id","awemeId"},{"play_url","playUrl"},{"cover_url","coverUrl"},{"title","description"},{"author","authorName"}})item.put(pair[1],text(n.get(pair[0])));
                require(!item.getString("awemeId").trim().isEmpty()&&!item.getString("playUrl").trim().isEmpty());item.put("likeCount",integer(n.get("like_count"),0,9007199254740991L));douyinDefaults(item);output.put(new JSONObject().put("item",item).put("watchedAtMillis",integer(n.get("watched_at"),0,9007199254740991L)));
            }secure.put("douyin.history.v1",output.toString());u.remove("history_json");
        }
    }
    static void telemetry(Map<String,Object> p,Set<String> u,JSONObject kv)throws Exception {
        if(!p.containsKey("days"))return;require(p.get("days") instanceof Set);TreeSet<String> days=new TreeSet<>();for(Object d:(Set<?>)p.get("days"))days.add(text(d));JSONArray rows=new JSONArray();
        for(String day:days){java.time.LocalDate.parse(day);String key="day_"+day;JSONObject src=p.containsKey(key)?new JSONObject(text(p.get(key))):new JSONObject(),out=new JSONObject().put("day",day);
            for(String name:new String[]{"eventCounts","screenOpenCounts","screenDurationMs"}){JSONObject counts=src.optJSONObject(name);if(counts==null)counts=new JSONObject();for(Iterator<String> it=counts.keys();it.hasNext();)integer(counts.get(it.next()),0,9007199254740991L);out.put(name,counts);}
            for(String name:new String[]{"appForegroundMs","syncSuccessCount","syncFailureCount"})out.put(name,integer(src.has(name)?src.get(name):0,0,9007199254740991L));rows.put(out);u.remove(key);
        }u.remove("days");kv.put("watchrss.sync.account-telemetry.v1",rows.toString());
    }
    static void bili(Map<String,Object> p,Set<String> u,JSONObject kv)throws Exception {
        if(p.containsKey("bili_search_history")){JSONArray a=new JSONArray(text(p.get("bili_search_history")));require(a.length()<=20);for(int i=0;i<a.length();i++)text(a.get(i));kv.put("watchrss.bili.search-history.v1",a);u.remove("bili_search_history");}
        String interaction="bili_local_interaction_state_json";
        if(p.containsKey(interaction)){JSONArray a=new JSONArray(text(p.get(interaction))),out=new JSONArray();require(a.length()<=200);for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i),t=biliIdentity(n);for(String[] pair:new String[][]{{"isLiked","like"},{"isCoined","coin"},{"isFavorited","favorite"}})t.put(pair[1],bool(n.get(pair[0])));t.put("updatedAt",integer(n.get("updatedAt"),0,9007199254740991L));out.put(t);}kv.put("watchrss.bili.interactions.v1",out);u.remove(interaction);}
        // Actual source key differs from the early design spelling; accept either, reject ambiguity.
        String progress=p.containsKey("bili_playback_progress_json")?"bili_playback_progress_json":"bili_local_playback_progress_json";
        require(!(p.containsKey("bili_playback_progress_json")&&p.containsKey("bili_local_playback_progress_json")));
        if(p.containsKey(progress)){JSONArray a=new JSONArray(text(p.get(progress))),out=new JSONArray();require(a.length()<=100);for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i),t=biliIdentity(n);t.put("cid",integer(n.get("cid"),1,9007199254740991L));for(String field:new String[]{"positionMs","durationMs","updatedAt"})t.put(field,integer(n.get(field),0,9007199254740991L));out.put(t);}kv.put("watchrss.bili.playback-progress.v1",out.toString());u.remove(progress);}
        if(p.containsKey("bili_feed_cache_json")){JSONArray a=new JSONArray(text(p.get("bili_feed_cache_json"))),out=new JSONArray();require(a.length()<=50);for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i),t=biliIdentity(n);for(String field:new String[]{"title","cover"})if(n.has(field))t.put(field,text(n.get(field)));if(!t.has("title"))t.put("title","");for(String field:new String[]{"cid","duration","pubdate"})if(n.has(field))t.put(field,integer(n.get(field),0,9007199254740991L));if(n.has("owner"))t.put("owner",new JSONObject().put("name",text(n.get("owner"))));JSONObject stat=new JSONObject();for(String field:new String[]{"view","like","danmaku"})if(n.has(field))stat.put(field,integer(n.get(field),0,9007199254740991L));t.put("stat",stat);out.put(t);}kv.put("watchrss.bili.feed-cache.v1",out);u.remove("bili_feed_cache_json");if(p.containsKey("bili_feed_cache_at")){integer(p.get("bili_feed_cache_at"),0,9007199254740991L);u.remove("bili_feed_cache_at");}}
    }
    static JSONObject biliIdentity(JSONObject n)throws Exception{JSONObject t=new JSONObject();if(n.has("aid"))t.put("aid",integer(n.get("aid"),1,9007199254740991L));if(n.has("bvid")){String b=text(n.get("bvid"));require(!b.trim().isEmpty());t.put("bvid",b);}require(t.length()>0);return t;}
    static void douyinDefaults(JSONObject item)throws Exception{
        item.put("createdAtSeconds",0).put("authorId","").put("commentCount",0).put("shareCount",0).put("collectCount",0);
        if(!item.has("durationMs"))item.put("durationMs",0);if(!item.has("variants"))item.put("variants",new JSONArray());
    }
    static void douyinFeed(String store,Map<String,Object> p,Set<String> u,JSONObject secure)throws Exception{
        String key=store.equals("douyin_recent_window")?"recent_window_json":"feed_cache_json";if(!p.containsKey(key))return;
        JSONObject src=new JSONObject(text(p.get(key))),out=new JSONObject(),resolved=new JSONObject();JSONArray items=src.getJSONArray("items"),converted=new JSONArray();require(items.length()<=40);Set<String> ids=new HashSet<>();
        for(int i=0;i<items.length();i++){
            JSONObject n=items.getJSONObject(i),v=new JSONObject();String id=text(n.get("awemeId")),url=text(n.get("playUrl"));require(!id.trim().isEmpty()&&!url.trim().isEmpty()&&ids.add(id));v.put("awemeId",id).put("playUrl",url);
            for(String[] pair:new String[][]{{"title","description"},{"author","authorName"},{"coverUrl","coverUrl"}})v.put(pair[1],n.isNull(pair[0])?"":text(n.get(pair[0])));
            v.put("likeCount",integer(n.get("likeCount"),0,9007199254740991L)).put("durationMs",integer(n.opt("durationMs")==null?0:n.get("durationMs"),0,9007199254740991L));
            JSONArray variants=n.optJSONArray("variants");if(variants==null)variants=new JSONArray();JSONArray targetVariants=new JSONArray();
            for(int j=0;j<variants.length();j++){JSONObject a=variants.getJSONObject(j),b=new JSONObject();b.put("playUrl",text(a.get("playUrl")));String codec=text(a.get("codec")).toLowerCase(Locale.ROOT);require(Arrays.asList("h264","h265","unknown").contains(codec));b.put("codec",codec);for(String num:new String[]{"bitrate","width","height"})b.put(num,integer(a.get(num),0,9007199254740991L));for(String field:new String[]{"definition","quality","gearName"})if(a.has(field))b.put(field,text(a.get(field)));targetVariants.put(b);}
            v.put("variants",targetVariants);douyinDefaults(v);converted.put(v);resolved.put(id,integer(n.has("playUrlResolvedAtMs")?n.get("playUrlResolvedAtMs"):0,0,9007199254740991L));
        }
        Object cursor=src.opt("nextCursor");require(cursor==null||cursor==JSONObject.NULL||cursor instanceof String);Object anchor=src.opt("anchorAwemeId");require(anchor==null||anchor==JSONObject.NULL||anchor instanceof String);
        out.put("items",converted).put("resolved",resolved).put("savedAtMillis",integer(src.get("savedAtMs"),0,9007199254740991L)).put("cursor",cursor==null?JSONObject.NULL:cursor).put("hasMore",src.has("hasMore")?bool(src.get("hasMore")):false).put("anchorAwemeId",anchor==null||anchor==JSONObject.NULL||"".equals(anchor)?JSONObject.NULL:anchor);
        secure.put(store.equals("douyin_recent_window")?"douyin.recent-window.v1":"douyin.feed.v1",out.toString());u.remove(key);
    }
    static void joint(java.io.File generation,JSONObject kv,JSONObject secure,JSONObject reports)throws Exception {
        if(secure.has("state_json")){
            JSONObject account=new JSONObject(secure.getString("state_json"));
            if(account.opt("backendBaseUrl") instanceof String&&!account.getString("backendBaseUrl").trim().isEmpty()){
            // WatchAccountStore compares this string with JSON.stringify, not parsed JSON.
            // Android JSONStringer escapes '/', whereas JSON.stringify leaves it literal.
            secure.put("watchrss.library.owner",new JSONArray().put(account.getString("backendBaseUrl").trim().replaceAll("/+$","")).put(account.getString("userId").trim()).toString().replace("\\/","/"));
            }
        }
        Object legacyKeys=secure.remove("__takeover.cloud-legacy");
        if(legacyKeys instanceof String&&secure.has("state_json")){
            String user=new JSONObject(secure.getString("state_json")).getString("userId"),hash=PodStoreConverter.slot(user),prefix="account_"+hash.substring(0,24);
            JSONObject source=new JSONObject((String)legacyKeys),versions=new JSONObject();
            for(String key:PodFileConverter.names(source))if(key.equals(prefix)||key.matches(java.util.regex.Pattern.quote(prefix)+"_v[1-9][0-9]*")){
                String version=key.equals(prefix)?"1":key.substring(prefix.length()+2);
                if(versions.has(version))require(versions.getString(version).equals(source.getString(key)));
                versions.put(version,source.getString(key));
            }
            if(versions.length()>0)secure.put("watchrss.cloud.keys."+hash,versions.toString());
        }
        Object legacy=kv.remove("__takeover.reader-dark"),explicit=kv.remove("__takeover.reader-mode-explicit");
        JSONObject selection=kv.optJSONObject("watchrss.reader-presets.selection"),schedule=kv.optJSONObject("watchrss.reader-presets.schedule");
        java.io.File catalog=new java.io.File(generation,"reader-catalog.json");
        if(selection!=null||catalog.exists()||legacy!=null){
            require(catalog.isFile());JSONObject state=PodStoreConverter.json(catalog);require(state.getInt("version")==1);state.getJSONArray("records");JSONObject assets=state.getJSONObject("assets");for(String group:new String[]{"fonts","backgrounds","deletions"})assets.getJSONArray(group);
            if(selection==null){JSONObject defaults=new JSONObject();reader(new HashMap<>(),new HashSet<>(),defaults);selection=defaults.getJSONObject("watchrss.reader-presets.selection");schedule=defaults.getJSONObject("watchrss.reader-presets.schedule");}
            if(!Boolean.TRUE.equals(explicit)&&legacy!=null)selection.put("mode",bool(legacy)?"DARK":"LIGHT");
            state.put("selection",selection).put("schedule",schedule);kv.remove("watchrss.reader-presets.selection");kv.remove("watchrss.reader-presets.schedule");kv.put("watchrss.reader-presets",state);
        }

        JSONObject t=kv.optJSONObject("watchrss.tts.config.v1");if(t!=null&&Arrays.asList("minimax","azure","doubao").contains(t.getString("engine"))&&secure.optString("watchrss.tts.api-key."+t.getString("engine"),"").trim().isEmpty()){
            kv.remove("watchrss.tts.config.v1");reports.getJSONObject("settings").put("status","needs-reauth").put("reason","tts_secret_missing");
        }
    }
}
