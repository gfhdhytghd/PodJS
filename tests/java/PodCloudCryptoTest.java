import dev.podjs.runtime.PodCloudCrypto;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.KeyAgreement;
public class PodCloudCryptoTest {
 static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static byte[] hex(String s){return HexFormat.of().parseHex(s);}
 static String b64(byte[] b){return Base64.getEncoder().withoutPadding().encodeToString(b);}
 static void require(boolean b){if(!b)throw new AssertionError();}
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static byte[] concat(byte[]... items)throws Exception{var out=new java.io.ByteArrayOutputStream();for(var b:items)out.write(b);return out.toByteArray();}
 public static void main(String[] args)throws Exception{
  byte[] ikm=new byte[22];Arrays.fill(ikm,(byte)11);
  require(Arrays.equals(PodCloudCrypto.hkdf(ikm,hex("000102030405060708090a0b0c"),hex("f0f1f2f3f4f5f6f7f8f9")),hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf")));
  byte[] key=new byte[32],salt=new byte[16],nonce=new byte[12];for(int i=0;i<key.length;i++)key[i]=(byte)i;
  byte[] input=text("原生 gzip 与快照兼容 fixture"),encoded=PodCloudCrypto.gzip(input);
  require(Arrays.equals(PodCloudCrypto.gunzip(encoded,input.length),input));
  try{PodCloudCrypto.gunzip(encoded,input.length-1);throw new AssertionError();}catch(java.io.IOException expected){}
  byte[] large=new byte[4*1024*1024];new Random(42).nextBytes(large);
  require(Arrays.equals(large,PodCloudCrypto.gunzip(PodCloudCrypto.gzip(large),large.length)));
  byte[] aad=text("test"),sealed=PodCloudCrypto.aes(true,key,nonce,large,aad);
  require(Arrays.equals(large,PodCloudCrypto.aes(false,key,nonce,sealed,aad)));
  try{PodCloudCrypto.aes(false,key,nonce,sealed,text("wrong"));throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){}
  KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));
  KeyPair recipient=generator.generateKeyPair(),sender=generator.generateKeyPair();
  KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(sender.getPrivate());agreement.doPhase(recipient.getPublic(),true);
  byte[] info=text("watchrss/device-envelope/v2/watch"),derived=PodCloudCrypto.hkdf(agreement.generateSecret(),salt,info);
  byte[] envelope=PodCloudCrypto.aes(true,derived,nonce,key,aad);
  require(Arrays.equals(key,PodCloudCrypto.unwrap(recipient.getPrivate(),sender.getPublic().getEncoded(),salt,info,nonce,envelope,aad)));
  try{PodCloudCrypto.unwrap(recipient.getPrivate(),sender.getPublic().getEncoded(),salt,text("wrong device"),nonce,envelope,aad);throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){}
  generator.initialize(new ECGenParameterSpec("secp384r1"));
  try{PodCloudCrypto.unwrap(recipient.getPrivate(),generator.generateKeyPair().getPublic().getEncoded(),salt,info,nonce,envelope,aad);throw new AssertionError();}catch(IllegalArgumentException expected){}
  String plainHash=hash(encoded);
  byte[] chunk=concat(text("WRSSCC2"),salt,nonce,PodCloudCrypto.aes(true,PodCloudCrypto.hkdf(key,salt,text("watchrss/cloud/chunk/v1")),nonce,encoded,text("watchrss-cloud-chunk-v1:"+plainHash)));
  String descriptor="{\"plaintextSha256\":\""+plainHash+"\",\"ciphertextSha256\":\""+hash(chunk)+"\",\"plaintextBytes\":"+encoded.length+",\"ciphertextBytes\":"+chunk.length+",\"saltBase64\":\""+b64(salt)+"\",\"nonceBase64\":\""+b64(nonce)+"\"}";
  String manifest="{\"format\":\"watchrss-cloud-snapshot\",\"schemaVersion\":2,\"snapshotId\":\"jvm-fixture\",\"sourceDeviceId\":\"phone\",\"deviceSequence\":7,\"keyVersion\":2,\"createdAtMillis\":123,\"parentHeads\":{},\"observedHeads\":{},\"objects\":[{\"name\":\"rss-state.json\",\"encoding\":\"gzip-chunks-v1\",\"originalBytes\":"+input.length+",\"encodedBytes\":"+encoded.length+",\"chunks\":["+descriptor+"]}],\"settings\":{},\"credentialEnvelopes\":{}}";
  byte[] encrypted=concat(text("WRSSCM2"),salt,nonce,PodCloudCrypto.aes(true,PodCloudCrypto.hkdf(key,salt,text("watchrss/cloud/manifest/v1")),nonce,text(manifest),text("watchrss-cloud-manifest-v2:jvm-fixture")));
  if(args.length>0)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),"{\"provenance\":\"JVM JCA native WatchCloudCrypto format; public fixture only\",\"key\":\""+b64(key)+"\",\"manifest\":\""+b64(encrypted)+"\",\"chunk\":\""+b64(chunk)+"\",\"plaintext\":\""+b64(input)+"\"}\n");
  System.out.println("PASS: RFC5869, AES-GCM/tamper, P-256 envelope/wrong context/curve, bounded gzip, 4 MiB input, native format fixture");
 }
}
