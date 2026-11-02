package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.io.IOException;
import java.io.File;
import java.util.Map;
import static org.junit.Assert.*;

/** Decoder fault injection checks the production cache-only readiness gate, not codec performance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=PodMediaStockReadinessTest.Decoder.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodMediaStockReadinessTest {
    @Implements(MediaMetadataRetriever.class)
    public static class Decoder {
        MediaDataSource source;
        static boolean probeGap;static Runnable duringDecode;static int reads;
        @Implementation protected void __constructor__(){}
        @Implementation protected void setDataSource(MediaDataSource source){this.source=source;}
        @Implementation protected Bitmap getScaledFrameAtTime(long time,int option,int width,int height){
            assertNotNull("decoder must receive the cache-only MediaDataSource",source);
            try{
                byte[] sample=new byte[64];assertEquals(0,source.readAt(2L*1024*1024,sample,0,0));assertEquals(64,source.readAt(0,sample,0,sample.length));reads++;
                assertEquals(64,source.readAt(source.getSize()-64,sample,0,sample.length));reads++;
                if(probeGap)try{source.readAt(2L*1024*1024,sample,0,sample.length);}catch(IOException recovered){}
            }catch(IOException error){throw new IllegalStateException(error);}
            duringDecode.run();
            // Model a decoder that returns a fallback frame despite a failed probe.
            return Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        }
        @Implementation protected void release(){}
    }
    @Before public void reset(){Decoder.probeGap=false;Decoder.reads=0;Decoder.duringDecode=()->{};}
    @Test public void completeLocalRangesAndDecodedFrameAreRequiredBeforeReadiness()throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*1024*1024)){
            String uri=cache.register("ready","https://example.test/movie",Map.of(),true);
            final long[] networkAtDecode={-1};Decoder.duringDecode=()->{try{networkAtDecode[0]=cache.stats().getLong("networkReadBytes");}catch(Exception e){throw new RuntimeException(e);}};
            JSONObject result=cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*1024*1024).put("frameKey","ready"));
            assertTrue(result.getBoolean("ready"));assertEquals(2,Decoder.reads);assertEquals(2*1024*1024,networkAtDecode[0]);assertEquals(networkAtDecode[0],cache.stats().getLong("networkReadBytes"));
            assertTrue(new PodVideoFrameCache(cache.frameRoot).restore("ready",PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache)).getBoolean("available"));
        }
    }
    @Test public void recoveredDecoderProbeCannotHideACacheGapOrPublishAFrame()throws Exception {
        Decoder.probeGap=true;PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*1024*1024)){
            String uri=cache.register("gap","https://example.test/movie",Map.of(),true);
            JSONObject result=cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*1024*1024).put("frameKey","gap"));
            assertFalse(result.getBoolean("ready"));assertEquals(2*1024*1024,cache.stats().getLong("networkReadBytes"));
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("PodMediaCache").stream().anyMatch(row->row.msg.matches("stock_gap position=[0-9]+ requestedBytes=[0-9]+ prefixBytes=[0-9]+ tailStart=[0-9]+ totalBytes=[0-9]+")));
            assertFalse(new PodVideoFrameCache(cache.frameRoot).restore("gap",PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache)).getBoolean("available"));
        }
    }
    @Test public void evictionDuringDecodeCannotPublishReadiness()throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*1024*1024)){
            String uri=cache.register("evicted","https://example.test/movie",Map.of(),true);
            Decoder.duringDecode=()->{try{assertTrue(cache.target(uri).file.delete());}catch(Exception e){throw new RuntimeException(e);}};
            JSONObject result=cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*1024*1024).put("frameKey","evicted"));
            assertFalse(result.getBoolean("ready"));assertFalse(new PodVideoFrameCache(cache.frameRoot).restore("evicted",PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache)).getBoolean("available"));
        }
    }

    @Test public void repeatedPreparationReusesCompleteRangesWithoutLosingReadiness()throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        File root=PodMediaPrefixCacheTest.root();
        for(int restart=0;restart<2;restart++)try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6*1024*1024)){
            String uri=cache.register("repeat","https://example.test/movie",Map.of(),true);
            JSONObject args=new JSONObject().put("uri",uri).put("bytes",2*1024*1024).put("frameKey","repeat");
            assertTrue(cache.execute("video.cache.prepare",args).getBoolean("ready"));
            long before=cache.stats().getLong("networkReadBytes");
            assertTrue("reusing the suffix must not subtract its budget twice",cache.execute("video.cache.prepare",args).getBoolean("ready"));
            assertEquals(before,cache.stats().getLong("networkReadBytes"));
            assertEquals(1536*1024,cache.target(uri).file.length());
            assertEquals(512*1024,cache.target(uri).tail().length());
            if(restart==1)assertEquals("persisted ranges need no upstream reads",0,before);
        }
    }

    @Test public void minimumBudgetReservesTheSuffixBeforeTheFirstStageFinishes()throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*1024*1024)){
            String uri=cache.register("minimum","https://example.test/movie",Map.of(),true);
            JSONObject result=cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",128*1024).put("frameKey","minimum"));
            assertTrue(result.getBoolean("ready"));
            assertEquals(64*1024,result.getLong("prefixBytes"));assertEquals(64*1024,result.getLong("tailBytes"));
            assertEquals(128*1024,remote.readBytes);assertEquals(remote.readBytes,cache.stats().getLong("networkReadBytes"));
        }
    }

}
