package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JVM-testable wire crypto shared byte-for-byte by Android and PodJS hosts. */
public final class BleSetupCrypto {
    public static byte[] crypt(byte[] secret,String sessionId,int mode,String direction,int sequence,byte[] nonce,byte[] bytes) throws Exception {
        if(secret.length!=32||nonce.length!=12||sequence<0||sequence>15||!(direction.equals("phone")||direction.equals("watch")))throw new IllegalArgumentException("Invalid setup envelope");
        final String protocol="watchrss-ble-setup-v1";
        byte[] salt=MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
        Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(salt,"HmacSHA256"));byte[] prk=hmac.doFinal(secret);
        hmac.init(new SecretKeySpec(prk,"HmacSHA256"));hmac.update((protocol+":"+sessionId+":"+direction).getBytes(StandardCharsets.UTF_8));byte[] key=hmac.doFinal(new byte[]{1});
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD((protocol+":"+sessionId+":"+direction+":"+sequence).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(bytes);}finally{Arrays.fill(key,(byte)0);Arrays.fill(prk,(byte)0);}
    }
}
