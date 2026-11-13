package dev.podjs.runtime;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Synthetic data only. Uses actual protobuf wire tags and authenticated JVM crypto. */
public class StoreTest {
    static class Crypto implements PodStoreConverter.Crypto {
        final javax.crypto.SecretKey key;boolean unavailable;boolean binding=true;boolean locked;
        Crypto(){byte[] fixtureKey=new byte[32];for(int i=0;i<32;i++)fixtureKey[i]=(byte)i;key=new javax.crypto.spec.SecretKeySpec(fixtureKey,"AES");} // Synthetic-only stable key permits process-kill replay.
        public Map<String,Object> decrypt(String s,Map<String,Object> raw)throws Exception{if(locked)throw new PodStoreConverter.Locked();if(unavailable)throw new PodStoreConverter.SourceKeyUnavailable(true);return raw;}
        public boolean cloudBindingValid(String user,String device){return binding;}
        public byte[] seal(JSONObject j)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);byte[] b=c.doFinal(j.toString().getBytes(StandardCharsets.UTF_8));return ByteBuffer.allocate(12+b.length).put(c.getIV()).put(b).array();}
        public JSONObject open(byte[] b)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(b,12)));return new JSONObject(new String(c.doFinal(Arrays.copyOfRange(b,12,b.length)),StandardCharsets.UTF_8));}
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static byte[] var(long n){ByteArrayOutputStream out=new ByteArrayOutputStream();do{int b=(int)(n&127);n>>>=7;out.write(b|(n!=0?128:0));}while(n!=0);return out.toByteArray();}
    static byte[] concat(byte[]... values)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] v:values)out.write(v);return out.toByteArray();}
    static byte[] field(int tag,byte[] b)throws Exception{return concat(var(tag),var(b.length),b);}
    static byte[] pref(String k,byte[] v)throws Exception{return field(10,concat(field(10,k.getBytes(StandardCharsets.UTF_8)),field(18,v)));}
    static byte[] string(String v)throws Exception{return field(42,v.getBytes(StandardCharsets.UTF_8));}
    static void write(File f,byte[] b)throws Exception{Files.createDirectories(f.getParentFile().toPath());Files.write(f.toPath(),b);}
    static void xml(File root,String name,String body)throws Exception{write(new File(root,"shared_prefs/"+name+".xml"),("<map>"+body+"</map>").getBytes(StandardCharsets.UTF_8));}
    static File fixture()throws Exception{
        File root=Files.createTempDirectory("pod-stores-source").toFile();
        for(String s:PodStoreConverter.STORES)if(!s.equals("settings")&&!s.equals("bili_cache"))xml(root,s,"");
        write(new File(root,"files/datastore/settings.preferences_pb.preferences_pb"),concat(pref("reading_font_size_sp",concat(var(24),var(18))),pref("oobe_seen_version",concat(var(24),var(2))),pref("tts_engine",string("minimax")),pref("tts_model",string("fixture-model")),pref("reading_theme_dark",concat(var(8),var(0))),pref("phone_connection_enabled",concat(var(8),var(0))),pref("llm_provider",string("synthetic-provider")),pref("llm_model",string("synthetic-model")),pref("llm_base_url",string("https://rehearsal.invalid")),pref("llm_system_prompt",string("synthetic-prompt")),pref("future_key",string("PRIVATE_USER_SETTING"))));
        write(new File(root,"files/datastore/bili_cache.preferences_pb.preferences_pb"),concat(pref("bili_search_history",string("[\"fixture\"]")),pref("bili_local_interaction_state_json",string("[{\"aid\":7,\"isLiked\":true,\"isCoined\":false,\"isFavorited\":true,\"updatedAt\":123}]")),pref("bili_playback_progress_json",string("[{\"aid\":7,\"cid\":8,\"positionMs\":900,\"durationMs\":1000,\"updatedAt\":123}]"))));
        xml(root,"bili_account_store","<string name=\"account_json\">{\"cookies\":{\"SESSDATA\":\"DO_NOT_LEAK_BILI\"},\"refreshToken\":\"DO_NOT_LEAK_REFRESH\",\"updatedAtMillis\":123,\"cookieRefreshCheckedAtMillis\":120,\"wbiImgKey\":\"synthetic-img\",\"wbiSubKey\":\"synthetic-sub\"}</string>");
        xml(root,"douyin_account_store","<string name=\"cookie\">DO_NOT_LEAK_DOUYIN</string>");
        xml(root,"watch_device_identity","<string name=\"device_id\">synthetic-device</string>");
        xml(root,"watchrss_account_state","<string name=\"state_json\">{\"userId\":\"synthetic-user\",\"backendBaseUrl\":\"https://synthetic.invalid\",\"watchAccessToken\":\"DO_NOT_LEAK_SECRET\"}</string>");
        xml(root,"tts_secure_prefs","<string name=\"tts_api_key_minimax\">DO_NOT_LEAK_TTS</string>");
        xml(root,"llm_secure_prefs","<string name=\"llm_api_key\">DO_NOT_LEAK_LLM</string>");
        xml(root,"watchrss_push","<long name=\"last_seen_notification_id\" value=\"99\"/>");
        xml(root,"reader_preset_state","<string name=\"reader_theme_mode\">SYSTEM</string><int name=\"watch_light_start_minutes\" value=\"480\"/><string name=\"watch_sun_latitude\">12.5</string>");
        xml(root,"home_entry_placement","<long name=\"notes_sort_order\" value=\"123456\"/><boolean name=\"notes_pinned\" value=\"true\"/>");
        xml(root,"watchrss_cloud_keys","<string name=\"account_000000000000000000000000_v1\">AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</string><int name=\"account_current_000000000000000000000000\" value=\"1\"/>");
        xml(root,"app_resume_state","<string name=\"intent_uri\">intent:#Intent;component=com.lightningstudio.watchrss/.HomeFeedListActivity;end</string>");
        xml(root,"douyin_watch_history","<set name=\"watched_ids\"><string>42</string></set><string name=\"history_json\">[{\"aweme_id\":\"42\",\"play_url\":\"https://example.invalid/video\",\"cover_url\":\"\",\"title\":\"fixture title\",\"author\":\"fixture author\",\"like_count\":3,\"watched_at\":123}]</string>");
        xml(root,"douyin_recent_window","<string name=\"recent_window_json\">{\"items\":[{\"awemeId\":\"42\",\"playUrl\":\"https://example.invalid/video\",\"coverUrl\":\"\",\"title\":\"fixture title\",\"author\":\"fixture author\",\"likeCount\":3,\"durationMs\":1000,\"playUrlResolvedAtMs\":123}],\"savedAtMs\":123,\"anchorAwemeId\":\"42\"}</string>");
        xml(root,"watchrss_daily_telemetry","<set name=\"days\"><string>2026-10-01</string></set><string name=\"day_2026-10-01\">{\"eventCounts\":{\"sync_account\":2},\"screenOpenCounts\":{},\"screenDurationMs\":{},\"appForegroundMs\":0,\"syncSuccessCount\":2,\"syncFailureCount\":0}</string>");
        xml(root,"future_store","<set name=\"unknown\"><string>a</string><string>b</string></set>");return root;
    }
    static Map<String,String> digests(File root)throws Exception{Map<String,String> d=new TreeMap<>();try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator)d.put(root.toPath().relativize(p).toString(),PodRoomV18Converter.hash(p.toFile()));}return d;}
    static File generation()throws Exception{File g=Files.createTempDirectory("pod-stores-generation").toFile();PodStoreConverter.json(new File(g,"reader-catalog.json"),new JSONObject("{\"version\":1,\"records\":[],\"assets\":{\"fonts\":[],\"backgrounds\":[],\"deletions\":[]}}"));return g;}
    static JSONObject report(File g,String store)throws Exception{return PodStoreConverter.json(new File(g,"stores-report.json")).getJSONObject(store);}
    static void remove(File root)throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    static void settingsAudit()throws Exception {
        Map<String,Object> source=new LinkedHashMap<>();
        source.put("cache_limit_bytes",512L*1048576);source.put("oobe_seen_version",3);source.put("reading_theme_dark",true);source.put("reading_font_size_sp",18);
        source.put("reader_auto_scroll_enabled",true);source.put("reader_auto_scroll_lines_per_second",2f);source.put("share_use_system",false);source.put("phone_connection_enabled",false);
        source.put("sync_media_keep_alive_enabled",true);source.put("media_volume_control_enabled",true);source.put("media_volume_guard_enabled",true);source.put("media_playback_start_volume_limit_percent",-1);
        source.put("rss_inline_image_prefetch_mode",1);source.put("llm_provider","provider");source.put("llm_model","model");source.put("llm_base_url","https://rehearsal.invalid");source.put("llm_system_prompt","prompt");
        source.put("llm_enabled",true);source.put("llm_auto_summarize",false);source.put("llm_show_token_usage",false);source.put("llm_prompt_preset",0);
        source.put("tts_engine","");source.put("tts_model","model");source.put("tts_voice_id","voice");source.put("tts_speed",1f);source.put("tts_base_url","");source.put("privacy_policy_agreed_version",1);
        source.put("temp_original_mode_hint_attempts_39","123,456");source.put("temp_original_mode_hint_last_toast_39",789L);
        check(source.size()==29);Set<String> unknown=new TreeSet<>(source.keySet());JSONObject output=new JSONObject();PodStoreMappings.settings(source,unknown,output);
        check(unknown.isEmpty());check(!output.getJSONObject("watchrss.settings").getBoolean("phoneConnectionEnabled"));check(output.getJSONObject("watchrss.settings").isNull("playbackStartVolumeLimit"));
        check(output.getJSONObject("watchrss.temporary-original-hints.v1").getJSONObject("39").getLong("lastToast")==789L);
        check(output.getJSONObject("watchrss.tts.config.v1").getString("engine").equals("local"));
        source.remove("media_playback_start_volume_limit_percent");unknown=new TreeSet<>(source.keySet());output=new JSONObject();PodStoreMappings.settings(source,unknown,output);check(output.getJSONObject("watchrss.settings").getInt("playbackStartVolumeLimit")==10);
        source.remove("tts_engine");unknown=new TreeSet<>(source.keySet());output=new JSONObject();PodStoreMappings.settings(source,unknown,output);check(unknown.isEmpty());check(output.getJSONObject("watchrss.tts.config.v1").getString("voiceId").equals("voice"));
        source.put("future_native_setting","retained");unknown=new TreeSet<>(source.keySet());PodStoreMappings.settings(source,unknown,new JSONObject());check(unknown.equals(Set.of("future_native_setting")));
    }
    public static void main(String[] args)throws Exception{
        settingsAudit();
        if(args.length>0&&args[0].equals("crash")){
            new PodStoreConverter(new File(args[1]),new Crypto(),(s,b)->{if(s.equals(args[3])&&b.equals(args[4]))Runtime.getRuntime().halt(91);}).copy(new File(args[2]));throw new AssertionError("crash boundary missing");
        }
        // Hash-colliding JSON names reorder on parse; equivalent number spelling is valid.
        JSONObject left=new JSONObject().put("Aa",1).put("BB",new JSONObject().put("x",1.0)),right=new JSONObject().put("BB",new JSONObject().put("x",1)).put("Aa",1L);
        check(PodStoreConverter.sameJson(left,right));check(!PodStoreConverter.sameJson(left,new JSONObject().put("Aa","1").put("BB",new JSONObject().put("x",1))));
        check(!PodStoreConverter.sameJson(new JSONArray("[1,2]"),new JSONArray("[2,1]")));
        File root=fixture(),g=generation();Crypto crypto=new Crypto();Map<String,String> before=digests(root);PodStoreConverter c=new PodStoreConverter(root,crypto);c.snapshot(g);c.copy(g);String receipt=c.verify(g);check(!c.complete());
        JSONObject kv=PodStoreConverter.json(new File(g,"podjs/podjs-kv.json"));check(kv.getJSONObject("watchrss.settings").getInt("fontSizeSp")==18);check(kv.getJSONObject("watchrss.onboarding").getInt("oobeSeenVersion")==2);check(!kv.getJSONObject("watchrss.onboarding").has("privacyPolicyAgreedVersion"));check(kv.getLong("announcement.last_seen_notification_id")==99);
        check(report(g,"settings").getString("status").equals("retained-unsupported"));check(report(g,"settings").getJSONArray("unknownKeys").getString(0).equals("future_key"));check(report(g,"watchrss_account_state").getBoolean("semanticValid"));
        check(kv.getJSONObject("watchrss.reader-presets").getJSONObject("selection").getString("mode").equals("SYSTEM"));check(kv.getJSONObject("watchrss.tts.config.v1").getString("engine").equals("minimax"));
        check(report(g,"watchrss_cloud_keys").getBoolean("bindingValid"));check(report(g,"watchrss_cloud_keys").getString("status").equals("retained-unsupported"));
        check(kv.getJSONArray("watchrss.bili.interactions.v1").getJSONObject(0).getBoolean("like"));check(new JSONArray(kv.getString("douyin.history.v1")).getJSONObject(0).getJSONObject("item").getString("description").equals("fixture title"));
        String publicReport=PodStoreConverter.json(new File(g,"stores-report.json")).toString();check(!publicReport.contains("DO_NOT_LEAK")&&!publicReport.contains("PRIVATE_USER_SETTING"));
        JSONObject secure=crypto.open(Files.readAllBytes(new File(g,"podjs/secure.store").toPath()));check(secure.has("state_json")&&secure.has("watchrss.tts.api-key.minimax"));check(!secure.has("llm_api_key"));check(report(g,"llm_secure_prefs").getString("status").equals("retained-unsupported"));
        c.copy(g);check(c.verify(g).equals(receipt));check(before.equals(digests(root)));
        if(args.length>0){File oracle=new File(args[0]);oracle.mkdirs();PodStoreConverter.json(new File(oracle,"kv.json"),kv);PodStoreConverter.json(new File(oracle,"synthetic-secure.json"),secure);}
        remove(g);
        // Every known store and unknown store interrupted before write, after files, after receipt.
        List<String> names=new ArrayList<>(Arrays.asList(PodStoreConverter.STORES));names.add("future_store");int boundaries=0;
        for(String name:names)for(String boundary:new String[]{"before","files","receipt"}){
            File candidate=generation();boolean[] hit={false};PodStoreConverter interrupted=new PodStoreConverter(root,crypto,(s,b)->{if(s.equals(name)&&b.equals(boundary)&&!hit[0]){hit[0]=true;throw new IOException("power_loss");}});interrupted.snapshot(candidate);try{interrupted.copy(candidate);throw new AssertionError("missing interruption");}catch(IOException expected){check(hit[0]);}c.resume(candidate,PodTakeoverCoordinator.Phase.COPYING);c.copy(candidate);c.verify(candidate);check(before.equals(digests(root)));remove(candidate);boundaries++;
        }
        int processCrashes=0;
        for(String name:names)for(String boundary:new String[]{"before","files","receipt"}){
            File candidate=generation();c.snapshot(candidate);
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-ea","-cp",System.getProperty("java.class.path"),StoreTest.class.getName(),"crash",root.toString(),candidate.toString(),name,boundary).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            check(child.waitFor()==91);c.resume(candidate,PodTakeoverCoordinator.Phase.COPYING);c.copy(candidate);c.verify(candidate);check(before.equals(digests(root)));remove(candidate);processCrashes++;
        }
        // Locking is retryable: no needs-reauth receipt is committed for a locked source.
        crypto.locked=true;g=generation();c.snapshot(g);try{c.copy(g);throw new AssertionError();}catch(PodStoreConverter.Locked expected){}crypto.locked=false;c.copy(g);c.verify(g);check(report(g,"watchrss_account_state").getString("status").equals("migrated"));remove(g);
        crypto.binding=false;g=generation();c.snapshot(g);c.copy(g);check(!report(g,"watchrss_cloud_keys").getBoolean("bindingValid")&&report(g,"watchrss_cloud_keys").getString("activationDisposition").equals("known-difference"));remove(g);crypto.binding=true;
        // Crypto failure becomes explicit needs-reauth; source never reset and retry preserves report.
        crypto.unavailable=true;g=generation();c.snapshot(g);c.copy(g);check(report(g,"watchrss_account_state").getString("status").equals("needs-reauth"));c.verify(g);check(!PodStoreConverter.json(new File(g,"stores-report.json")).toString().contains("DO_NOT_LEAK"));remove(g);crypto.unavailable=false;
        // Authenticated receipt tampering and candidate ciphertext truncation fail closed.
        g=generation();c.snapshot(g);c.copy(g);File accountDir=new File(g,"store-results/"+PodStoreConverter.slot("watchrss_account_state"));File receiptFile=new File(accountDir,"receipt.json");JSONObject tampered=PodStoreConverter.json(receiptFile);tampered.put("status","absent");PodStoreConverter.json(receiptFile,tampered);try{c.verify(g);throw new AssertionError();}catch(IOException expected){}remove(g);
        g=generation();c.snapshot(g);c.copy(g);Files.write(new File(g,"podjs/secure.store").toPath(),new byte[]{1,2,3});try{c.verify(g);throw new AssertionError();}catch(Exception expected){}remove(g);
        // SharedPreferences backups are never silently ignored in favor of stale main XML.
        write(new File(root,"shared_prefs/watchrss_push.xml.bak"),"<map/>".getBytes(StandardCharsets.UTF_8));g=generation();c.snapshot(g);c.copy(g);check(report(g,"watchrss_push").getString("status").equals("failed"));remove(g);Files.delete(new File(root,"shared_prefs/watchrss_push.xml.bak").toPath());
        // Aggregated reader catalog exceeds the KV quota: report failure, never publish.
        g=generation();JSONObject hugeCatalog=PodStoreConverter.json(new File(g,"reader-catalog.json"));hugeCatalog.getJSONArray("records").put(new JSONObject().put("payloadJson",new String(new char[4*1024*1024]).replace('\0','x')));PodStoreConverter.json(new File(g,"reader-catalog.json"),hugeCatalog);c.snapshot(g);try{c.copy(g);throw new AssertionError();}catch(IOException expected){check(expected.getMessage().equals("kv_quota"));}check(!new File(g,"podjs/podjs-kv.json").exists());check(!PodStoreConverter.json(new File(g,"stores-validation.json")).getBoolean("candidateValid"));remove(g);
        // Ambiguous filename, malformed protobuf, invalid quota and exact MiB conversion.
        write(new File(root,"files/datastore/settings.preferences_pb"),new byte[0]);g=generation();c.snapshot(g);c.copy(g);check(report(g,"settings").getString("reason").equals("ambiguous_store_files"));remove(g);Files.delete(new File(root,"files/datastore/settings.preferences_pb").toPath());
        write(new File(root,"files/datastore/settings.preferences_pb.preferences_pb"),pref("cache_limit_bytes",concat(var(32),var(536870913))));g=generation();c.snapshot(g);c.copy(g);check(report(g,"settings").getString("status").equals("failed"));check(!PodStoreConverter.json(new File(g,"podjs/podjs-kv.json")).has("watchrss.settings"));remove(g);
        try{PodStoreConverter.validateKv(new JSONObject().put("large",new String(new char[4*1024*1024]).replace('\0','x')));throw new AssertionError();}catch(IOException expected){}
        xml(root,"bad","<!DOCTYPE bad><string name=\"x\">x</string>");try{PodPreferenceReader.xml(new File(root,"shared_prefs/bad.xml"));throw new AssertionError();}catch(Exception expected){}
        // Tampering with authenticated per-store reports or target plaintext fails verification.
        g=generation();c.snapshot(g);c.copy(g);File target=new File(g,"podjs/podjs-kv.json");PodStoreConverter.json(target,new JSONObject());try{c.verify(g);throw new AssertionError();}catch(IOException expected){}remove(g);
        File absent=generation();File empty=generation();PodStoreConverter absentConverter=new PodStoreConverter(empty,crypto);absentConverter.snapshot(absent);absentConverter.copy(absent);absentConverter.verify(absent);for(String s:PodStoreConverter.STORES)check(report(absent,s).getString("status").equals("absent"));remove(absent);remove(empty);remove(root);
        System.out.println("StoreTest PASS: "+boundaries+" interruption boundaries + "+processCrashes+" process crashes; source unchanged, protobuf/XML, crypto failures, privacy, quota, ambiguity, tamper, absent");
    }
}
