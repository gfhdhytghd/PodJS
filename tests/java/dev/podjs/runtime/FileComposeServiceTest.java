package dev.podjs.runtime;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
public final class FileComposeServiceTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static String hash(byte[] b)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 public static void main(String[] ignored)throws Exception{
  Path dir=Files.createTempDirectory("compose-service-");
  try(PodDeviceServices host=new PodDeviceServices(dir.toFile(),()->new SecretKeySpec(new byte[32],"AES"))){
   Files.createDirectories(dir.resolve("private"));JSONArray parts=new JSONArray();var bytes=new java.io.ByteArrayOutputStream();
   for(int i=0;i<4;i++){byte[] b=new byte[i==3?17:262144];new Random(i).nextBytes(b);Files.write(dir.resolve("private/part-"+i),b);bytes.write(b);parts.put(new JSONObject().put("path","private/part-"+i).put("size",b.length).put("sha256",hash(b)));}
   byte[] body=bytes.toByteArray();String digest=hash(body);JSONObject args=new JSONObject().put("parts",parts).put("target","private/final").put("expectedSize",body.length).put("expectedSha256",digest);
   JSONObject result=host.execute("file.compose",args);check(result.getString("sha256").equals(digest));check(Arrays.equals(body,Files.readAllBytes(dir.resolve("private/final"))));
   for(String mode:new String[]{"tamper","deadline","traversal","total","hash","overlap"}){
    JSONObject bad=new JSONObject(args.toString());
    switch(mode){case "tamper":bad.getJSONArray("parts").getJSONObject(1).put("sha256","0".repeat(64));break;case "deadline":bad.put("deadlineMs",1);break;case "traversal":bad.getJSONArray("parts").getJSONObject(0).put("path","../outside");break;case "total":bad.put("expectedSize",body.length+1);break;case "hash":bad.put("expectedSha256","0".repeat(64));break;case "overlap":bad.getJSONArray("parts").getJSONObject(0).put("path","private/final");break;}
    boolean failed=false;try{host.execute("file.compose",bad);}catch(Exception expected){failed=true;}check(failed);check(Arrays.equals(body,Files.readAllBytes(dir.resolve("private/final"))));try(var files=Files.list(dir.resolve("private"))){check(files.noneMatch(p->p.getFileName().toString().startsWith(".pod-write-")));}
   }
   host.execute("file.compose",new JSONObject().put("parts",new JSONArray()).put("target","private/empty").put("expectedSize",0).put("expectedSha256",hash(new byte[0])));check(Files.size(dir.resolve("private/empty"))==0);
   byte[] bounded=new byte[262144];new Random(7).nextBytes(bounded);
   JSONObject publish=new JSONObject().put("target","private/atomic").put("dataBase64",Base64.getEncoder().encodeToString(bounded)).put("expectedSize",bounded.length).put("expectedSha256",hash(bounded));
   check(host.execute("file.publishBytes",publish).getString("sha256").equals(hash(bounded)));
   check(host.execute("file.publishBytes",publish).getBoolean("reused"));
   check(Arrays.equals(bounded,Files.readAllBytes(dir.resolve("private/atomic"))));
   for(String mode:new String[]{"hash","size","deadline","path"}){JSONObject bad=new JSONObject(publish.toString());if(mode.equals("hash"))bad.put("expectedSha256","0".repeat(64));if(mode.equals("size"))bad.put("expectedSize",262145);if(mode.equals("deadline"))bad.put("deadlineMs",1);if(mode.equals("path"))bad.put("target","../outside");boolean failed=false;try{host.execute("file.publishBytes",bad);}catch(Exception expected){failed=true;}check(failed);check(Arrays.equals(bounded,Files.readAllBytes(dir.resolve("private/atomic"))));}
   String text="Reader 汉😀" + (char)0xd800;
   byte[] encoded="Reader 汉😀?".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   String textHash=hash(encoded),textPath="private/chapter-"+textHash+".txt";
   JSONObject utf8=new JSONObject().put("text",text).put("targetPattern","private/chapter-{sha256}.txt").put("syncDirectory",true);
   JSONObject saved=host.execute("file.publishUtf8",utf8);
   check(saved.getString("path").equals(textPath));check(saved.getString("sha256").equals(textHash));
   check(Arrays.equals(encoded,Files.readAllBytes(dir.resolve(textPath))));
   check(host.execute("file.publishUtf8",utf8).getBoolean("reused"));
   check(host.execute("file.syncDirectory",new JSONObject().put("path","private")).getBoolean("synced"));
   try{host.execute("file.syncDirectory",new JSONObject().put("path",textPath));throw new AssertionError();}
   catch(java.io.IOException expected){check(expected.getMessage().equals("not_directory"));}
   for(String pattern:new String[]{"../out-{sha256}","private/name","private/{sha256}/body","private/{sha256}-{sha256}"}){
    JSONObject bad=new JSONObject(utf8.toString()).put("targetPattern",pattern);
    boolean failed=false;try{host.execute("file.publishUtf8",bad);}catch(Exception expected){failed=true;}
    check(failed);check(Arrays.equals(encoded,Files.readAllBytes(dir.resolve(textPath))));
   }
   System.out.println("FILE_PUBLISH_UTF8_PASS deterministic Java UTF-8, durability, reuse and path bounds");
   System.out.println("FILE_PUBLISH_BYTES_PASS bounds/hash/deadline/path/reuse preserve exact old target");
   System.out.println("FILE_COMPOSE_SERVICE_PASS exact 786449 bytes; tamper/deadline/traversal/size/hash/overlap preserve old target; empty and cleanup");
  }finally{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
}
