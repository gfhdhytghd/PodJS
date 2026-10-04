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

}
