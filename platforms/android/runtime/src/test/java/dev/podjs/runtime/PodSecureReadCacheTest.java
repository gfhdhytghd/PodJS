package dev.podjs.runtime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class PodSecureReadCacheTest {
    private static byte[] encrypted(String json, byte nonceByte) throws Exception {
        byte[] nonce=new byte[12];nonce[0]=nonceByte;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[32],"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD("PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8));
        byte[] body=cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)),all=new byte[12+body.length];
        System.arraycopy(nonce,0,all,0,12);System.arraycopy(body,0,all,12,body.length);return all;
    }
    @Test public void usesProductionAuthenticatedDecoderAndNeverReturnsAnOldAccountAfterReplacement() throws Exception {
        SecureReadCache cache=new SecureReadCache();int[] decodes={0};
        SecureReadCache.Decoder decoder=bytes->{decodes[0]++;return PodDeviceServices.decodeSecureBytes(bytes,new SecretKeySpec(new byte[32],"AES")).toString();};
        byte[] before=encrypted("{\"account\":\"old\"}",(byte)1),after=encrypted("{\"account\":\"new\"}",(byte)2);
        for(int i=0;i<100;i++)assertEquals("old",new JSONObject(cache.read(before,decoder)).getString("account"));
        assertEquals(1,decodes[0]);
        // A caller mutation / failed later write cannot change the cached map.
        new JSONObject(cache.read(before,decoder)).put("account","uncommitted");
        assertEquals("old",new JSONObject(cache.read(before,decoder)).getString("account"));
        assertEquals("new",new JSONObject(cache.read(after,decoder)).getString("account"));assertEquals(2,decodes[0]);
        byte[] corrupt=after.clone();corrupt[corrupt.length-1]^=1;
        try{cache.read(corrupt,decoder);fail("bad tag accepted");}catch(javax.crypto.AEADBadTagException expected){}
        assertEquals("new",new JSONObject(cache.read(after,decoder)).getString("account"));assertEquals(4,decodes[0]);
        cache.clear();cache.read(after,decoder);assertEquals(5,decodes[0]);
    }
}
