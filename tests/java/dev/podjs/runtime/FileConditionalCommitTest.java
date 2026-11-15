package dev.podjs.runtime;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
/** Actual service dispatcher; no device, AndroidKeyStore or production data. */
public final class FileConditionalCommitTest {
 static String hash(byte[] b)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static JSONObject args(String path,String target,byte[] b,Object wanted)throws Exception{return new JSONObject().put("path",path).put("target",target).put("expectedSize",b.length).put("expectedSha256",hash(b)).put("expectedTargetSha256",wanted);}
 public static void main(String[] ignored)throws Exception{
  Path dir=Files.createTempDirectory("conditional-commit-");byte[] incoming={1,2,3},local={9,8,7};
  try(PodDeviceServices host=new PodDeviceServices(dir.toFile(),()->new SecretKeySpec(new byte[32],"AES"));PodDeviceServices other=new PodDeviceServices(dir.toFile(),()->new SecretKeySpec(new byte[32],"AES"))){
   Files.write(dir.resolve("part"),incoming);
   // Competing writer arrives at the final commit boundary, after caller's last read.
   CountDownLatch entered=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
   Future<Boolean> result=FileCommitGuard.locked(dir.toFile(),dir.resolve("target").toFile(),()->{
    Future<Boolean> pending=pool.submit(()->{entered.countDown();try{host.execute("file.commit",args("part","target",incoming,JSONObject.NULL));return false;}catch(Exception e){return e.getMessage().contains("file_target_changed");}});
    check(entered.await(2,TimeUnit.SECONDS));try{pending.get(100,TimeUnit.MILLISECONDS);throw new AssertionError("commit escaped final lock");}catch(TimeoutException expected){}Files.write(dir.resolve("target"),local);return pending;
   });
   check(result.get(2,TimeUnit.SECONDS));check(Arrays.equals(Files.readAllBytes(dir.resolve("target")),local));check(Files.exists(dir.resolve("part")));
   host.execute("file.commit",args("part","target",incoming,hash(local)));check(Arrays.equals(Files.readAllBytes(dir.resolve("target")),incoming));
   Files.write(dir.resolve("part2"),local);
   try{other.execute("file.commit",args("part2","target",local,hash(local)));throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("file_target_changed"));}
   check(Arrays.equals(Files.readAllBytes(dir.resolve("target")),incoming));check(Files.exists(dir.resolve("part2")));
   other.execute("file.commit",args("part2","new",local,JSONObject.NULL));check(Arrays.equals(Files.readAllBytes(dir.resolve("new")),local));
   // A matching incoming file must not bypass a failed absent-target condition.
   Files.write(dir.resolve("same"),incoming);
   try{host.execute("file.commit",args("same","target",incoming,JSONObject.NULL));throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().equals("file_target_changed"));}
   check(Files.exists(dir.resolve("same")));check(host.execute("file.commit",args("same","target",incoming,hash(incoming))).getBoolean("reused"));check(!Files.exists(dir.resolve("same")));
   // No condition is a backwards-compatible ordinary commit.
   Files.write(dir.resolve("part3"),incoming);JSONObject old=args("part3","new",incoming,JSONObject.NULL);old.remove("expectedTargetSha256");host.execute("file.commit",old);
   pool.shutdownNow();System.out.println("FileConditionalCommitTest: 6 conditional cases + legacy commit passed");
  }finally{try(var walk=Files.walk(dir)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
