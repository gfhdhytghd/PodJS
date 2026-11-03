package dev.podjs.runtime;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.KeyAgreement;

/** Cache validated ECDH/HKDF material, not AEAD authentication results. */
final class ProcessEcdh {
    private static final Map<PrivateKey,State> keys=new IdentityHashMap<>();
    private static final class State {final Map<String,byte[]> derived=new HashMap<>();final FailureBackoff failure=new FailureBackoff();}
    static synchronized byte[] unwrap(PrivateKey privateKey,byte[] spki,byte[] salt,byte[] info,byte[] nonce,byte[] ciphertext,byte[] aad)throws Exception {
        if(spki.length>1024||salt.length>65536||info.length>65536)throw new IllegalArgumentException("invalid_crypto_input");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(byte[] part:new byte[][]{spki,salt,info}){hash.update(java.nio.ByteBuffer.allocate(4).putInt(part.length).array());hash.update(part);}
        String id=Base64.getEncoder().encodeToString(hash.digest());
        State state=keys.computeIfAbsent(privateKey,k->new State());state.failure.check();byte[] key=state.derived.get(id);
        if(key==null){
            if(state.derived.size()>=4096)throw new IllegalStateException("ecdh_cache_capacity");
            // Preserve the generic implementation's complete curve validation.
            PublicKey peer=PodCloudCrypto.p256Peer(spki);
            try{KeyAgreement agreement=KeyAgreement.getInstance("ECDH");SecureStoreCipher.keyOp(privateKey,"agreement");agreement.init(privateKey);agreement.doPhase(peer,true);key=PodCloudCrypto.hkdf(agreement.generateSecret(),salt,info);}
            catch(Exception error){state.failure.failed(error);throw error;}
        }
        // A cached derived key still checks every nonce, tag, ciphertext and AAD.
        state.derived.put(id,key);
        try{byte[] plain=PodCloudCrypto.aes(false,key,nonce,ciphertext,aad);state.failure.success();return plain;}
        catch(Exception error){state.failure.failed(error);throw error;}
    }
    static byte[] proof(KeyPair pair,byte[] recipient,byte[] spki,byte[] salt,byte[] info,byte[] nonce,byte[] ciphertext,byte[] aad,byte[] data)throws Exception {
        if(recipient.length!=32||salt.length!=32||nonce.length!=12||ciphertext.length!=48||info.length>4096||aad.length>4096||data.length>4096)throw new IllegalArgumentException("invalid_possession_challenge");
        if(!MessageDigest.isEqual(recipient,MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded())))throw new GeneralSecurityException("device_public_key_mismatch");
        byte[] secret=unwrap(pair.getPrivate(),spki,salt,info,nonce,ciphertext,aad);
        if(secret.length!=32)throw new GeneralSecurityException("invalid_possession_secret");
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(data);
    }
}
