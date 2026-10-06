package dev.podjs.runtime;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Device-owner-only execution. These sources are compile-checked by wrss-c. */
public class PodMemoryStatsTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 private Context flags(boolean debug){return new ContextWrapper(context()){
  @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.flags=debug?ApplicationInfo.FLAG_DEBUGGABLE:0;return info;}
 };}
 @Test public void releaseHasNoMemoryCollectorOrCounters(){
  assertFalse(PodServices.memoryEnabled(flags(false)));
  assertTrue(PodServices.memoryEnabled(flags(true)));
 }
 @Test public void realRasterBitmapsRecycleAndParagraphCacheHitsStayBounded()throws Exception{
  JSONObject paragraph=new JSONObject().put("text","memory ownership").put("widthPx",64).put("fontSizePx",14);
  PodParagraphLayout.Cache cache=new PodParagraphLayout.Cache();
  // Also exercise the existing no-cache/default production overload.
  PodParagraphLayout.obtain(context(),paragraph,new CancellationSignal(),null);
  for(int i=0;i<20;i++)PodParagraphLayout.obtain(context(),paragraph,new CancellationSignal(),cache);
  assertEquals(1,cache.entries.size());assertEquals(paragraph.getString("text").length(),cache.units);assertEquals(1,cache.builds);
  PodTextRasterServices.Stats stats=new PodTextRasterServices.Stats();
  JSONObject args=new JSONObject().put("text","memory").put("widthPx",64).put("heightPx",32).put("baselinePx",24).put("fontSizePx",14);
  JSONObject output=PodTextRasterServices.raster(context(),args,new CancellationSignal(),cache,stats);
  try{
   JSONObject snapshot=stats.snapshot();
   assertEquals(1,snapshot.getLong("created"));assertEquals(1,snapshot.getLong("recycled"));
   assertEquals(0,snapshot.getLong("live"));assertEquals(0,snapshot.getLong("liveBytes"));
   assertEquals(1,snapshot.getLong("peak"));assertTrue(snapshot.getLong("peakBytes")>=64*32*4);
  }finally{PodTextRasterServices.discard(context(),output);cache.clear();}
  assertEquals(0,cache.entries.size());assertEquals(0,cache.units);
 }
}
