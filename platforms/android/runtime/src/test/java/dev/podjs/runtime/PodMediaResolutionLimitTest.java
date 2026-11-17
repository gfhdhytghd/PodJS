package dev.podjs.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaResolutionLimitTest {
    @Test public void unknownPrefixReportsResolutionLimitAndDropsBytesAndRegistration() throws Exception {
        PodMediaStockRangeTest.Ranges remote=portrait();
        List<JSONObject> events=new CopyOnWriteArrayList<>();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            cache.setResultSink(events::add);
            String uri=register(cache,"prefix");
            JSONObject target=new JSONObject().put("uri",uri).put("prefetchBytes",512*1024)
                .put("captureBytes",512*1024).put("itemToken","clip").put("generation",1);
            cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(target)).put("revision",1));
            long deadline=System.nanoTime()+5_000_000_000L;
            while(events.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(5);
            JSONObject event=events.get(0).getJSONObject("value");
            assertEquals("clip",event.getString("itemToken"));
            assertEquals("resolution_limit",event.getString("category"));
            assertThrows(PodVideoShortEdge.Rejected.class,()->cache.target(uri));
            assertEquals(0,cache.stats().getLong("bytes"));
            assertTrue(remote.readBytes<512*1024);
            cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(target.put("generation",2))).put("revision",2));
            assertEquals(2,events.get(1).getJSONObject("value").getInt("generation"));
        }
    }

    @Test public void unknownStockStopsAtTrackMetadataWithoutLeavingAnInventoryEntry() throws Exception {
        PodMediaStockRangeTest.Ranges remote=portrait();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            cache.localStore.configure(new JSONObject().put("minFreeBytes",0));
            String uri=register(cache,"stock");
            JSONObject args=new JSONObject().put("uri",uri).put("key","stock").put("metadata",new JSONObject());
            assertThrows(PodVideoShortEdge.Rejected.class,()->cache.execute("video.cache.local.prepare",args));
            assertTrue(remote.readBytes<1024);
            assertEquals(0,cache.stats().getLong("bytes"));
            assertEquals(0,cache.localStore.list().length());
            assertFalse(cache.localStore.file(PodMediaLocalStore.token("stock"),".part").exists());
            assertThrows(PodVideoShortEdge.Rejected.class,()->cache.target(uri));
        }
    }

    private static String register(PodMediaPrefixCache cache,String key) throws Exception {
        return cache.execute("video.cache.register",new JSONObject().put("key",key).put("url","https://example.test/video")
            .put("maxVideoShortEdge",720)).getString("uri");
    }

    private static PodMediaStockRangeTest.Ranges portrait(){
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        byte[] ftyp=PodVideoShortEdgeTest.box("ftyp");
        byte[] moov=PodVideoShortEdgeTest.movie(1080,1920);
        System.arraycopy(ftyp,0,remote.body,0,ftyp.length);
        System.arraycopy(moov,0,remote.body,ftyp.length,moov.length);
        return remote;
    }
}
