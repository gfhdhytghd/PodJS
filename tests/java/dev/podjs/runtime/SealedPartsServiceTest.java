package dev.podjs.runtime;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import javax.crypto.spec.SecretKeySpec;
/** Actual host dispatcher/files/AEAD with public synthetic inputs and a JVM Base64 shim. */
public final class SealedPartsServiceTest {
 static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static long temporaryCount(Path root)throws Exception{try(var files=Files.list(root.resolve("cloud-crypto"))){return files.count();}}
 public static void main(String[] ignored)throws Exception{
  Path directory=Files.createTempDirectory("sealed-parts-service-"),root=directory.resolve("files");Files.createDirectories(root);byte[] key=new byte[32],nonce=new byte[12];
  try(PodDeviceServices host=new PodDeviceServices(root.toFile(),()->new SecretKeySpec(key,"AES"))){
   JSONArray parts=new JSONArray();var expected=new java.io.ByteArrayOutputStream();
   for(int i=0;i<33;i++){
    byte[] body=new byte[i==32?17:262144];new Random(i).nextBytes(body);byte[] aad=("public-fixture/part/"+i).getBytes(StandardCharsets.UTF_8);nonce[0]=(byte)i;
    byte[] sealed=PodCloudCrypto.aes(true,key,nonce,body,aad),all=new byte[12+sealed.length];System.arraycopy(nonce,0,all,0,12);System.arraycopy(sealed,0,all,12,sealed.length);Files.write(root.resolve("part-"+i),all);expected.write(body);
    parts.put(new JSONObject().put("path","part-"+i).put("aadBase64",b64(aad)).put("bytes",body.length));
   }
   JSONObject args=new JSONObject().put("keyBase64",b64(key)).put("parts",parts).put("length",expected.size());
   JSONObject result=host.execute("crypto.openSealedParts",args);check(result.getInt("size")==expected.size());check(Arrays.equals(expected.toByteArray(),Files.readAllBytes(root.resolve(result.getString("path")))));host.execute("file.delete",new JSONObject().put("path",result.getString("path")));check(temporaryCount(root)==0);
   byte[] before=Files.readAllBytes(root.resolve("part-17"));byte[] bad=before.clone();bad[20]^=1;Files.write(root.resolve("part-17"),bad);
   try{host.execute("crypto.openSealedParts",args);throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("sealed_part_invalid_17"));}check(temporaryCount(root)==0);Files.write(root.resolve("part-17"),before);
   args.put("deadlineMs",1);try{host.execute("crypto.openSealedParts",args);throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("cloud_sync_deadline_exceeded"));}check(temporaryCount(root)==0);
   args.remove("deadlineMs");parts.getJSONObject(0).put("path","../outside");try{host.execute("crypto.openSealedParts",args);throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("sealed_part_invalid_0"));}check(temporaryCount(root)==0);
   System.out.println("SEALED_PARTS_SERVICE_PASS 8MiB+17bytes/33parts; exact output, indexed tamper, deadline, traversal and temporary cleanup");
  }finally{try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
}
