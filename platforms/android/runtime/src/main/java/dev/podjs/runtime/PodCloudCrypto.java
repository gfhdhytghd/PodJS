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
    public static byte[] openSealedPart(byte[] key,byte[] aad,byte[] sealed,int length)throws Exception{
        if(length<0||length>256*1024||sealed.length!=length+28)throw new IllegalArgumentException("invalid_sealed_part");
        return aes(false,key,Arrays.copyOfRange(sealed,0,12),Arrays.copyOfRange(sealed,12,sealed.length),aad);
    }
    public static byte[] unwrap(PrivateKey privateKey, byte[] publicSpki, byte[] salt, byte[] info, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        PublicKey peer=p256Peer(publicSpki);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey); agreement.doPhase(peer,true);
        byte[] secret = agreement.generateSecret(), key = null;
        try { key = hkdf(secret,salt,info); return aes(false,key,nonce,ciphertext,aad); }
        finally { Arrays.fill(secret,(byte)0); if(key != null) Arrays.fill(key,(byte)0); }
    }
    static PublicKey p256Peer(byte[] publicSpki)throws Exception {
        ECPublicKey peer = (ECPublicKey)KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicSpki));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class), actual = peer.getParams();
        if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator()) || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor())
            throw new IllegalArgumentException("p256_required");
        return peer;
    }
    /** Prove possession by MACing an authenticated challenge with its unwrapped
     * one-time secret. Neither secret nor raw ECDH output leaves this method. */
    public static byte[] possessionProof(KeyPair pair,byte[] recipientDigest,byte[] publicSpki,byte[] salt,byte[] info,byte[] nonce,byte[] ciphertext,byte[] aad,byte[] data) throws Exception {
        if(recipientDigest.length!=32||salt.length!=32||nonce.length!=12||ciphertext.length!=48||info.length>4096||aad.length>4096||data.length>4096)
            throw new IllegalArgumentException("invalid_possession_challenge");
        if(!MessageDigest.isEqual(recipientDigest,MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded())))throw new GeneralSecurityException("device_public_key_mismatch");
        byte[] secret=unwrap(pair.getPrivate(),publicSpki,salt,info,nonce,ciphertext,aad);
        try {if(secret.length!=32)throw new GeneralSecurityException("invalid_possession_secret");Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(data);}
        finally {Arrays.fill(secret,(byte)0);}
    }
    /** P1363 fixed-width r||s input, converted to minimal positive DER integers. */
    public static boolean verifyP256(byte[] spki, byte[] input, byte[] signature) throws Exception {
        if(signature.length != 64 || input.length > 256*1024) return false;
        ECPublicKey key=(ECPublicKey)KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class), actual=key.getParams();
        if(!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator()) || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor()!=actual.getCofactor()) return false;
        byte[] r=new java.math.BigInteger(1,Arrays.copyOfRange(signature,0,32)).toByteArray();
        byte[] s=new java.math.BigInteger(1,Arrays.copyOfRange(signature,32,64)).toByteArray();
        ByteArrayOutputStream der=new ByteArrayOutputStream();der.write(48);der.write(4+r.length+s.length);der.write(2);der.write(r.length);der.write(r);der.write(2);der.write(s.length);der.write(s);
        Signature verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update(input);
        try{return verifier.verify(der.toByteArray());}catch(SignatureException invalid){return false;}
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
