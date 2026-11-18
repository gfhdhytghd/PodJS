package dev.podjs.runtime;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaPrefixFailureTest {
 @Test public void resolutionRejectionCannotBecomeAnOptionalCacheAvailabilityFailure(){
  for(Throwable error:new Throwable[]{new PodVideoShortEdge.Rejected(),new IOException("PRIVATE",new PodVideoShortEdge.Rejected())}){
   assertEquals("resolution_limit",PodMediaPrefixFailure.reason(error));
   assertEquals("resolution_limit",PodMediaPrefixFailure.code(error,false));
   assertEquals("resolution_limit",PodMediaPrefixFailure.code(error,true));
  }
 }
 @Test public void budgetsAndStorageFailuresHaveAvailabilityCodes(){
  assertEquals("resource_exhausted",PodMediaPrefixFailure.code(new IOException("media_cache_metadata_budget"),false));
  assertEquals("cache_error",PodMediaPrefixFailure.code(new IOException("PRIVATE"),false));
  assertEquals("cache_error",PodMediaPrefixFailure.code(new IOException("media_cache_registration_missing"),false));
 }
 @Test public void authorizationExpiryAndCancellationNeverBecomeCacheAvailability(){
  assertEquals("permission_denied",PodMediaPrefixFailure.code(new IOException("media_credentials_origin_rejected"),false));
  assertEquals("permission_denied",PodMediaPrefixFailure.code(new SecurityException("PRIVATE"),false));
  assertEquals("source_expired",PodMediaPrefixFailure.code(new IOException("media_source_expired"),false));
  assertEquals("cancelled",PodMediaPrefixFailure.code(new IOException("media_request_cancelled"),false));
  assertEquals("media_traffic_stopped",PodMediaPrefixFailure.code(new IOException("PRIVATE"),true));
  assertEquals("invalid_argument",PodMediaPrefixFailure.code(new IllegalArgumentException("PRIVATE"),false));
 }
 @Test public void diagnosticsContainOnlyFixedCategoriesAndNumericCounts(){
  ShadowLog.clear();PodMediaPrefixFailure.record("video.cache.register",new IOException("PRIVATE https://private.invalid/?token=SECRET"),null);
  String value=ShadowLog.getLogsForTag("PodMediaCache").get(0).msg;assertTrue(value.contains("operation=register reason=io"));assertFalse(value.contains("PRIVATE"));assertFalse(value.contains("SECRET"));assertFalse(value.contains("https"));
  ShadowLog.clear();PodMediaPrefixFailure.record("video.cache.PRIVATE",new RuntimeException("SECRET"),null);assertTrue(ShadowLog.getLogsForTag("PodMediaCache").get(0).msg.contains("operation=other reason=unknown"));
 }
 @Test public void remoteRefusalsRemainNetworkOrAuthorizationFailuresAndErrnoIsNumeric(){
  for(int status:new int[]{403,429,503}){
   Throwable error=new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(status,"PRIVATE",null,java.util.Collections.emptyMap(),new androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://fixture.invalid/PRIVATE")),new byte[0]);
   assertEquals(status==403?"source_expired":status==429?"restricted":"network_error",PodMediaPrefixFailure.code(error,false));assertEquals(status,PodMediaPrefixFailure.httpStatus(error));
  }
  assertEquals(24,PodMediaPrefixFailure.errno(new IOException("PRIVATE",new android.system.ErrnoException("PRIVATE",24))));
 }

}
