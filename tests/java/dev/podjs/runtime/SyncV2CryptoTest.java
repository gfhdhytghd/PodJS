package dev.podjs.runtime;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.io.*;
public final class SyncV2CryptoTest {
 static byte[] hex(String s){return java.util.HexFormat.of().parseHex(s);}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static byte[] lp(byte[]... fields)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);for(byte[] x:fields){d.writeInt(x.length);d.write(x);}return b.toByteArray();}
 static void reject(byte[] b,int n)throws Exception{boolean bad=false;try{PodSyncV2Crypto.gunzip(b,n);}catch(Exception e){bad=true;}check(bad);}
 public static void main(String[] args)throws Exception{
  int count=0;
  for(String line:Files.readAllLines(Path.of(args[0]))){String[] f=line.split("\t",-1);
   if(f[0].equals("signature")){byte[] message=lp("wrss.sync.v2/commit-sign".getBytes("UTF-8"),f[2].getBytes("UTF-8")),sig=Base64.getUrlDecoder().decode(f[3]);check(PodSyncV2Crypto.verify(hex(f[1]),message,sig));sig[0]^=1;check(!PodSyncV2Crypto.verify(hex(f[1]),message,sig));count+=2;}
   else {byte[] aad=hex(f[5]),sealed=hex(f[6]),plain=hex(f[4]),key=PodCloudCrypto.hkdf(hex(f[1]),hex(f[2]),lp("wrss.sync.v2/aead".getBytes("UTF-8"),MessageDigest.getInstance("SHA-256").digest(aad)));byte[] expected=Arrays.copyOfRange(sealed,8+aad.length,sealed.length);byte[] encoded=PodCloudCrypto.aes(false,key,hex(f[3]),expected,aad);check(Arrays.equals(expected,PodSyncV2Crypto.encrypt(key,hex(f[3]),encoded,aad)));if(!Arrays.equals(encoded,plain))check(Arrays.equals(plain,PodSyncV2Crypto.gunzip(encoded,plain.length)));count++;}
  }
  KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair pair=generator.generateKeyPair();byte[] pub=PodSyncV2Crypto.publicBytes(pair.getPublic()),input={1,2,3},sig=PodSyncV2Crypto.sign(pair.getPrivate(),input);check(PodSyncV2Crypto.verify(pub,input,sig));check(Arrays.equals(sig,PodSyncV2Crypto.fromDer(PodSyncV2Crypto.toDer(sig))));count+=2;
  check(!PodSyncV2Crypto.verify(pub,input,new byte[64]));check(!PodSyncV2Crypto.verify(pub,input,new byte[65]));pub[0]=3;check(!PodSyncV2Crypto.verify(pub,input,sig));count+=3;
  byte[] plain=new byte[4096],gzip=PodCloudCrypto.gzip(plain);check(Arrays.equals(plain,PodSyncV2Crypto.gunzip(gzip,4096)));reject(Arrays.copyOf(gzip,gzip.length+1),4096);byte[] twice=new byte[gzip.length*2];System.arraycopy(gzip,0,twice,0,gzip.length);System.arraycopy(gzip,0,twice,gzip.length,gzip.length);reject(twice,4096);reject(gzip,4095);gzip[gzip.length-8]^=1;reject(gzip,4096);count+=5;
  System.out.println("syncv2 JVM checks="+count);
 }
}
