package dev.podjs.runtime;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class PodSecureBatchTest {
 @Test public void batchMergesWithoutMutatingOldMapAndDeletesOnlyNamedKeys()throws Exception {
  JSONObject old=new JSONObject().put("bili","old").put("douyin","old").put("unrelated","retained");
  JSONObject next=PodDeviceServices.mergeSecure(old,new JSONObject().put("bili","new").put("douyin",JSONObject.NULL));
  assertEquals("old",old.getString("bili"));assertEquals("old",old.getString("douyin"));
  assertEquals("new",next.getString("bili"));assertFalse(next.has("douyin"));assertEquals("retained",next.getString("unrelated"));
 }
 @Test public void invalidEntryOrQuotaLeavesOriginalUnchanged()throws Exception {
  JSONObject old=new JSONObject().put("bili","old");
  JSONObject[] invalid={new JSONObject().put("bili","new").put("invalid",7),new JSONObject().put("","secret"),new JSONObject().put("bili","x".repeat(262144))};
  for(JSONObject batch:invalid){try{PodDeviceServices.mergeSecure(old,batch);fail("invalid batch accepted");}catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains("secret"));}assertEquals("old",old.getString("bili"));assertEquals(1,old.length());}
 }
 @Test public void prefixesAreAtomicAndRetainOtherAccounts()throws Exception {
  JSONObject old=new JSONObject().put("scopeA.key.1","one").put("scopeA.state","state").put("scopeB.key.1","two");
  JSONObject next=PodDeviceServices.mergeSecure(old,new JSONObject().put("scopeA.key.2","new"),new org.json.JSONArray().put("scopeA."));
  assertEquals(3,old.length());assertFalse(next.has("scopeA.key.1"));assertFalse(next.has("scopeA.state"));assertEquals("two",next.getString("scopeB.key.1"));assertEquals("new",next.getString("scopeA.key.2"));
  try{PodDeviceServices.mergeSecure(old,new JSONObject(),new org.json.JSONArray().put("scopeA.").put(""));fail();}catch(IllegalArgumentException expected){}
  assertEquals(3,old.length());
 }
 @Test public void nativeEcdhFieldsSurviveScopedCleanupAndRejectExplicitOrOverlappingGuestMutation()throws Exception {
  String nativeKey="__podjs_native_ecdh_v1.fixture";
  JSONObject old=new JSONObject().put(nativeKey,"native-private").put("watchrss.cloud.state.fixture","old").put("other","keep");
  try(PodDeviceServices service=new PodDeviceServices(androidx.test.core.app.ApplicationProvider.getApplicationContext())) {
   for(String operation:new String[]{"secure.get","secure.set","secure.delete","secure.update"}) {
    JSONObject request=new JSONObject().put("key",nativeKey).put("value","forbidden").put("values",new JSONObject().put(nativeKey,"forbidden"));
    try{service.execute(operation,request);fail();}catch(SecurityException expected){assertEquals("reserved_secure_key",expected.getMessage());}
   }
  }
  JSONObject next=PodDeviceServices.mergeSecure(old,new JSONObject().put("normal","value"),new org.json.JSONArray().put("watchrss.cloud.state."));
  assertEquals("native-private",next.getString(nativeKey));assertFalse(next.has("watchrss.cloud.state.fixture"));assertEquals("keep",next.getString("other"));
  for(Object value:new Object[]{"replacement",JSONObject.NULL}) {
   try{PodDeviceServices.mergeSecure(old,new JSONObject().put(nativeKey,value));fail();}catch(SecurityException expected){assertEquals("reserved_secure_key",expected.getMessage());}
  }
  for(String prefix:new String[]{"_","__podjs_native_","__podjs_native_ecdh_v1.",nativeKey}) {
   try{PodDeviceServices.mergeSecure(old,new JSONObject().put("normal","value"),new org.json.JSONArray().put("watchrss.cloud.state.").put(prefix));fail();}catch(SecurityException expected){assertEquals("reserved_secure_key",expected.getMessage());}
   assertEquals("old",old.getString("watchrss.cloud.state.fixture"));assertEquals("native-private",old.getString(nativeKey));assertFalse(old.has("normal"));
  }
 }
}
