package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Complete migration with isolated-UID fixtures and negative publication checks. */
public final class StorePolicyTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final class Crypto extends StoreTest.Crypto {
        String failure="missing";
        public Map<String,Object> decrypt(String store,Map<String,Object> raw)throws Exception {
            if(failure.equals("locked"))throw new PodStoreConverter.Locked();
            if(failure.equals("missing"))throw new PodStoreConverter.SourceKeyUnavailable(true);
            if(failure.equals("authentication"))throw new PodStoreConverter.SourceKeyUnavailable(false);
            if(failure.equals("unexpected"))throw new IOException("DO_NOT_LEAK_SECRET");
            return raw;
        }
    }
    static PodTakeoverCoordinator coordinator(File source,File target,Crypto crypto,boolean isolated) {
        PodTakeoverConverters bundle=new PodTakeoverConverters(
            new PodRoomV18Converter(new File(source,"databases/watchrss.db"),RoomV18Test.Jdbc::new,g->PodStoreConverter.identity(new File(g,"stores-source"))),
            new PodStoreConverter(source,crypto,isolated),FileTakeoverTest.files(source,b->{}));
        return new PodTakeoverCoordinator(target,()->PodTakeoverSource.detect(source,true),bundle,p->{});
    }
    static File fixture()throws Exception {
        File source=FileTakeoverTest.fixture();
        StoreTest.xml(source,"watchrss_account_state","<string name=\"state_json\">PRIVATE_TOKEN</string>");
        StoreTest.xml(source,"bili_account_store","<string name=\"account_json\">PRIVATE_COOKIE</string>");
        StoreTest.xml(source,"douyin_account_store","<string name=\"cookie\">PRIVATE_COOKIE</string>");
        StoreTest.xml(source,"llm_secure_prefs","<string name=\"keyset\">PRIVATE_KEY</string>");
        StoreTest.xml(source,"tts_secure_prefs","<string name=\"keyset\">PRIVATE_KEY</string>");
        StoreTest.xml(source,"WebViewChromiumPrefs","<int name=\"lastVersionCodeUsed\" value=\"123\"/>");
        StoreTest.xml(source,"watchrss_installation_identity","<string name=\"install_id\">synthetic-install</string><long name=\"first_installed_at\" value=\"123\"/>");
        StoreTest.xml(source,"app_resume_state","<string name=\"intent_uri\">untrusted-native-route</string>");
        StoreTest.write(new File(source,"files/datastore/settings.preferences_pb"),StoreTest.concat(StoreTest.pref("reading_font_size_sp",StoreTest.concat(StoreTest.var(24),StoreTest.var(18))),StoreTest.pref("tts_engine",StoreTest.string("minimax")),StoreTest.pref("temp_original_mode_hint_attempts_39",StoreTest.string("123,456"))));
        return source;
    }
    static void isolated()throws Exception {
        File source=fixture(),target=Files.createTempDirectory("p5-target").toFile();Crypto crypto=new Crypto();Map<String,String> before=StoreTest.digests(source);
        try {
            PodTakeoverCoordinator c=coordinator(source,target,crypto,true);Properties p=c.run();check(p.getProperty("phase").equals("ACTIVE"),"isolated complete activation: "+p);
            File g=c.activeGeneration(p);JSONObject diagnostics=PodStoreConverter.json(new File(g,"stores-diagnostics.json"));
            check(diagnostics.getInt("blockerCount")==0&&diagnostics.getInt("knownDifferenceCount")==9,"all expected differences reported");
            check(!diagnostics.toString().contains("PRIVATE")&&!diagnostics.toString().contains("temp_original"),"diagnostics contain no values/keys");
            JSONObject secure=crypto.open(Files.readAllBytes(new File(g,"podjs/secure.store").toPath()));check(!secure.has("state_json")&&!secure.has("douyin.cookie")&&!secure.has("watchrss.bili.session.v1"),"no invented credentials");
            JSONObject kv=PodStoreConverter.json(new File(g,"podjs/podjs-kv.json"));check(kv.getJSONObject("watchrss.settings").getInt("fontSizeSp")==18,"settings migrated");check(!kv.has("watchrss.tts.config.v1"),"unavailable selected TTS explicitly held");
            check(new File(g,"podjs/files/article_content/fixture.txt").isFile(),"body migrated");
            check(!new File(g,"podjs/files/profileinstaller_profileWrittenFor_lastUpdateTime.dat").exists()&&!new File(g,"podjs/files/bili-smoke-00000000-0000-0000-0000-000000000000.preferences_pb").exists(),"test settings/platform marker never published");
            String generation=p.getProperty("generation"),receipt=p.getProperty("receipt");kv.put("new.user.state",7);PodStoreConverter.json(new File(g,"podjs/podjs-kv.json"),kv);
            Properties restarted=coordinator(source,target,crypto,true).run();check(restarted.getProperty("phase").equals("ACTIVE")&&generation.equals(restarted.getProperty("generation"))&&receipt.equals(restarted.getProperty("receipt")),"terminal restart, no reimport");
            check(PodStoreConverter.json(new File(g,"podjs/podjs-kv.json")).getInt("new.user.state")==7,"new writes survive");
            check(before.equals(StoreTest.digests(source)),"source unchanged");
            try{coordinator(source,target,crypto,false).run();throw new AssertionError("formal adopted isolated generation");}catch(IOException expected){check(expected.getMessage().equals("isolated_generation_not_admissible"),"isolated admission reason");}
        } finally{StoreTest.remove(source);StoreTest.remove(target);}
    }
    static void rejection(String kind)throws Exception {
        File source=fixture(),target=Files.createTempDirectory("p5-reject").toFile();Crypto crypto=new Crypto();boolean isolated=true;
        if(kind.equals("formal"))isolated=false;
        if(kind.equals("unexpected"))crypto.failure="unexpected";
        if(kind.equals("authentication"))crypto.failure="authentication";
        if(kind.equals("locked"))crypto.failure="locked";
        if(kind.equals("unknown"))StoreTest.xml(source,"PRIVATE_STORE_NAME","<string name=\"private_key\">PRIVATE_VALUE</string>");
        if(kind.equals("extra_webview"))StoreTest.xml(source,"WebViewChromiumPrefs","<int name=\"lastVersionCodeUsed\" value=\"123\"/><string name=\"private_key\">PRIVATE_VALUE</string>");
        if(kind.equals("malformed"))StoreTest.write(new File(source,"shared_prefs/llm_secure_prefs.xml"),"not XML".getBytes());
        if(kind.equals("backup"))StoreTest.write(new File(source,"shared_prefs/tts_secure_prefs.xml.bak"),"<map/>".getBytes());
        if(kind.equals("unknown_settings")){File settings=new File(source,"files/datastore/settings.preferences_pb");StoreTest.write(settings,StoreTest.concat(Files.readAllBytes(settings.toPath()),StoreTest.pref("PRIVATE_KEY",StoreTest.string("PRIVATE_VALUE"))));}
        if(kind.equals("malformed_settings"))StoreTest.write(new File(source,"files/datastore/settings.preferences_pb"),new byte[]{1,2,3});
        Map<String,String> before=StoreTest.digests(source);
        try {
            Properties p=coordinator(source,target,crypto,isolated).run();check(!p.getProperty("phase").equals("ACTIVE"),kind+" rejected");check(!new File(target,"active").exists(),kind+" no publication");check(before.equals(StoreTest.digests(source)),kind+" source unchanged");
            if(kind.equals("locked")){
                crypto.failure="missing";check(coordinator(source,target,crypto,true).run().getProperty("phase").equals("ACTIVE"),"locked retry proceeds");
            }else{
                File g=new File(target,"generations/"+p.getProperty("generation"));JSONObject diagnostics=PodStoreConverter.json(new File(g,"stores-diagnostics.json"));check(!diagnostics.toString().contains("PRIVATE"),"diagnostic sanitization");check(diagnostics.getInt("blockerCount")>0,"explicit blockers");
            }
        }finally{StoreTest.remove(source);StoreTest.remove(target);}
    }
    static void accountsOnly()throws Exception {
        for(String failure:new String[]{"missing","authentication"}){
            File source=FileTakeoverTest.fixture(),target=Files.createTempDirectory("p5-accounts").toFile();Crypto crypto=new Crypto();crypto.failure=failure;
            try {
                for(String name:new String[]{"watchrss_account_state","bili_account_store","douyin_account_store"})StoreTest.xml(source,name,"<string name=\"secret\">PRIVATE_VALUE</string>");
                Map<String,String> before=StoreTest.digests(source);PodTakeoverCoordinator c=coordinator(source,target,crypto,false);Properties p=c.run();check(p.getProperty("phase").equals("ACTIVE"),"native allowed account recovery");File g=c.activeGeneration(p);check(PodStoreConverter.json(new File(g,"stores-diagnostics.json")).getInt("needsReauthCount")==3,"explicit reauth");check(before.equals(StoreTest.digests(source)),"native recovery source unchanged");
            }finally{StoreTest.remove(source);StoreTest.remove(target);}
        }
    }
    public static void main(String[] args)throws Exception {
        isolated();accountsOnly();for(String kind:new String[]{"formal","unexpected","authentication","locked","unknown","extra_webview","malformed","backup","unknown_settings","malformed_settings"})rejection(kind);
        System.out.println("StorePolicyTest PASS: isolated ACTIVE/restart, core data/source integrity, native account recovery, formal/unknown/schema/crypto/backup rejection and locked retry");
    }
}
