package dev.podjs.runtime;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.json.*;
import javax.crypto.spec.SecretKeySpec;

/** The production dispatcher with synthetic files, never AndroidKeyStore. */
public final class SealedGzipServiceTest {
 static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static JSONObject args(byte[] key,JSONArray parts,int length,int output){return new JSONObject().put("keyBase64",b64(key)).put("parts",parts).put("length",length).put("maxOutputBytes",output).put("outputPrefix","output/p").put("aadPrefix","public-output:").put("offset",0).put("deadlineMs",System.currentTimeMillis()+60000);}
 public static void main(String[] ignored)throws Exception {
  Path directory=Files.createTempDirectory("sealed-gzip-service-"),root=directory.resolve("files");Files.createDirectories(root);
  byte[] key=new byte[32],body=new byte[8*1024*1024+17];new Random(812).nextBytes(body);var packed=new java.io.ByteArrayOutputStream();try(var zip=new GZIPOutputStream(packed)){zip.write(body);}byte[] gzip=packed.toByteArray();JSONArray parts=new JSONArray();
  for(int i=0,at=0;at<gzip.length;i++,at+=262144){byte[] plain=Arrays.copyOfRange(gzip,at,Math.min(gzip.length,at+262144)),nonce=new byte[12];nonce[0]=(byte)i;byte[] aad=("source:"+i).getBytes(StandardCharsets.UTF_8),cipher=PodCloudCrypto.aes(true,key,nonce,plain,aad),sealed=new byte[12+cipher.length];System.arraycopy(nonce,0,sealed,0,12);System.arraycopy(cipher,0,sealed,12,cipher.length);Files.write(root.resolve("in-"+i),sealed);parts.put(new JSONObject().put("path","in-"+i).put("bytes",plain.length).put("aadBase64",b64(aad)));}
  JSONObject a=args(key,parts,gzip.length,body.length);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
  int offset=0;int receipts=0;
  // Reconstruct the actual native service at every output boundary. Persist
  // only the receipt offset; no live inflater/cursor survives reconstruction.
  while(offset<body.length){
   try(PodDeviceServices host=new PodDeviceServices(root.toFile(),()->new SecretKeySpec(key,"AES"))){
    a.put("offset",offset);JSONObject step=host.execute("crypto.gunzipSealedParts",a);int count=step.getInt("bytes");check(count==Math.min(262144,body.length-offset));
    byte[] sealed=Files.readAllBytes(root.resolve("output/p"+offset+".bin")),plain=PodCloudCrypto.openSealedPart(key,("public-output:"+offset).getBytes(StandardCharsets.UTF_8),sealed,count);check(Arrays.equals(plain,Arrays.copyOfRange(body,offset,offset+count)));offset+=count;receipts++;
    check(step.getBoolean("done")== (offset==body.length));if(step.getBoolean("done"))check(step.getString("sha256").equals(digest));
   }
  }
  try(PodDeviceServices host=new PodDeviceServices(root.toFile(),()->new SecretKeySpec(key,"AES"))){
   a.put("offset",body.length);JSONObject eof=host.execute("crypto.gunzipSealedParts",a);check(eof.getInt("bytes")==0&&eof.getBoolean("done")&&eof.getString("sha256").equals(digest));
   a.put("offset",0).put("deadlineMs",1);try{host.execute("crypto.gunzipSealedParts",a);throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("cloud_sync_deadline_exceeded"));}
   a.put("deadlineMs",System.currentTimeMillis()+60000).put("outputPrefix","../escape");try{host.execute("crypto.gunzipSealedParts",a);throw new AssertionError();}catch(SecurityException expected){}
   a.put("outputPrefix","output/p");byte[] before=Files.readAllBytes(root.resolve("in-1"));byte[] corrupt=before.clone();corrupt[20]^=1;Files.write(root.resolve("in-1"),corrupt);
   try{for(int at=0;at<body.length;at+=262144)host.execute("crypto.gunzipSealedParts",a.put("offset",at));throw new AssertionError();}catch(java.io.IOException expected){check(expected.getMessage().equals("sealed_part_invalid_1"));}Files.write(root.resolve("in-1"),before);
   // Valid AEAD over an invalid gzip trailer must never report done.
   int last=parts.length()-1;JSONObject descriptor=parts.getJSONObject(last);Path file=root.resolve("in-"+last);byte[] sealed=Files.readAllBytes(file),aad=Base64.getDecoder().decode(descriptor.getString("aadBase64")),plain=PodCloudCrypto.openSealedPart(key,aad,sealed,descriptor.getInt("bytes"));plain[plain.length-8]^=1;byte[] nonce=Arrays.copyOf(sealed,12),cipher=PodCloudCrypto.aes(true,key,nonce,plain,aad);System.arraycopy(cipher,0,sealed,12,cipher.length);Files.write(file,sealed);
   boolean failed=false;try{for(int at=0;at<body.length;at+=262144){JSONObject step=host.execute("crypto.gunzipSealedParts",a.put("offset",at));check(!step.getBoolean("done"));}}catch(java.io.IOException expected){failed=true;}check(failed);
  }
  // Streaming output can cross the old contiguous 64 MiB limit without ever
  // allocating that output in the service. Resume at its last 17-byte piece.
  var largePacked=new java.io.ByteArrayOutputStream();var largeHash=java.security.MessageDigest.getInstance("SHA-256");byte[] zeros=new byte[262144];
  try(var zip=new GZIPOutputStream(largePacked)){for(int n=0;n<260;n++){zip.write(zeros);largeHash.update(zeros);}zip.write(zeros,0,17);largeHash.update(zeros,0,17);}
  byte[] largePlain=largePacked.toByteArray(),largeNonce=new byte[12],largeAad="large-source".getBytes(StandardCharsets.UTF_8),largeCipher=PodCloudCrypto.aes(true,key,largeNonce,largePlain,largeAad),largeSealed=new byte[12+largeCipher.length];System.arraycopy(largeCipher,0,largeSealed,12,largeCipher.length);Files.write(root.resolve("large-input"),largeSealed);
  JSONArray largeParts=new JSONArray().put(new JSONObject().put("path","large-input").put("aadBase64",b64(largeAad)).put("bytes",largePlain.length));
  try(PodDeviceServices host=new PodDeviceServices(root.toFile(),()->new SecretKeySpec(key,"AES"))){
   JSONObject large=args(key,largeParts,largePlain.length,65*1024*1024+17).put("outputPrefix","large/p").put("offset",65*1024*1024);JSONObject last=host.execute("crypto.gunzipSealedParts",large);check(last.getInt("bytes")==17&&last.getBoolean("done"));check(last.getString("sha256").equals(java.util.HexFormat.of().formatHex(largeHash.digest())));
   Thread.currentThread().interrupt();try{host.execute("crypto.gunzipSealedParts",large.put("offset",0));throw new AssertionError();}catch(java.io.IOException expected){check(expected.getMessage().equals("cloud_sync_deadline_exceeded"));}finally{Thread.interrupted();}
  }
  System.out.println("SEALED_GZIP_SERVICE_PASS "+receipts+" restart boundaries; exact AEAD output/hash, lost EOF reply, deadline, traversal, input tamper and gzip CRC failure, 65MiB streamed replay and thread cancellation");
  try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
 }
}
