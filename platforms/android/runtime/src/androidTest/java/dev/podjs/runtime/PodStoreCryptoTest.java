package dev.podjs.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Compile-only in P2c. All writes are synthetic fixtures with unique aliases.
 * NEVER uses the native MasterKey alias, native store name or a real user context. */
public final class PodStoreCryptoTest {
    private KeyStore keys()throws Exception{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);return k;}
    @Test public void accountContextPrefixesTheFileButKeepsLogicalAndroidxAad()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotEquals("com.lightningstudio.watchrss",context.getPackageName());
        String id="pod-takeover-scoped-"+UUID.randomUUID(),master=id+"-master",target=id+"-target";
        String logical="douyin_account_store",physical="account_"+PodStoreConverter.slot(id)+"_"+logical;
        Context workspace=new android.content.ContextWrapper(context){
            public Context getApplicationContext(){return this;}
            public SharedPreferences getSharedPreferences(String name,int mode){return context.getSharedPreferences("account_"+uncheckedScope()+"_"+name,mode);}
            private String uncheckedScope(){return physical.substring(8,72);}
        };
        File source=new File(context.getApplicationInfo().dataDir,"shared_prefs/"+physical+".xml").getCanonicalFile();
        try {
            MasterKey key=new MasterKey.Builder(context,master).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            SharedPreferences fixture=EncryptedSharedPreferences.create(workspace,logical,key,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            assertTrue(fixture.edit().putString("cookie","synthetic-nonempty-cookie").commit());
            byte[] before=Files.readAllBytes(source.toPath());PodStoreAndroidCrypto crypto=new PodStoreAndroidCrypto(master,target);
            Map<String,Object> values=crypto.decrypt(logical,PodPreferenceReader.xml(source));assertEquals("synthetic-nonempty-cookie",values.get("cookie"));
            try{crypto.decrypt(physical,PodPreferenceReader.xml(source));fail("physical filename accepted as logical AAD");}catch(PodStoreConverter.SourceKeyUnavailable expected){assertFalse(expected.missing);}
            Set<String> unused=new TreeSet<>(values.keySet());JSONObject secret=new JSONObject();PodStoreMappings.convert(logical,values,unused,new JSONObject(),secret,new JSONObject());
            assertTrue(unused.isEmpty());assertEquals("synthetic-nonempty-cookie",secret.getString("douyin.cookie"));assertArrayEquals(before,Files.readAllBytes(source.toPath()));
        }finally{context.deleteSharedPreferences(physical);keys().deleteEntry(master);keys().deleteEntry(target);}
    }
    @Test public void readsAndroidXFixturesWithoutRepairAndUsesProductionSecureReader()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="pod-takeover-test-"+UUID.randomUUID(),master=id+"-master",target=id+"-target";
        File source=new File(context.getApplicationInfo().dataDir,"shared_prefs/"+id+".xml").getCanonicalFile();
        try {
            MasterKey fixtureMaster=new MasterKey.Builder(context,master).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            SharedPreferences fixture=EncryptedSharedPreferences.create(context,id,fixtureMaster,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            assertTrue(fixture.edit().putString("state_json","{\"userId\":\"fixture\",\"watchAccessToken\":\"fixture-token\"}").putString("tts_api_key_minimax","fixture-tts").putInt("account_current_000000000000000000000000",1).putLong("long",42L).putFloat("float",1.25f).putBoolean("bool",true).putStringSet("set",new HashSet<>(Arrays.asList("a","b"))).commit());
            byte[] before=Files.readAllBytes(source.toPath());Set<String> aliasesBefore=new HashSet<>(Collections.list(keys().aliases()));
            PodStoreAndroidCrypto crypto=new PodStoreAndroidCrypto(master,target);
            Map<String,Object> decoded=crypto.decrypt(id,PodPreferenceReader.xml(source));assertEquals("fixture-tts",decoded.get("tts_api_key_minimax"));assertEquals(1,decoded.get("account_current_000000000000000000000000"));assertEquals(42L,decoded.get("long"));assertEquals(1.25f,decoded.get("float"));assertEquals(true,decoded.get("bool"));assertEquals(new HashSet<>(Arrays.asList("a","b")),decoded.get("set"));
            assertArrayEquals(before,Files.readAllBytes(source.toPath()));assertEquals(aliasesBefore,new HashSet<>(Collections.list(keys().aliases())));
            Map<String,Object> missingKeyset=new HashMap<>(PodPreferenceReader.xml(source));missingKeyset.remove(PodStoreAndroidCrypto.KEYS);
            try{crypto.decrypt(id,missingKeyset);fail("missing keyset accepted");}catch(Exception expected){}
            assertEquals(aliasesBefore,new HashSet<>(Collections.list(keys().aliases())));assertArrayEquals(before,Files.readAllBytes(source.toPath()));
            JSONObject values=new JSONObject().put("state_json",decoded.get("state_json")).put("watchrss.tts.api-key.minimax",decoded.get("tts_api_key_minimax"));
            byte[] candidate=crypto.seal(values);assertEquals(values.toString(),crypto.open(candidate).toString());
            // Same decoder/AAD/format as PodDeviceServices, reading an isolated target alias.
            File output=File.createTempFile("candidate-secure-",".store",context.getCacheDir());
            try{Files.write(output.toPath(),candidate);assertEquals(values.toString(),PodDeviceServices.readSecureFile(output,(javax.crypto.SecretKey)keys().getKey(target,null)).toString());}finally{Files.deleteIfExists(output.toPath());}
            keys().deleteEntry(master);Set<String> afterLoss=new HashSet<>(Collections.list(keys().aliases()));
            try{crypto.decrypt(id,PodPreferenceReader.xml(source));fail("missing source alias accepted");}catch(PodStoreConverter.SourceKeyUnavailable expected){assertTrue(expected.missing);}
            Map<String,Object> malformed=new HashMap<>(PodPreferenceReader.xml(source));malformed.put(PodStoreAndroidCrypto.KEYS,"not-hex");
            try{crypto.decrypt(id,malformed);fail("malformed keyset accepted");}catch(PodStoreConverter.SourceKeyUnavailable misclassified){fail("malformed keyset classified as missing key");}catch(Exception expected){}
            assertEquals(afterLoss,new HashSet<>(Collections.list(keys().aliases())));assertArrayEquals(before,Files.readAllBytes(source.toPath()));assertFalse(keys().containsAlias(master));
        }finally{context.deleteSharedPreferences(id);keys().deleteEntry(master);keys().deleteEntry(target);}
    }
    @Test public void existingCloudPairIsVerifiedWithoutRegeneration()throws Exception{
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=31);
        String user="fixture-"+UUID.randomUUID(),device="fixture-device";
        String alias="watchrss_cloud_ecdh_"+PodRoomV18Converter.hex(java.security.MessageDigest.getInstance("SHA-256").digest((user+":"+device).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,24);
        try {
            java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC","AndroidKeyStore");
            generator.initialize(new android.security.keystore.KeyGenParameterSpec.Builder(alias,android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1")).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build());generator.generateKeyPair();
            Set<String> before=new HashSet<>(Collections.list(keys().aliases()));PodStoreAndroidCrypto crypto=new PodStoreAndroidCrypto();
            assertTrue(crypto.cloudBindingValid(user,device));assertFalse(crypto.cloudBindingValid(user,"missing-device"));assertEquals(before,new HashSet<>(Collections.list(keys().aliases())));
        }finally{keys().deleteEntry(alias);}
    }
    @Test public void missingKeysetDoesNotCreateOrReplaceAnything()throws Exception{
        String id="pod-takeover-test-"+UUID.randomUUID();Set<String> before=new HashSet<>(Collections.list(keys().aliases()));
        try{new PodStoreAndroidCrypto(id,id+"-target").decrypt("fixture",Collections.emptyMap());fail("missing alias accepted");}catch(Exception expected){}
        assertEquals(before,new HashSet<>(Collections.list(keys().aliases())));
    }
}
