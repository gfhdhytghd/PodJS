package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaPrefixRegistrationTest {
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
}
