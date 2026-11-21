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

    @Test public void bypassedCapabilityStillStopsOnResolutionRevocationWithoutCachingBytes() throws Exception {
        PodMediaStockRangeTest.Ranges remote=new PodMediaStockRangeTest.Ranges();
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6L<<20)){
            String uri=register(cache,"bypass-revocation");
            androidx.media3.datasource.DataSource source=cache.factory(remote,true).createDataSource();
            byte[] buffer=new byte[64];
            try {
                source.open(new androidx.media3.datasource.DataSpec.Builder().setUri(uri).build());
                assertEquals(64,source.read(buffer,0,buffer.length));
                assertEquals(0,cache.stats().getLong("bytes"));
                cache.rejectResolution(uri);
                int before=remote.readBytes;
                assertThrows(PodVideoShortEdge.Rejected.class,()->source.read(buffer,0,buffer.length));
                assertEquals(before,remote.readBytes);
                assertEquals(0,cache.stats().getLong("bytes"));
                assertThrows(java.io.IOException.class,()->cache.target(uri));
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

    @Test public void stagedReplacementsRemainReplaceableWithoutGuestState() throws Exception {
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),new PodMediaStockRangeTest.Ranges(),6L<<20)){
            String current=cache.execute("video.cache.register",replacement("0","")).getString("uri");
            for(int cycle=0;cycle<3;cycle++){
                String previous=current;
                JSONObject candidate=cache.execute("video.cache.stage",replacement(String.valueOf(cycle*2+1),previous));
                String candidateUri=candidate.getString("uri");
                assertNotNull(cache.target(previous));
                assertNull(cache.registration(candidateUri));
                assertEquals(1,cache.memoryStats().getInt("entries"));

                assertEquals(candidateUri,cache.execute("video.cache.commit",new JSONObject()
                    .put("stageId",candidate.getString("stageId"))).getString("uri"));
                assertNull(cache.registration(previous));
                assertEquals("stage-lifecycle",cache.target(candidateUri).replacementKey);

                // No URI is passed when the app has left and rebuilt its index.
                current=cache.execute("video.cache.register",replacement(String.valueOf(cycle*2+2),"")).getString("uri");
                assertNull(cache.registration(candidateUri));
                assertEquals(1,cache.memoryStats().getInt("entries"));
            }
        }
    }

    @Test public void discardAndOvertakenCommitPreserveTheCurrentRegistration() throws Exception {
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),new PodMediaStockRangeTest.Ranges(),6L<<20)){
            String old=cache.execute("video.cache.register",replacement("old","")).getString("uri");
            JSONObject rejected=cache.execute("video.cache.stage",replacement("rejected",old));
            JSONObject rejectedId=new JSONObject().put("stageId",rejected.getString("stageId"));
            cache.execute("video.cache.discard",rejectedId);
            assertNull(cache.registration(rejected.getString("uri")));
            assertNotNull(cache.target(old));
            assertThrows(java.io.IOException.class,()->cache.execute("video.cache.commit",rejectedId));

            JSONObject stale=cache.execute("video.cache.stage",replacement("stale",old));
            String newer=cache.execute("video.cache.register",replacement("newer","")).getString("uri");
            assertThrows(java.io.IOException.class,()->cache.execute("video.cache.commit",
                new JSONObject().put("stageId",stale.getString("stageId"))));
            assertNull(cache.registration(stale.getString("uri")));
            assertNotNull(cache.target(newer));
            assertEquals(1,cache.memoryStats().getInt("entries"));
        }
    }

    private static JSONObject replacement(String suffix,String previous)throws Exception {
        return new JSONObject().put("key","stage-lifecycle").put("url","https://fixture.invalid/video-"+suffix)
            .put("maxVideoShortEdge",720).put("replacesUri",previous);
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
    @Test public void cappedRegistrationAndRestartDoNotTouchAnUncappedSource() throws Exception {
        File root=PodMediaPrefixCacheTest.root();
        byte[] portrait=PodVideoShortEdgeTest.movie(1080,1920);
        File uncapped, capped;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,new PodMediaStockRangeTest.Ranges(),6L<<20)){
            String uncappedUri=cache.register("unrelated","https://example.test/video",java.util.Map.of());
            uncapped=cache.target(uncappedUri).file;
            java.nio.file.Files.write(uncapped.toPath(),portrait);
            register(cache,"capped-scope");
            String cappedUri=register(cache,"unrelated");
            assertNotEquals(uncappedUri,cappedUri);
            assertTrue(cache.owns(uncappedUri));
            assertEquals(portrait.length,uncapped.length());
            capped=cache.target(cappedUri).file;
            java.nio.file.Files.write(capped.toPath(),portrait);
        }
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,new PodMediaStockRangeTest.Ranges(),6L<<20)){
            assertTrue(uncapped.isFile());
            assertEquals(portrait.length,uncapped.length());
            assertFalse(capped.isFile());
        }
    }

    @Test public void extendedLengthMoovIsScrubbedAtRestart() throws Exception {
        File root=PodMediaPrefixCacheTest.root();
        byte[] moov=PodVideoShortEdgeTest.movie(1080,1920);
        byte[] extended=java.nio.ByteBuffer.allocate(moov.length+8).putInt(1).putInt(0x6d6f6f76)
            .putLong(moov.length+8L).put(moov,8,moov.length-8).array();
        File prefix;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,new PodMediaStockRangeTest.Ranges(),6L<<20)){
            prefix=cache.target(register(cache,"extended-moov")).file;
            java.nio.file.Files.write(prefix.toPath(),extended);
            try(java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(lengthFile(prefix)))){
                out.writeLong(extended.length);
            }
        }
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,new PodMediaStockRangeTest.Ranges(),6L<<20)){
            assertFalse(prefix.isFile());
            assertFalse(lengthFile(prefix).isFile());
        }
    }

    @Test public void resolutionResultIdentifiesTheActualMirrorWithoutAnAddress() throws Exception {
        List<JSONObject> events=new CopyOnWriteArrayList<>();
        String mirror="https://example.test/mirror";
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),portrait(),6L<<20)){
            cache.setResultSink(events::add);
            String uri=cache.execute("video.cache.register",new JSONObject().put("key","mirrored")
                .put("url","https://example.test/primary").put("alternates",new JSONArray().put(mirror))
                .put("maxVideoShortEdge",720)).getString("uri");
            // Set the same winning-source state that the HTTP race records.
            cache.target(uri).selectedRemote=mirror;
            JSONObject target=new JSONObject().put("uri",uri).put("prefetchBytes",512*1024)
                .put("captureBytes",512*1024).put("itemToken","mirrored").put("generation",1);
            cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(target)));
            long deadline=System.nanoTime()+5_000_000_000L;
            while(events.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(5);
            JSONObject result=events.get(0).getJSONObject("value");
            assertEquals("resolution_limit",result.getString("category"));
            assertEquals(1,result.optInt("mirrorIndex",-1));
            assertFalse(result.toString().contains(mirror));
            assertFalse(cache.owns(uri));
            assertEquals(0,cache.stats().getLong("bytes"));
        }
    }

    @Test public void equivalentMirrorServing1080IsRejectedWithoutPlayableBytes() throws Exception {
        byte[] media;
        try(java.io.InputStream asset=getClass().getResourceAsStream("rescap-portrait.mp4")){
            assertNotNull(asset);media=asset.readAllBytes();
        }
        byte[] safe=PodVideoShortEdgeTest.movie(480,854);
        List<JSONObject> events=new CopyOnWriteArrayList<>();
        try(PodMediaHttpTest.Server primary=new PodMediaHttpTest.Server(0,800,206,safe);
            PodMediaHttpTest.Server mirror=new PodMediaHttpTest.Server(40,0,206,media);
            PodMediaHttp http=new PodMediaHttp();
            PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),http,6L<<20)){
            String canonical=mirror.url().replace("/fixture","/bad");
            String alias=mirror.url().replace("/fixture","/p"+(char)92+".."+(char)92+"bad");
            assertEquals(okhttp3.HttpUrl.get(canonical),okhttp3.HttpUrl.get(alias));
            cache.setResultSink(events::add);
            String uri=cache.execute("video.cache.register",new JSONObject().put("key","equivalent")
                .put("url",primary.url()).put("alternates",new JSONArray().put(alias))
                .put("maxVideoShortEdge",720)).getString("uri");
            File prefix=cache.target(uri).file;
            JSONObject item=new JSONObject().put("uri",uri).put("prefetchBytes",32*1024)
                .put("captureBytes",32*1024).put("itemToken","equivalent").put("generation",1);
            cache.execute("video.cache.window",new JSONObject().put("items",new JSONArray().put(item)));
            long deadline=System.nanoTime()+5_000_000_000L;
            while((events.isEmpty()||cache.owns(uri))&&System.nanoTime()<deadline)Thread.sleep(5);
            JSONObject result=events.get(0).getJSONObject("value");
            assertEquals("resolution_limit",result.getString("category"));
            assertEquals(1,result.getInt("mirrorIndex"));
            assertTrue(mirror.headers.stream().anyMatch(request->request.startsWith("GET /bad HTTP/")));
            assertTrue(cache.stats().getLong("networkReadBytes")>0);
            assertEquals(0,cache.stats().getLong("bytes"));
            assertFalse(prefix.isFile());
            assertFalse(lengthFile(prefix).exists());
            assertEquals(1,events.size());
            androidx.media3.datasource.DataSource playback=cache.factory(http).createDataSource();
            try{assertThrows(java.io.IOException.class,()->playback.open(new androidx.media3.datasource.DataSpec(android.net.Uri.parse(uri))));}
            finally{playback.close();}
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
