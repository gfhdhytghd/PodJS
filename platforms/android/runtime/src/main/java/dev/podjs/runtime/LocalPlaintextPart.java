package dev.podjs.runtime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
/** Plaintext with a 28-byte checksum header. This is corruption detection,
 * not secret storage or a claim of authentication against the file's owner. */
final class LocalPlaintextPart {
 static boolean plain(byte[] bytes){return bytes.length>=28&&bytes[0]=='P'&&bytes[1]=='L'&&bytes[2]=='N'&&bytes[3]=='4';}
 static byte[] seal(byte[] scope,byte[] bytes)throws Exception {
  MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(scope);hash.update((byte)0);byte[] digest=hash.digest(bytes),out=new byte[28+bytes.length];out[0]='P';out[1]='L';out[2]='N';out[3]='4';System.arraycopy(digest,0,out,4,24);System.arraycopy(bytes,0,out,28,bytes.length);return out;
 }
 static byte[] open(byte[] scope,byte[] bytes)throws Exception {
  if(!plain(bytes))throw new IOException("invalid_plain_part");byte[] value=Arrays.copyOfRange(bytes,28,bytes.length),expected=seal(scope,value);
  if(!MessageDigest.isEqual(Arrays.copyOfRange(expected,4,28),Arrays.copyOfRange(bytes,4,28)))throw new IOException("invalid_plain_part");return value;
 }
 static byte[] read(File file,byte[] scope,int length,byte[] legacyKey,boolean plaintext)throws Exception {
  if(!file.isFile()||file.length()!=length+28)throw new IOException("invalid_part");byte[] stored=Files.readAllBytes(file.toPath());
  if(!plaintext)return PodCloudCrypto.openSealedPart(legacyKey,scope,stored,length);
  if(plain(stored))return open(scope,stored);
  byte[] value=PodCloudCrypto.openSealedPart(legacyKey,scope,stored,length);
  // Upgrade only authenticated legacy bytes. Atomic replace keeps old receipts valid.
  PodDeviceServices.atomicWrite(file,seal(scope,value));return value;
 }
}
