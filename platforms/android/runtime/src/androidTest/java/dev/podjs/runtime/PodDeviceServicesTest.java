package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

import static org.junit.Assert.*;

public class PodDeviceServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void secureStoreSurvivesRecreationAndDoesNotContainPlaintext() throws Exception {
        String value = "你好，PodJS 🔐";
        PodDeviceServices first = new PodDeviceServices(context());
        first.execute("secure.set", new JSONObject().put("key", "unicode").put("value", value));
        first.close();
        File store = new File(new File(context().getFilesDir(), "podjs"), "secure.store");
        assertTrue(store.isFile());
        byte[] bytes = java.nio.file.Files.readAllBytes(store.toPath());
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains(value));
        PodDeviceServices second = new PodDeviceServices(context());
        JSONObject result = second.execute("secure.get", new JSONObject().put("key", "unicode"));
        assertTrue(result.getBoolean("exists"));
        assertEquals(value, result.getString("value"));
        second.execute("secure.delete", new JSONObject().put("key", "unicode"));
        second.close();
    }

    @Test public void fileChunksAndTraversalAreBounded() throws Exception {
        PodDeviceServices services = new PodDeviceServices(context());
        String path = "test/chunk.bin";
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        services.execute("file.write", new JSONObject().put("path", path).put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP)));
        JSONObject read = services.execute("file.read", new JSONObject().put("path", path).put("maxBytes", 3));
        assertEquals("hel", new String(Base64.decode(read.getString("dataBase64"), Base64.DEFAULT), StandardCharsets.UTF_8));
        try { services.execute("file.stat", new JSONObject().put("path", "../escape")); fail("traversal accepted"); }
        catch (SecurityException expected) { }
        services.execute("file.delete", new JSONObject().put("path", path));
        services.close();
    }
    @Test public void pairingCryptoAuthenticatesConfigAndRejectsTampering() throws Exception {
        PodDeviceServices services = new PodDeviceServices(context());
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte)i;
        String key64 = Base64.encodeToString(key, Base64.NO_WRAP);
        String request = "POST\n/setTtsConfig\nnonce\nbody-digest";
        String data64 = Base64.encodeToString(request.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject proof = services.execute("crypto.hmacSha256", new JSONObject().put("keyBase64", key64).put("dataBase64", data64));
        javax.crypto.Mac oracle = javax.crypto.Mac.getInstance("HmacSHA256");
        oracle.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        assertArrayEquals(oracle.doFinal(request.getBytes(StandardCharsets.UTF_8)), Base64.decode(proof.getString("digestBase64"), Base64.DEFAULT));

        String plaintext = "{\"engine\":\"minimax\",\"apiKey\":\"private-value\"}";
        String aad64 = Base64.encodeToString("POST\n/setTtsConfig".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject encrypted = services.execute("crypto.aesGcmEncrypt", new JSONObject().put("keyBase64", key64).put("aadBase64", aad64).put("dataBase64", Base64.encodeToString(plaintext.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)));
        assertEquals(12, Base64.decode(encrypted.getString("nonceBase64"), Base64.DEFAULT).length);
        assertFalse(encrypted.toString().contains("private-value"));
        JSONObject input = new JSONObject().put("keyBase64", key64).put("aadBase64", aad64).put("nonceBase64", encrypted.getString("nonceBase64")).put("ciphertextBase64", encrypted.getString("ciphertextBase64"));
        JSONObject opened = services.execute("crypto.aesGcmDecrypt", input);
        assertEquals(plaintext, new String(Base64.decode(opened.getString("dataBase64"), Base64.DEFAULT), StandardCharsets.UTF_8));
        input.put("aadBase64", Base64.encodeToString("GET\n/getTtsConfig".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        try { services.execute("crypto.aesGcmDecrypt", input); fail("tampered AAD accepted"); }
        catch (javax.crypto.AEADBadTagException expected) { }
        services.close();
    }
    @Test public void textChunksPreserveJavaUtf8ReplacementAndByteOffsets() throws Exception {
        PodDeviceServices services = new PodDeviceServices(context());
        String path = "test/text-chunk.bin";
        byte[] bytes = new byte[] {65, (byte)0xe4, (byte)0xb8, (byte)0xad, (byte)0xed, (byte)0xa0, (byte)0x80, (byte)0xf0, (byte)0x9f, (byte)0x98, (byte)0x80, (byte)0xe2, (byte)0x82, 66};
        try {
            services.execute("file.write", new JSONObject().put("path", path).put("dataBase64", Base64.encodeToString(bytes, Base64.NO_WRAP)));
            for (int offset = 0; offset <= bytes.length; offset++) {
                for (int max = 1; max <= bytes.length; max++) {
                    JSONObject read = services.execute("file.read", new JSONObject().put("path",path).put("offset",offset).put("maxBytes",max).put("encoding","utf8"));
                    int count = Math.min(max, bytes.length - offset);
                    assertEquals(new String(bytes,offset,count,StandardCharsets.UTF_8),read.getString("text"));
                    assertEquals(count,read.getInt("bytesRead"));
                    assertEquals(offset,read.getInt("offset"));
                    assertEquals(bytes.length,read.getInt("size"));
                    assertEquals(offset+count>=bytes.length,read.getBoolean("eof"));
                    assertFalse(read.has("dataBase64"));
                }
            }
            try { services.execute("file.read",new JSONObject().put("path",path).put("encoding","unknown"));fail("unsupported encoding accepted"); }
            catch (IllegalArgumentException expected) { assertEquals("invalid_encoding",expected.getMessage()); }
        } finally { services.execute("file.delete",new JSONObject().put("path",path));services.close(); }
    }

    @Test public void cloudEcdhIdentitySurvivesInstancesAndUnwraps() throws Exception {
        String id="instrumentation-ecdh-"+java.util.UUID.randomUUID();
        JSONObject args=new JSONObject().put("keyId",id);
        String publicSpki;
        try(PodDeviceServices first=new PodDeviceServices(context())) {
            publicSpki=first.execute("crypto.ecdhPublicKey",args).getString("publicKeySpki");
        }
        java.security.KeyPair peer=PodSoftwareEcdh.generate();
        java.security.PublicKey recipient=java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.decode(publicSpki,Base64.DEFAULT)));
        javax.crypto.KeyAgreement agreement=javax.crypto.KeyAgreement.getInstance("ECDH");
        agreement.init(peer.getPrivate());agreement.doPhase(recipient,true);
        byte[] salt=new byte[16],nonce=new byte[12],info="cloud-context".getBytes(StandardCharsets.UTF_8),aad="device-id".getBytes(StandardCharsets.UTF_8),account=new byte[32];
        new java.security.SecureRandom().nextBytes(account);
        byte[] cipher=PodCloudCrypto.aes(true,PodCloudCrypto.hkdf(agreement.generateSecret(),salt,info),nonce,account,aad);
        try(PodDeviceServices second=new PodDeviceServices(context())) {
            assertEquals(publicSpki,second.execute("crypto.ecdhPublicKey",args).getString("publicKeySpki"));
            JSONObject envelope=new JSONObject().put("keyId",id).put("publicKeySpki",b64(peer.getPublic().getEncoded())).put("saltBase64",b64(salt)).put("infoBase64",b64(info)).put("nonceBase64",b64(nonce)).put("ciphertextBase64",b64(cipher)).put("aadBase64",b64(aad));
            assertArrayEquals(account,Base64.decode(second.execute("crypto.ecdhUnwrap",envelope).getString("dataBase64"),Base64.DEFAULT));
            try { second.execute("crypto.ecdhUnwrap",new JSONObject(envelope.toString()).put("keyId",id+"missing"));fail("missing identity regenerated"); }
            catch(IllegalStateException expected) { assertEquals("device_private_key_missing",expected.getMessage()); }
            String alias="podjs.ecdh.v1."+b64(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
            java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
            assertEquals(android.os.Build.VERSION.SDK_INT>=31,store.containsAlias(alias));
            assertEquals(android.os.Build.VERSION.SDK_INT<31,store.containsAlias(alias+".software"));
            for(String operation:new String[]{"secure.get","secure.set","secure.delete","secure.update"}) {
                String privateField="__podjs_native_ecdh_v1."+alias;
                JSONObject request=new JSONObject().put("key",privateField).put("value","forbidden").put("values",new JSONObject().put(privateField,"forbidden"));
                try { second.execute(operation,request);fail("native key exposed through "+operation); }
                catch(SecurityException expected) { assertEquals("reserved_secure_key",expected.getMessage()); }
            }
        }
    }
    private static String b64(byte[] bytes) { return Base64.encodeToString(bytes,Base64.NO_WRAP); }

}
