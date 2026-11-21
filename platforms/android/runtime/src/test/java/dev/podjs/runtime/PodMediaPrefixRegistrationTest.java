package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaPrefixRegistrationTest {
 @Test public void invalidSourceDiagnosticsDistinguishClosedValidationReasonsWithoutTheSource()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"registration-source-reasons"));
  String[][] rejected={{"","https://fixture.invalid/PRIVATE","key_empty"},{"x".repeat(2049),"https://fixture.invalid/PRIVATE","key_length"},{"fixture","podjs-media-cache://play/PRIVATE","scheme"},{"fixture","https:///PRIVATE","host"},{"fixture","https://PRIVATE@fixture.invalid/a","credentials"},{"fixture","https://fixture.invalid/"+"x".repeat(32768),"url_length"}};
  try{for(String[] row:rejected){try{cache.register(row[0],row[1],Collections.emptyMap());fail(row[2]);}catch(IllegalArgumentException error){assertEquals("invalid_media_cache_source_"+row[2],error.getMessage());assertEquals("invalid_source_"+row[2],PodMediaPrefixFailure.reason(error));assertEquals("invalid_argument",PodMediaPrefixFailure.code(error,false));assertFalse(error.getMessage().contains("PRIVATE"));}}}finally{cache.close();}
 }
 @Test public void issuedUrisSurviveOffWindowMetadataChurn()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"registration-lifetime"));
  try{String first=cache.register("first","https://fixture.invalid/first",Collections.emptyMap());
   for(int i=0;i<100;i++)cache.register("item"+i,"https://fixture.invalid/"+i,Collections.emptyMap());
   assertTrue(cache.owns(first));assertEquals("https://fixture.invalid/first",cache.target(first).remote);
   assertTrue(cache.memoryStats().getLong("metadataUtf16BytesUpperBound")<=PodMediaPrefixCache.MAX_METADATA_BYTES);
  }finally{cache.close();}
 }
 @Test public void byteBudgetRejectsNewCapabilitiesWithoutRevokingExistingOnes()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"registration-budget"));
  try{String first=cache.register("first","https://fixture.invalid/first",Collections.emptyMap());String large="x".repeat(30000);boolean full=false;
   for(int i=0;i<200;i++)try{cache.register("large"+i,"https://fixture.invalid/"+large+i,Collections.singletonMap("X-Test",large));}
    catch(IOException e){assertEquals("media_cache_metadata_budget",e.getMessage());full=true;break;}
   assertTrue(full);assertTrue(cache.owns(first));assertTrue(cache.memoryStats().getLong("metadataUtf16BytesUpperBound")<=PodMediaPrefixCache.MAX_METADATA_BYTES);
   assertTrue(cache.memoryStats().getInt("entries")<PodMediaPrefixCache.MAX_REGISTRATIONS);
   assertEquals(first,cache.register("first","https://fixture.invalid/first",Collections.emptyMap()));
  }finally{cache.close();}
 }
 @Test public void credentialsCannotEnterUntrustedMainCompanionOrMirror()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"registration-credentials"));
  try{
   for(String part:new String[]{"main","audio","mirror"})try{
    cache.register(part,part.equals("main")?"https://fixture.invalid/main":"https://www.douyin.com/main",java.util.Map.of("Cookie","offline-fixture"),false,
      part.equals("mirror")?java.util.List.of("https://fixture.invalid/mirror"):Collections.emptyList(),part.equals("audio")?"http://www.douyin.com/audio":"");fail(part);
   }catch(IOException expected){assertEquals("media_credentials_origin_rejected",expected.getMessage());}
  }finally{cache.close();}
 }
 @Test public void clearRestoresExplicitTrafficStop()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(c.getCacheDir(),"registration-recovery"));
  try{cache.stopTraffic();assertTrue(cache.trafficStopped());cache.execute("video.cache.clear",new org.json.JSONObject());assertFalse(cache.trafficStopped());}finally{cache.close();}
 }

 @Test public void newerStageFencesTheOlderTokenForPersistentAndEphemeralKeys()throws Exception {
  for(boolean persistent:new boolean[]{true,false}){
   try(PodMediaPrefixCache cache=stageCache()){
    JSONObject older=cache.execute("video.cache.stage",request("shared","older",persistent));
    JSONObject newer=cache.execute("video.cache.stage",request("shared","newer",persistent));
    assertEquals("media_request_cancelled",assertThrows(IOException.class,()->commit(cache,older)).getMessage());
    String uri=commit(cache,newer);
    assertEquals("https://fixture.invalid/newer",cache.target(uri).remote);
    assertEquals(1,cache.memoryStats().getInt("entries"));
   }
  }
 }

 @Test public void ordinaryPersistentRegistrationFencesAPendingStageForTheSameUri()throws Exception {
  try(PodMediaPrefixCache cache=stageCache()){
   JSONObject pending=cache.execute("video.cache.stage",request("shared","older",true));
   String uri=cache.execute("video.cache.register",request("shared","newer",true)).getString("uri");
   assertEquals(uri,pending.getString("uri"));
   assertEquals("media_request_cancelled",assertThrows(IOException.class,()->commit(cache,pending)).getMessage());
   assertEquals("https://fixture.invalid/newer",cache.target(uri).remote);
  }
 }

 @Test public void supersedingATokenReusesItsSlotInAFullStagePool()throws Exception {
  for(boolean persistent:new boolean[]{true,false}){
   try(PodMediaPrefixCache cache=stageCache()){
    JSONObject first=cache.execute("video.cache.stage",request("shared","older",persistent));
    for(int index=1;index<PodMediaPrefixCache.MAX_ENTRIES;index++)
     cache.execute("video.cache.stage",request("stage-"+index,"filler",persistent));
    JSONObject latest=cache.execute("video.cache.stage",request("shared","latest",persistent));
    assertEquals("media_request_cancelled",assertThrows(IOException.class,()->commit(cache,first)).getMessage());
    assertEquals("media_cache_metadata_budget",assertThrows(IOException.class,
     ()->cache.execute("video.cache.stage",request("extra","fresh",persistent))).getMessage());
    assertNotNull(cache.target(commit(cache,latest)));
    assertNotNull(cache.execute("video.cache.stage",request("extra","fresh",persistent)));
   }
  }
 }

 @Test public void registrationCountIsCheckedAtStageAndRecheckedAtCommit()throws Exception {
  try(PodMediaPrefixCache cache=stageCache()){
   String original=cache.execute("video.cache.register",request("original","old",false)).getString("uri");
   for(int i=1;i<PodMediaPrefixCache.MAX_REGISTRATIONS-1;i++){
    cache.execute("video.cache.register",request("fill-"+i,"fill",false));
   }
   JSONObject pending=cache.execute("video.cache.stage",request("pending","fresh",false));
   cache.execute("video.cache.register",request("last","last",false));
   assertEquals("media_cache_metadata_budget",assertThrows(IOException.class,()->commit(cache,pending)).getMessage());
   assertNull(cache.registration(pending.getString("uri")));
   assertEquals("media_cache_metadata_budget",assertThrows(IOException.class,
    ()->cache.execute("video.cache.stage",request("overflow","overflow",false))).getMessage());
   assertNotNull(cache.target(original));
   JSONObject replacement=cache.execute("video.cache.stage",request("original","replacement",false));
   assertNotNull(cache.target(original));
   assertNotNull(cache.target(commit(cache,replacement)));
   assertNull(cache.registration(original));
   assertEquals(PodMediaPrefixCache.MAX_REGISTRATIONS,cache.memoryStats().getInt("entries"));
  }
 }

 @Test public void replacementKeyBudgetCountsNewAndExistingUrisAtTheByteBoundary()throws Exception {
  for(boolean existing:new boolean[]{true,false}){
   for(int utf16Units:new int[]{-1,0,1}){
    try(PodMediaPrefixCache cache=stageCache()){
     JSONObject candidate=request("binding","candidate",false);
     String uri=cache.execute("video.cache.register",candidate).getString("uri");
     long withKey=metadataBytes(cache);
     cache.execute("video.cache.remove",new JSONObject().put("uri",uri));
     if(existing)cache.execute("video.cache.register",new JSONObject(candidate.toString()).put("replacesUri",JSONObject.NULL));
     long delta=withKey-metadataBytes(cache);
     long limit=PodMediaPrefixCache.MAX_METADATA_BYTES;
     fillMetadata(cache,limit-delta+2L*utf16Units);
     long before=metadataBytes(cache);
     if(utf16Units>0){
      assertEquals("media_cache_metadata_budget",assertThrows(IOException.class,
       ()->cache.execute("video.cache.register",candidate)).getMessage());
      assertEquals(before,metadataBytes(cache));
      if(existing)assertNull(cache.target(uri).replacementKey);
      else assertNull(cache.registration(uri));
     }else{
      assertEquals(uri,cache.execute("video.cache.register",candidate).getString("uri"));
      assertEquals("binding",cache.target(uri).replacementKey);
      assertEquals(limit+2L*utf16Units,metadataBytes(cache));
     }
    }
   }
  }
 }

 @Test public void idleExpiryFreesTheStageWithoutReleasingTheOldUri()throws Exception {
  try(PodMediaPrefixCache cache=stageCache()){
   String old=cache.execute("video.cache.register",request("idle","old",false)).getString("uri");
   JSONObject pending=cache.execute("video.cache.stage",request("idle","pending",false));
   Field field=PodMediaPrefixCache.class.getDeclaredField("stagedRegistrations");
   field.setAccessible(true);
   Map<?,?> stages=(Map<?,?>)field.get(cache);
   assertEquals(1,stages.size());
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
   assertTrue(stages.isEmpty());
   assertEquals("media_request_cancelled",assertThrows(IOException.class,()->commit(cache,pending)).getMessage());
   assertNotNull(cache.target(old));
  }
 }

 @Test public void firstCacheReadAfterDeepSleepFreesExpiredStage()throws Exception {
  try(PodMediaPrefixCache cache=stageCache()){
   String old=cache.execute("video.cache.register",request("sleep","old",false)).getString("uri");
   JSONObject pending=cache.execute("video.cache.stage",request("sleep","next",false));
   Field field=PodMediaPrefixCache.class.getDeclaredField("stagedRegistrations");
   field.setAccessible(true);
   Map<?,?> stages=(Map<?,?>)field.get(cache);
   assertEquals(1,stages.size());
   long uptime=android.os.SystemClock.uptimeMillis();
   ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(31));
   assertEquals(uptime,android.os.SystemClock.uptimeMillis());
   assertNotNull(cache.target(old));
   assertTrue(stages.isEmpty());
   assertEquals("media_request_cancelled",assertThrows(IOException.class,()->commit(cache,pending)).getMessage());
  }
 }

 private static PodMediaPrefixCache stageCache()throws Exception {
  return new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),new PodMediaStockRangeTest.Ranges(),6L<<20);
 }
 private static JSONObject request(String key,String source,boolean persistent)throws Exception {
  return new JSONObject().put("key",key).put("url","https://fixture.invalid/"+source)
   .put("persistent",persistent).put("replacesUri","");
 }
 private static String commit(PodMediaPrefixCache cache,JSONObject pending)throws Exception {
  return cache.execute("video.cache.commit",new JSONObject().put("stageId",pending.getString("stageId"))).getString("uri");
 }
 private static long metadataBytes(PodMediaPrefixCache cache)throws Exception {
  return cache.memoryStats().getLong("metadataUtf16BytesUpperBound");
 }
 private static void fillMetadata(PodMediaPrefixCache cache,long target)throws Exception {
  for(int i=0;target-metadataBytes(cache)>65536;i++){
   JSONObject row=request("fill-"+i,"fill",false).put("headers",new JSONObject().put("X-Pad","x".repeat(16000)));
   cache.execute("video.cache.register",row);
  }
  JSONObject pad=request("pad","pad",true).put("headers",new JSONObject().put("X-Pad",""));
  cache.execute("video.cache.register",pad);
  int chars=(int)((target-metadataBytes(cache))/2);
  pad.put("headers",new JSONObject().put("X-Pad","x".repeat(chars)));
  cache.execute("video.cache.register",pad);
  assertEquals(target,metadataBytes(cache));
 }

}
