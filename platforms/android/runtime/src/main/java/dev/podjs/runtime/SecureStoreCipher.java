package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local format v2: AndroidKeyStore wraps one random process-cached data key.
 * Every commit has a fresh software AES-GCM nonce. Both the version and wrapped
 * key are authenticated. Legacy nonce+ciphertext remains readable. No key export,
 * identity rotation, wire-format change, or plaintext persistence is involved. */
final class SecureStoreCipher {
    static final byte[] MAGIC="PodJS-secure-v2\0\0".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LEGACY_AAD="PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8);
    static final int WRAPPED_BYTES=60, HEADER_BYTES=MAGIC.length+WRAPPED_BYTES;
    static final class Material {
        final SecretKey key; final byte[] header;
        Material(SecretKey key,byte[] header){this.key=key;this.header=header;}
    }
    static final class Opened {
        final byte[] plain; final Material material;
        Opened(byte[] plain,Material material){this.plain=plain;this.material=material;}
    }
    static void keyOp(Key key,String op){if(key.getClass().getName().startsWith("android.security.keystore"))SecureOperationStats.PROCESS.op(op);}
    static byte[] seal(SecretKey key,byte[] plain,byte[] aad)throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");keyOp(key,"cipherInit");c.init(Cipher.ENCRYPT_MODE,key);
        keyOp(key,"cipherAad");c.updateAAD(aad);keyOp(key,"cipherFinal");byte[] body=c.doFinal(plain),iv=c.getIV();
        if(iv==null||iv.length!=12)throw new java.io.IOException("invalid_keystore_iv");
        byte[] result=new byte[12+body.length];System.arraycopy(iv,0,result,0,12);System.arraycopy(body,0,result,12,body.length);return result;
    }
    static byte[] open(SecretKey key,byte[] all,byte[] aad)throws Exception {
        if(all.length<28)throw new java.io.IOException("secure_store_corrupt");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");keyOp(key,"cipherInit");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(all,12)));
        keyOp(key,"cipherAad");c.updateAAD(aad);keyOp(key,"cipherFinal");return c.doFinal(Arrays.copyOfRange(all,12,all.length));
    }
    static Material create(SecretKey wrappingKey)throws Exception {
        byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);
        byte[] wrapped=seal(wrappingKey,raw,MAGIC);if(wrapped.length!=WRAPPED_BYTES)throw new java.io.IOException("secure_store_corrupt");
        byte[] header=Arrays.copyOf(MAGIC,HEADER_BYTES);System.arraycopy(wrapped,0,header,MAGIC.length,wrapped.length);
        return new Material(new SecretKeySpec(raw,"AES"),header);
    }
    static byte[] encode(Material material,byte[] plain)throws Exception {
        byte[] body=seal(material.key,plain,material.header),result=Arrays.copyOf(material.header,HEADER_BYTES+body.length);
        System.arraycopy(body,0,result,HEADER_BYTES,body.length);return result;
    }
    static Opened decode(SecretKey wrappingKey,byte[] all)throws Exception {
        if(all.length>SecureStoreLimits.MAX_FILE_BYTES)throw SecureStoreLimits.tooLarge();
        boolean v2=all.length>=MAGIC.length&&Arrays.equals(MAGIC,Arrays.copyOf(all,MAGIC.length));
        if(!v2){if(all.length>SecureStoreLimits.MAX_PLAIN_BYTES+28)throw SecureStoreLimits.tooLarge();return new Opened(open(wrappingKey,all,LEGACY_AAD),null);}
        if(all.length<HEADER_BYTES+28)throw new java.io.IOException("secure_store_corrupt");
        byte[] header=Arrays.copyOf(all,HEADER_BYTES),raw=open(wrappingKey,Arrays.copyOfRange(header,MAGIC.length,HEADER_BYTES),MAGIC);
        if(raw.length!=32)throw new java.io.IOException("secure_store_corrupt");
        Material material=new Material(new SecretKeySpec(raw,"AES"),header);
        return new Opened(open(material.key,Arrays.copyOfRange(all,HEADER_BYTES,all.length),header),material);
    }
}
