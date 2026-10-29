package dev.podjs.runtime;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
/** Synthetic deterministic cross-client transcript. No Android/device/network. */
public final class PossessionProofTest {
 static byte[] b(Map<String,String> v,String k){return Base64.getDecoder().decode(v.get(k));}
 static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;}
 static byte[] prove(KeyPair pair,Map<String,String> v)throws Exception{return PodCloudCrypto.possessionProof(pair,hex(v.get("recipientSpkiSha256")),b(v,"publicKeySpki"),b(v,"saltBase64"),b(v,"infoBase64"),b(v,"nonceBase64"),b(v,"ciphertextBase64"),b(v,"aadBase64"),b(v,"dataBase64"));}
 public static void main(String[] args)throws Exception {
  Map<String,String> values=new HashMap<>();for(String line:Files.readAllLines(Path.of(args[0]))){String[] f=line.split("\t",2);values.put(f[0],f[1]);}
  KeyFactory f=KeyFactory.getInstance("EC");KeyPair pair=new KeyPair(f.generatePublic(new X509EncodedKeySpec(b(values,"receiverPublicKeySpki"))),f.generatePrivate(new PKCS8EncodedKeySpec(b(values,"receiverPrivateKeyPkcs8"))));
  if(!MessageDigest.isEqual(prove(pair,values),b(values,"proofBase64")))throw new AssertionError("shared proof mismatch");
  for(String field:new String[]{"recipientSpkiSha256","publicKeySpki","saltBase64","nonceBase64","ciphertextBase64","aadBase64","infoBase64"}){
   Map<String,String> changed=new HashMap<>(values);
   if(field.equals("recipientSpkiSha256"))changed.put(field,"00".repeat(32));else{byte[] bytes=b(changed,field);bytes[bytes.length-1]^=1;changed.put(field,Base64.getEncoder().encodeToString(bytes));}
   try{prove(pair,changed);throw new AssertionError("accepted altered "+field);}catch(GeneralSecurityException|IllegalArgumentException expected){}
  }
  // Only the proof is returned; changing its bound message changes that proof.
  Map<String,String> changed=new HashMap<>(values);changed.put("dataBase64",Base64.getEncoder().encodeToString(new byte[]{1}));if(MessageDigest.isEqual(prove(pair,changed),b(values,"proofBase64")))throw new AssertionError("message not bound");
  System.out.println("PossessionProofTest PASS: shared P256/AEAD/HMAC vector, SPKI/contexts/ciphertext binding, proof-only result");
 }
}
