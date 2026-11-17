package dev.podjs.runtime;

import java.io.File;
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
            assertThrows(java.io.IOException.class,()->cache.target(uri));
            assertEquals(0,cache.stats().getLong("bytes"));
            assertTrue(remote.readBytes<512*1024);
            cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(target.put("generation",2))).put("revision",2));
            assertEquals(1,events.size());
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
            assertThrows(java.io.IOException.class,()->cache.target(uri));
        }
    }

    @Test public void openPrefixReaderStopsAfterResolutionRejection() throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            String uri=cache.execute("video.cache.register",new JSONObject().put("key","active")
                .put("url","https://fixture.invalid/clip").put("maxVideoShortEdge",720)).getString("uri");
            androidx.media3.datasource.DataSource source=cache.factory(remote).createDataSource();
            byte[] buffer=new byte[64];
            try {
                source.open(new androidx.media3.datasource.DataSpec.Builder().setUri(uri).build());
                assertEquals(64,source.read(buffer,0,buffer.length));
                assertEquals(64,cache.stats().getLong("bytes"));
                cache.rejectResolution(uri);
                assertThrows(java.io.IOException.class,()->cache.target(uri));
                assertEquals(0,cache.stats().getLong("bytes"));
                int before=remote.readBytes;
                assertThrows(PodVideoShortEdge.Rejected.class,()->source.read(buffer,0,buffer.length));
                assertEquals(before,remote.readBytes);
                assertEquals(0,cache.stats().getLong("bytes"));
            } finally {source.close();}
        }
    }

    @Test public void resolutionRejectionClosesBlockedForegroundRead() throws Exception {
        java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
        androidx.media3.datasource.DataSource.Factory remote=()->new androidx.media3.datasource.DataSource(){
            android.net.Uri uri;
            public void addTransferListener(androidx.media3.datasource.TransferListener listener){}
            public long open(androidx.media3.datasource.DataSpec spec){uri=spec.uri;return 4096;}
            public int read(byte[] data,int offset,int length)throws java.io.IOException{
                reading.countDown();
                try{
                    if(!closed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("close timed out");
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}
                // Simulate bytes returned by the socket as cancellation arrives.
                return 1;
            }
            public android.net.Uri getUri(){return uri;}
            public void close(){closed.countDown();}
        };
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            String uri=register(cache,"blocked");
            androidx.media3.datasource.DataSource source=cache.factory(remote).createDataSource();
            source.open(new androidx.media3.datasource.DataSpec(android.net.Uri.parse(uri)));
            java.util.concurrent.FutureTask<Throwable> result=new java.util.concurrent.FutureTask<>(()->{
                try{source.read(new byte[1],0,1);return null;}catch(Exception error){return error;}
            });
            Thread worker=new Thread(result);
            worker.start();
            try{
                assertTrue(reading.await(5,java.util.concurrent.TimeUnit.SECONDS));
                cache.rejectResolution(uri);
                assertTrue(closed.await(3,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(result.get(3,java.util.concurrent.TimeUnit.SECONDS) instanceof PodVideoShortEdge.Rejected);
                assertEquals(0,cache.stats().getLong("bytes"));
            }finally{source.close();worker.join(5000);}
        }
    }

    @Test public void generatedReplacementsRevokeOldRegistrationsAndBytes() throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            java.util.Random random=new java.util.Random(0x7202026);
            String prior="";
            for(int step=0;step<24;step++){
                JSONObject args=new JSONObject().put("key","generated").put("url","https://example.test/video/"+step)
                    .put("maxVideoShortEdge",720).put("replacesUri",random.nextBoolean()?prior:"");
                String next=cache.execute("video.cache.register",args).getString("uri");
                if(!prior.isEmpty()){
                    final String superseded=prior;
                    assertThrows(java.io.IOException.class,()->cache.target(superseded));
                    assertEquals(0,cache.stats().getLong("bytes"));
                    cache.execute("video.cache.window",new JSONObject().put("items",new org.json.JSONArray()
                        .put(new JSONObject().put("uri",superseded)).put(new JSONObject().put("uri",next))));
                }
                androidx.media3.datasource.DataSource source=cache.factory(remote).createDataSource();
                try{
                    source.open(new androidx.media3.datasource.DataSpec(android.net.Uri.parse(next)));
                    int size=1+random.nextInt(64);
                    assertEquals(size,source.read(new byte[size],0,size));
                    assertEquals(size,cache.stats().getLong("bytes"));
                }finally{source.close();}
                prior=next;
            }
        }
    }

    @Test public void restartScrubsOversizedPrefixAndRejectRemovesUnregisteredBytes() throws Exception {
        File root=PodMediaPrefixCacheTest.root();
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        String oversized,unclassified,compliant; File bad,pending,safe;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6L<<20)){
            oversized=register(cache,"large-restart");
            unclassified=register(cache,"unknown-restart");
            compliant=register(cache,"safe-restart");
            bad=cache.target(oversized).file;
            pending=cache.target(unclassified).file;
            safe=cache.target(compliant).file;
            java.nio.file.Files.write(bad.toPath(),PodVideoShortEdgeTest.movie(1080,1920));
            java.nio.file.Files.write(pending.toPath(),new byte[64]);
            java.nio.file.Files.write(safe.toPath(),PodVideoShortEdgeTest.movie(720,1280));
            for(File file:new File[]{bad,pending,safe}){
                try(java.io.DataOutputStream length=new java.io.DataOutputStream(new java.io.FileOutputStream(lengthFile(file)))){length.writeLong(file.length());}
            }
        }
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6L<<20)){
            cache.rejectResolution(unclassified);
            assertFalse(pending.exists());
            assertFalse(lengthFile(pending).exists());
            assertFalse(bad.exists());
            assertFalse(lengthFile(bad).exists());
            assertTrue(safe.isFile());
            assertTrue(lengthFile(safe).isFile());
        }
    }
    private static File lengthFile(File prefix){return new File(prefix.getParentFile(),prefix.getName().replace(".prefix",".length"));}

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
