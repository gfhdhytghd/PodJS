package dev.podjs.runtime;

import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;

/** Native-only Android 11 ECDH identity. Encodings belong exclusively in encrypted secure.store. */
final class PodSoftwareEcdh {
    private PodSoftwareEcdh() {}
    static KeyPair generate() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    static String encode(KeyPair pair) throws Exception {
        validate(pair);
        byte[] privateBytes=pair.getPrivate().getEncoded();
        try {
            return "1."+Base64.getEncoder().encodeToString(privateBytes)+"."+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        } finally { Arrays.fill(privateBytes,(byte)0); }
    }
    static KeyPair decode(String encoded) throws Exception {
        if(encoded.length()>2048) throw new GeneralSecurityException("device_private_key_corrupt");
        String[] parts=encoded.split("\\.",-1);
        if(parts.length!=3 || !"1".equals(parts[0])) throw new GeneralSecurityException("device_private_key_corrupt");
        byte[] privateBytes=Base64.getDecoder().decode(parts[1]);
        try {
            KeyFactory factory=KeyFactory.getInstance("EC");
            KeyPair pair=new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[2]))),factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
            validate(pair); return pair;
        } finally { Arrays.fill(privateBytes,(byte)0); }
    }
    private static boolean same(ECParameterSpec a,ECParameterSpec b) {
        return a.getCurve().equals(b.getCurve())&&a.getGenerator().equals(b.getGenerator())&&a.getOrder().equals(b.getOrder())&&a.getCofactor()==b.getCofactor();
    }
    static void validate(KeyPair pair) throws Exception {
        if(!(pair.getPublic() instanceof ECPublicKey)||!(pair.getPrivate() instanceof ECPrivateKey)) throw new GeneralSecurityException("p256_required");
        AlgorithmParameters params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=params.getParameterSpec(ECParameterSpec.class);
        ECPrivateKey privateKey=(ECPrivateKey)pair.getPrivate();
        if(!same(((ECPublicKey)pair.getPublic()).getParams(),expected)||!same(privateKey.getParams(),expected)||privateKey.getS().signum()<=0||privateKey.getS().compareTo(expected.getOrder())>=0) throw new GeneralSecurityException("p256_required");
        // Detect mismatched PKCS8/SPKI records before advertising a public key.
        byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(pair.getPrivate());signer.update(challenge);
        byte[] signature=signer.sign();signer.initVerify(pair.getPublic());signer.update(challenge);
        if(!signer.verify(signature)) throw new GeneralSecurityException("device_private_key_mismatch");
    }
}
