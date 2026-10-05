package dev.podjs.runtime;

import java.io.*;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Provider-independent primitives, also exercised on the JVM without a device. */
public final class PodCloudCrypto {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private PodCloudCrypto() {}
    public static byte[] hkdf(byte[] input, byte[] salt, byte[] info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] extracted = mac.doFinal(input);
        try {
            mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
            mac.update(info); mac.update((byte)1); return mac.doFinal();
        } finally { Arrays.fill(extracted, (byte)0); }
    }
    public static byte[] aes(boolean encrypt, byte[] key, byte[] nonce, byte[] input, byte[] aad) throws Exception {
        if (key.length != 32 || nonce.length != 12 || input.length > MAX_BYTES || aad.length > 65536)
            throw new IllegalArgumentException("invalid_crypto_input");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad); return cipher.doFinal(input);
    }
    public static byte[] unwrap(PrivateKey privateKey, byte[] publicSpki, byte[] salt, byte[] info, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        ECPublicKey peer = (ECPublicKey)KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicSpki));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class), actual = peer.getParams();
        if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator()) || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor())
            throw new IllegalArgumentException("p256_required");
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey); agreement.doPhase(peer,true);
        byte[] secret = agreement.generateSecret(), key = null;
        try { key = hkdf(secret,salt,info); return aes(false,key,nonce,ciphertext,aad); }
        finally { Arrays.fill(secret,(byte)0); if(key != null) Arrays.fill(key,(byte)0); }
    }
    public static byte[] gzip(byte[] input) throws Exception {
        if(input.length > MAX_BYTES) throw new IllegalArgumentException("compression_input_too_large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try(GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(input); }
        return out.toByteArray();
    }
    public static byte[] gunzip(byte[] input, int maxBytes) throws Exception {
        if(input.length > MAX_BYTES || maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("invalid_compression_limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try(GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            byte[] buffer = new byte[8192]; int n;
            while((n=gzip.read(buffer)) != -1) { if(out.size() > maxBytes-n) throw new IOException("decompressed_data_too_large"); out.write(buffer,0,n); }
        }
        return out.toByteArray();
    }
}
