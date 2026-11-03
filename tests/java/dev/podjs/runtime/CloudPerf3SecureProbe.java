package dev.podjs.runtime;

import java.io.File;
import org.json.JSONObject;

/** app_process fixture: real AndroidKeyStore; synthetic store, no user data. */
public final class CloudPerf3SecureProbe {
    public static void main(String[] args)throws Exception {
        android.os.Looper.prepareMainLooper();
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        File root=new File(args[0]);root.mkdirs();
        PodDeviceServices host=new PodDeviceServices(root,null);
        JSONObject values=new JSONObject().put("fixture.large","x".repeat(1024*1024)).put("fixture.small","same");
        host.execute("secure.update",new JSONObject().put("values",values));
        SecureOperationStats.PROCESS.emit(System.nanoTime(),true,System.out::println);
        for(int i=0;i<20;i++){
            host.execute("secure.update",new JSONObject().put("values",new JSONObject().put("fixture.absent",JSONObject.NULL)));
            host.execute("secure.update",new JSONObject().put("values",new JSONObject().put("fixture.small","same")));
            host.execute("secure.get",new JSONObject().put("key","fixture.small"));
            host.execute("secure.compareAndSet",new JSONObject().put("key","fixture.small").put("expected","same").put("value","same"));
        }
        SecureOperationStats.PROCESS.emit(System.nanoTime(),true,System.out::println);
        for(int i=0;i<20;i++)host.execute("secure.update",new JSONObject().put("values",new JSONObject().put("fixture.checkpoint",Integer.toString(i))));
        SecureOperationStats.PROCESS.emit(System.nanoTime(),true,System.out::println);
        if(args.length>1&&args[1].equals("ecdh")){
            JSONObject identity=new JSONObject().put("keyId","cloudperf3-synthetic-identity");
            String spki=host.execute("crypto.ecdhPublicKey",identity).getString("publicKeySpki");
            java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));java.security.KeyPair peer=generator.generateKeyPair();
            javax.crypto.KeyAgreement agreement=javax.crypto.KeyAgreement.getInstance("ECDH");agreement.init(peer.getPrivate());agreement.doPhase(java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(spki,0))),true);
            byte[] salt=new byte[32],info=new byte[]{1},nonce=new byte[12],aad=new byte[]{2},secret=new byte[32];java.util.Arrays.fill(secret,(byte)9);
            byte[] cipher=PodCloudCrypto.aes(true,PodCloudCrypto.hkdf(agreement.generateSecret(),salt,info),nonce,secret,aad);
            JSONObject envelope=new JSONObject(identity.toString()).put("publicKeySpki",android.util.Base64.encodeToString(peer.getPublic().getEncoded(),2)).put("saltBase64",android.util.Base64.encodeToString(salt,2)).put("infoBase64",android.util.Base64.encodeToString(info,2)).put("nonceBase64",android.util.Base64.encodeToString(nonce,2)).put("ciphertextBase64",android.util.Base64.encodeToString(cipher,2)).put("aadBase64",android.util.Base64.encodeToString(aad,2));
            host.execute("crypto.ecdhUnwrap",envelope);SecureOperationStats.PROCESS.emit(System.nanoTime(),true,System.out::println);
            for(int i=0;i<100;i++){host.execute("crypto.ecdhPublicKey",identity);JSONObject result=host.execute("crypto.ecdhUnwrap",envelope);if(!java.util.Arrays.equals(secret,android.util.Base64.decode(result.getString("dataBase64"),0)))throw new AssertionError("ECDH mismatch");}
            envelope.put("aadBase64","Aw==");for(int i=0;i<10;i++){boolean rejected=false;try{host.execute("crypto.ecdhUnwrap",envelope);}catch(Exception expected){rejected=true;}if(!rejected)throw new AssertionError("AAD tamper accepted");}
            SecureOperationStats.PROCESS.emit(System.nanoTime(),true,System.out::println);
        }
        host.close();System.out.println("CLOUDPERF3_REAL_KEYSTORE_PASS");System.exit(0);
    }
}
