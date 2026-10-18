package dev.podjs.runtime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class PodCloudReleaseTest {
 private static final String SOURCE="globalThis.backgroundHandler=()=> 'success';";
 private PodBackgroundPackage bundle(String... grants)throws Exception{
  String hash=PodBackgroundScheduler.sha256(SOURCE);
  JSONObject artifact=new JSONObject().put("file","background/"+hash+".js").put("bytes",SOURCE.getBytes(StandardCharsets.UTF_8).length).put("sha256",hash);
  JSONObject manifest=new JSONObject().put("schema",1).put("backgroundServices",new JSONArray(grants)).put("background",new JSONObject().put("watchrss-cloud-sync",artifact));
  return new PodBackgroundPackage("cloud.release",manifest,path->new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)));
 }
 @Test public void releaseAndDebugUseExplicitProductionGrant()throws Exception{
  PodBackgroundPackage p=bundle("cloud.host","kv.get");assertEquals("cloud.host",p.cloudHostMethod(false));assertEquals("cloud.host",p.cloudHostMethod(true));assertEquals(SOURCE,p.cloudSource(false));
 }
 @Test public void legacyDebugGrantCannotAuthorizeRelease()throws Exception{
  PodBackgroundPackage p=bundle("debug.cloudHost","kv.get");assertEquals("debug.cloudHost",p.cloudHostMethod(true));assertEquals(SOURCE,p.cloudSource(true));
  assertThrows(SecurityException.class,()->p.cloudSource(false));
 }
 @Test public void missingDuplicateAndUnknownGrantsRemainDenied()throws Exception{
  assertThrows(SecurityException.class,()->bundle("cloud.host").cloudSource(false));
  assertThrows(SecurityException.class,()->bundle("kv.get").cloudSource(false));
  assertThrows(SecurityException.class,()->bundle("cloud.host","cloud.host"));
  assertThrows(SecurityException.class,()->bundle("cloud.host","ui.draw"));
  assertFalse(PodCloudWorker.METHODS.contains("ui.draw"));assertFalse(PodCloudWorker.METHODS.contains("debug.exportCredentials"));
 }
 @Test public void declaredCloudStillRequiresExactInstalledSourceHash()throws Exception{
  String hash=PodBackgroundScheduler.sha256(SOURCE);
  JSONObject manifest=new JSONObject().put("schema",1).put("backgroundServices",new JSONArray(new String[]{"cloud.host","kv.get"})).put("background",new JSONObject().put("watchrss-cloud-sync",new JSONObject().put("file","background/"+hash+".js").put("bytes",SOURCE.length()).put("sha256",hash)));
  PodBackgroundPackage p=new PodBackgroundPackage("cloud.release",manifest,path->new ByteArrayInputStream(SOURCE.replace("success","failure").getBytes(StandardCharsets.UTF_8)));
  assertThrows(SecurityException.class,()->p.cloudSource(false));
 }
}
