package dev.podjs.runtime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class PodSecureLeaseTest {
 @Test public void serviceInstancesExcludeEachOtherAndClosingOwnerReleasesLease()throws Exception {
  android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
  JSONObject request=new JSONObject().put("key","fixture.account");
  try(PodDeviceServices first=new PodDeviceServices(context);PodDeviceServices second=new PodDeviceServices(context)){
   assertTrue(first.execute("secure.acquire",request).getBoolean("acquired"));
   assertFalse(second.execute("secure.acquire",request).getBoolean("acquired"));
   second.execute("secure.release",request);
   assertFalse(second.execute("secure.acquire",request).getBoolean("acquired"));
   first.close();assertTrue(second.execute("secure.acquire",request).getBoolean("acquired"));
  }
  try(PodDeviceServices next=new PodDeviceServices(context)){assertTrue(next.execute("secure.acquire",request).getBoolean("acquired"));}
  assertTrue(PodCloudWorker.METHODS.contains("secure.acquire"));assertTrue(PodCloudWorker.METHODS.contains("secure.release"));
 }
}
