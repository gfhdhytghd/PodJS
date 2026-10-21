package dev.podjs.runtime;
import java.security.*;
import java.security.spec.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.KeyAgreement;

public final class SoftwareEcdhTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void reject(Checked fn)throws Exception{try{fn.run();throw new AssertionError("accepted invalid identity");}catch(GeneralSecurityException|IllegalArgumentException expected){}}
    interface Checked{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        KeyPair original=PodSoftwareEcdh.generate(),sender=PodSoftwareEcdh.generate();
        String encoded=PodSoftwareEcdh.encode(original);
        byte[] storageKey=new byte[32],storageNonce=new byte[12],aad="secure-store-fixture".getBytes(StandardCharsets.UTF_8);
        new SecureRandom().nextBytes(storageKey);new SecureRandom().nextBytes(storageNonce);
        Path file=Files.createTempFile("pod-ecdh-store", ".bin");
        try{
            Files.write(file,PodCloudCrypto.aes(true,storageKey,storageNonce,encoded.getBytes(StandardCharsets.UTF_8),aad));
            check(!new String(Files.readAllBytes(file),StandardCharsets.UTF_8).contains(encoded));
            // New decode instance after reading encrypted bytes, no object identity reuse.
            KeyPair restored=PodSoftwareEcdh.decode(new String(PodCloudCrypto.aes(false,storageKey,storageNonce,Files.readAllBytes(file),aad),StandardCharsets.UTF_8));
            check(Arrays.equals(original.getPublic().getEncoded(),restored.getPublic().getEncoded()));
            byte[] accountKey=new byte[32],salt=new byte[16],nonce=new byte[12],info="account-envelope".getBytes(StandardCharsets.UTF_8);
            new SecureRandom().nextBytes(accountKey);
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(sender.getPrivate());agreement.doPhase(restored.getPublic(),true);
            byte[] ciphertext=PodCloudCrypto.aes(true,PodCloudCrypto.hkdf(agreement.generateSecret(),salt,info),nonce,accountKey,aad);
            check(Arrays.equals(accountKey,PodCloudCrypto.unwrap(restored.getPrivate(),sender.getPublic().getEncoded(),salt,info,nonce,ciphertext,aad)));
            byte[] wrong=storageKey.clone();wrong[0]^=1;
            reject(()->PodCloudCrypto.aes(false,wrong,storageNonce,Files.readAllBytes(file),aad));
            String mixed="1."+encoded.split("\\.")[1]+"."+PodSoftwareEcdh.encode(sender).split("\\.")[2];
            reject(()->PodSoftwareEcdh.decode(mixed));reject(()->PodSoftwareEcdh.decode("corrupt"));
            KeyPairGenerator p384=KeyPairGenerator.getInstance("EC");p384.initialize(new ECGenParameterSpec("secp384r1"));
            reject(()->PodSoftwareEcdh.encode(p384.generateKeyPair()));
        }finally{Files.delete(file);Arrays.fill(storageKey,(byte)0);}
        System.out.println("PASS software P-256 encrypted persistence, envelope unwrap, wrong store key, corruption, mismatched pair, wrong curve");
    }
}
