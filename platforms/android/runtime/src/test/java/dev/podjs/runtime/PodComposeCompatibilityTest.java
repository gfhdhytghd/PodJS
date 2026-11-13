package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class PodComposeCompatibilityTest {
    static final PodStoreConverter.Crypto CRYPTO=new PodStoreConverter.Crypto(){
        public Map<String,Object> decrypt(String name,Map<String,Object> values){return values;}
        public byte[] seal(JSONObject values){return values.toString().getBytes(StandardCharsets.UTF_8);}
        public JSONObject open(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    };
    static void xml(File root,String name,String body)throws Exception {
        File f=new File(root,"shared_prefs/"+name+".xml");f.getParentFile().mkdirs();
        Files.write(f.toPath(),("<?xml version=\"1.0\" encoding=\"utf-8\"?><map>"+body+"</map>").getBytes(StandardCharsets.UTF_8));
    }
    @Test public void realComposeFlipSettingsAndSeenDisclosureAreConsumed()throws Exception {
        Map<String,Object> settings=new HashMap<>();settings.put("reader_page_mode",true);settings.put("reader_page_seconds",20);
        Set<String> unknown=new TreeSet<>(settings.keySet());JSONObject kv=new JSONObject();
        PodStoreMappings.convert("settings",settings,unknown,kv,new JSONObject(),new JSONObject());
        assertTrue("real Compose keys must not block at 93%",unknown.isEmpty());
        assertTrue(kv.getJSONObject("watchrss.settings").getBoolean("readerPageMode"));
        assertEquals(20,kv.getJSONObject("watchrss.settings").getInt("readerPageSeconds"));
        Map<String,Object> disclosure=Map.of("initial_app_disclosure_v1",true);
        unknown=new TreeSet<>(disclosure.keySet());
        PodStoreMappings.convert("watchrss_transparency",disclosure,unknown,kv,new JSONObject(),new JSONObject());
        assertTrue(unknown.isEmpty());assertTrue(kv.getBoolean("watchrss.transparency.initial_app_disclosure_v1"));
    }
    @Test public void incompleteNativePhoneArticleChunksArePreservedWithoutAdmittingUnknownFiles()throws Exception {
        File root=Files.createTempDirectory("compose-phone-checkpoints").toFile();
        try {
            String base="files/phone-article-checkpoints/"+"a".repeat(64)+"/"+"b".repeat(64)+"/";
            File source=new File(root,base+"0.bin");source.getParentFile().mkdirs();Files.write(source.toPath(),new byte[]{1,2,3});
            assertEquals("native-transfer-checkpoint",PodFileConverter.category(base+"0.bin"));
            assertEquals("native-transfer-checkpoint",PodFileConverter.category(base+"2147483647.bin"));
            for(String name:new String[]{"-1.bin","01.bin","2147483648.bin","unknown.bin","0.bin/child","0.data"})assertEquals("unknown-user-content",PodFileConverter.category(base+name));
            PodFileConverter converter=new PodFileConverter(root,file->{throw new AssertionError("snapshot must not open SQLite");},(f,n)->{});
            File g=new File(root,"generation");converter.snapshot(g);converter.resume(g,PodTakeoverCoordinator.Phase.COPYING);
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(source.toPath()));
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(g,"files-source/"+base+"0.bin").toPath()));
            assertFalse(new File(g,"podjs/files/phone-article-checkpoints").exists());
        }finally{PodScratchIdentityTest.remove(root);}
    }
    @Test public void scopedDataStoresAreIncludedInTheFixedInventory()throws Exception {
        File root=Files.createTempDirectory("compose-profile").toFile();
        try {
            String scope="a".repeat(64);xml(root,"watchrss_library_ownership","<string name=\"legacy_owner\">"+PodTakeoverProfile.space("unbound",null)+"</string>");xml(root,"account_"+scope+"_bili_account_store","");
            File ds=new File(root,"files/accounts/"+scope+"/datastore/settings.preferences_pb.preferences_pb");ds.getParentFile().mkdirs();Files.write(ds.toPath(),new byte[0]);
            File generation=new File(root,"candidate");
            new PodStoreConverter(root,CRYPTO).snapshot(generation);
            JSONObject entries=PodStoreConverter.json(new File(generation,"stores-snapshot.json")).getJSONObject("entries");
            assertTrue("account DataStore must be snapshotted",entries.has("files/accounts/"+scope+"/datastore/settings.preferences_pb.preferences_pb"));
        }finally{PodScratchIdentityTest.remove(root);}
    }
    static void catalog(File generation)throws Exception {
        PodStoreConverter.json(new File(generation,"reader-catalog.json"),new JSONObject("{\"version\":1,\"records\":[],\"assets\":{\"fonts\":[],\"backgrounds\":[],\"deletions\":[]}}"));
    }
    @Test public void selectedAccountKeepsLoginAndSettingsWithoutMergingAnonymousData()throws Exception {
        File root=Files.createTempDirectory("compose-account").toFile();
        try {
            String scope=PodTakeoverProfile.space("https://example.invalid","synthetic-user"),guest=PodTakeoverProfile.space("unbound",null);
            xml(root,"watchrss_library_ownership","<string name=\"legacy_owner\">"+guest+"</string>");
            xml(root,"watchrss_account_state","<string name=\"state_json\">{\"userId\":\"synthetic-user\",\"backendBaseUrl\":\"https://example.invalid/\",\"watchAccessToken\":\"synthetic-token\"}</string>");
            xml(root,"home_entry_placement","<boolean name=\"notes_pinned\" value=\"false\"/>");
            xml(root,"account_"+scope+"_home_entry_placement","<boolean name=\"notes_pinned\" value=\"true\"/>");
            String encryptedName="account_"+scope+"_douyin_account_store";
            xml(root,encryptedName,"<string name=\"cookie\">synthetic-cookie</string>");
            List<String> decrypted=new ArrayList<>();
            PodStoreConverter.Crypto crypto=new PodStoreConverter.Crypto(){
                public Map<String,Object> decrypt(String name,Map<String,Object> values){decrypted.add(name);return values;}
                public byte[] seal(JSONObject values)throws Exception{return CRYPTO.seal(values);}
                public JSONObject open(byte[] bytes)throws Exception{return CRYPTO.open(bytes);}
            };
            File g=new File(root,"generation");catalog(g);PodStoreConverter converter=new PodStoreConverter(root,crypto);
            converter.snapshot(g);assertFalse(PodTakeoverProfile.load(g).legacy);assertEquals("databases/accounts/"+scope+"/watchrss.db",PodTakeoverProfile.load(g).database());
            converter.copy(g);converter.verify(g);
            assertTrue(decrypted.contains("douyin_account_store"));assertFalse(decrypted.contains(encryptedName));
            assertTrue(PodStoreConverter.json(new File(g,"podjs/podjs-kv.json")).getBoolean("watchrss.home.notes-pinned"));
            JSONObject secure=crypto.open(Files.readAllBytes(new File(g,"podjs/secure.store").toPath()));
            assertEquals("synthetic-cookie",secure.getString("douyin.cookie"));
            assertEquals("synthetic-user",new JSONObject(secure.getString("state_json")).getString("userId"));
            assertEquals("[\"https://example.invalid\",\"synthetic-user\"]",secure.getString("watchrss.library.owner"));
            xml(root,"watchrss_account_state",""); // Re-entry uses the fixed snapshot, never a changed live selection.
            assertEquals(scope,PodTakeoverProfile.load(g).scope);converter.verify(g);
            assertTrue(new File(root,"shared_prefs/home_entry_placement.xml").isFile());
        }finally{PodScratchIdentityTest.remove(root);}
    }
    @Test public void everyCatalogueKeyIsMappedOrExplicitlyRetainedAndUnknownKeysStillFailClosed()throws Exception {
        JSONObject contract=new JSONObject(PodRoomV18Converter.resource("compose-preferences.json"));
        JSONArray rows=contract.getJSONArray("keys");Set<String> stores=new HashSet<>(),seen=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String store=row.getString("store"),key=row.optString("sampleKey",row.getString("key"));
            assertTrue("duplicate contract key",seen.add(store+":"+row.getString("key")));stores.add(store);
            Object value=row.get("sample");switch(row.getString("type")){
                case "int":value=((Number)value).intValue();break;
                case "long":value=((Number)value).longValue();break;
                case "float":value=((Number)value).floatValue();break;
                case "set":Set<String> set=new HashSet<>();JSONArray a=(JSONArray)value;for(int j=0;j<a.length();j++)set.add(a.getString(j));value=set;break;
            }
            Map<String,Object> input=new HashMap<>();input.put(key,value);
            if(store.equals("watchrss_daily_telemetry")&&key.startsWith("day_"))input.put("days",new HashSet<>(Arrays.asList(key.substring(4))));
            Set<String> unused=new TreeSet<>(input.keySet());JSONObject report=new JSONObject().put("reason","unmapped_keys_retained");
            PodStoreMappings.convert(store,input,unused,new JSONObject(),new JSONObject(),report);
            PodStorePolicy.retainKnown(store,input,unused,report);report.put("unknownKeys",new JSONArray(unused));
            assertTrue("uncovered contract key: "+store+"/"+row.getString("key"),unused.isEmpty()||PodStorePolicy.knownRetention(store,report));
        }
        for(String store:stores){
            Map<String,Object> input=Collections.singletonMap("future_unreviewed_key","synthetic");Set<String> unused=new TreeSet<>(input.keySet());JSONObject report=new JSONObject().put("reason","unmapped_keys_retained");
            PodStoreMappings.convert(store,input,unused,new JSONObject(),new JSONObject(),report);PodStorePolicy.retainKnown(store,input,unused,report);
            assertEquals(Collections.singleton("future_unreviewed_key"),unused);report.put("unknownKeys",new JSONArray(unused));assertFalse(PodStorePolicy.knownRetention(store,report));
        }
    }
    static void room(File path,int id)throws Exception {
        path.getParentFile().mkdirs();
        try(PodRoomAndroidDb db=new PodRoomAndroidDb(path)){
            JSONArray sql=new JSONObject(PodRoomV18Converter.resource("room-v18.json")).getJSONArray("sql");
            for(int i=0;i<sql.length();i++)db.exec(sql.getString(i));db.exec("PRAGMA user_version=18");
            for(int n=0;n<2;n++)db.exec("INSERT INTO rss_channels(id,url,title,createdAt,sortOrder,isPinned,useOriginalContent,continuePlaybackInBackground) VALUES(?,?,?,1,1,0,0,0)",id+n,"https://example.invalid/"+(id+n),"synthetic-"+(id+n));
        }
    }
    @Test public void completeAccountRoomMigrationAndInterruptedReentrySelectOnlyCurrentWorkspace()throws Exception {
        for(boolean pause:new boolean[]{false,true}){
            File root=Files.createTempDirectory("compose-workspace-room").toFile();
            try {
                String scope=PodTakeoverProfile.space("https://example.invalid","synthetic-user");
                xml(root,"watchrss_library_ownership","<string name=\"legacy_owner\">"+PodTakeoverProfile.space("unbound",null)+"</string>");
                xml(root,"watchrss_account_state","<string name=\"state_json\">{\"userId\":\"synthetic-user\",\"backendBaseUrl\":\"https://example.invalid\",\"watchAccessToken\":\"synthetic-token\"}</string>");
                xml(root,"watch_device_identity","<string name=\"device_id\">synthetic-device</string>");
                File anonymous=new File(root,"databases/watchrss.db"),account=new File(root,"databases/accounts/"+scope+"/watchrss.db");room(anonymous,10);room(account,20);
                String beforeAnonymous=PodStoreConverter.hash(anonymous),beforeAccount=PodStoreConverter.hash(account);
                boolean[] stop={pause};
                PodRoomV18Converter room=new PodRoomV18Converter(anonymous,PodRoomAndroidDb::new,"synthetic-device",1,c->{if(stop[0]){stop[0]=false;throw new IOException("synthetic_interruption");}}).withProfileSandbox(root);
                PodTakeoverConverters bundle=new PodTakeoverConverters(room,new PodStoreConverter(root,CRYPTO),new PodFileConverter(root,PodRoomAndroidDb::new,(f,n)->{}));
                PodTakeoverCoordinator coordinator=new PodTakeoverCoordinator(new File(root,"migration"),()->new PodTakeoverCoordinator.Source(true,null,18,0,1),bundle);
                Properties result=coordinator.run();
                if(pause){assertEquals("PAUSED",result.getProperty("phase"));result=coordinator.run();}
                assertEquals(result.toString(),"ACTIVE",result.getProperty("phase"));File g=coordinator.activeGeneration(result);
                try(PodRoomAndroidDb db=new PodRoomAndroidDb(new File(g,"podjs.sqlite"))){
                    assertEquals(2L,((Number)PodRoomV18Converter.scalar(db,"SELECT count(*) FROM channels")).longValue());
                    assertEquals(20L,((Number)PodRoomV18Converter.scalar(db,"SELECT min(id) FROM channels")).longValue());
                }
                assertEquals(beforeAnonymous,PodStoreConverter.hash(anonymous));assertEquals(beforeAccount,PodStoreConverter.hash(account));
                Properties digest=PodTakeoverBackup.snapshot(root,g),independent=dev.podjs.takeover.TakeoverDigest.snapshot(root,Collections.emptyMap());
                assertEquals("selected DB hash must not overwrite the anonymous DB hash",dev.podjs.takeover.TakeoverDigest.digestManifest(independent,"SNAPSHOTTED",g.getName()),dev.podjs.takeover.TakeoverDigest.digestManifest(digest,"SNAPSHOTTED",g.getName()));
                assertEquals("ACTIVE",coordinator.run().getProperty("phase"));
            }finally{PodScratchIdentityTest.remove(root);}
        }
    }
}
