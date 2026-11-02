package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMediaStockRangeTest {
    static final int M=1024*1024;
    static class Ranges implements DataSource.Factory {
        final byte[] body=new byte[5*M];
        int cap=Integer.MAX_VALUE,tailLimit=Integer.MAX_VALUE,readBytes;boolean wrongStart;Runnable afterRead=()->{};
        final List<DataSpec> opened=new ArrayList<>();
        Ranges(){new Random(19).nextBytes(body);}
        public DataSource createDataSource(){return new DataSource(){
            int position,end,delivered;DataSpec spec;
            public void addTransferListener(TransferListener ignored){}
            public long open(DataSpec request){spec=request;opened.add(request);position=(int)request.position;end=(int)Math.min(body.length,position+Math.min(cap,request.length<0?body.length:request.length));return end-position;}
            public int read(byte[] target,int offset,int length){
                if(position>=end||(spec.position>=4*M&&delivered>=tailLimit))return C.RESULT_END_OF_INPUT;
                int n=Math.min(length,end-position);if(spec.position>=4*M)n=Math.min(n,tailLimit-delivered);
                System.arraycopy(body,position,target,offset,n);position+=n;delivered+=n;readBytes+=n;afterRead.run();return n;
            }
            public Uri getUri(){return spec.uri;}
            public Map<String,List<String>> getResponseHeaders(){return Map.of("Content-Range",List.of("bytes "+(spec.position+(wrongStart?1:0))+"-"+(end-1)+"/"+body.length));}
            public void close(){}
        };}
    }
    @Test public void capped206DoesNotTruncateTotalAndCompletesBothRanges()throws Exception {
        Ranges remote=new Ranges();remote.cap=128*1024;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("capped","https://example.test/movie",Map.of(),true);
            PodMediaPrefixCache.Job job=cache.beginPreparation(uri,M);
            try{
                // Preparation owns both ranges now; the queue yields between prefix stages.
                cache.prefetch(job);assertNull(job.failure);assertEquals("range EOF is not file EOF",5*M,job.entry.length);
                assertArrayEquals(Arrays.copyOf(remote.body,M/2),java.nio.file.Files.readAllBytes(job.entry.file.toPath()));
                assertArrayEquals(Arrays.copyOfRange(remote.body,5*M-M/2,5*M),java.nio.file.Files.readAllBytes(job.entry.tail().toPath()));
                assertEquals(M,remote.readBytes);assertEquals(remote.readBytes,cache.stats().getLong("networkReadBytes"));
                assertTrue("both ranges require continuation",remote.opened.size()>2);
            }
            finally{cache.endPreparation(job);}
        }
    }
    @Test public void tailFailureCountsEveryReadAndCannotPublishReadiness()throws Exception {
        Ranges remote=new Ranges();remote.tailLimit=64*1024;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("short-tail","https://example.test/movie",Map.of(),true);
            try{JSONObject result=cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","short-tail"));assertFalse(result.getBoolean("ready"));}
            catch(EOFException expected){}
            assertEquals("failed tail reads are real network reads",remote.readBytes,cache.stats().getLong("networkReadBytes"));
            assertEquals(0,cache.target(uri).tail().length());
        }
    }
    @Test public void capped206TailIsCompleteAndByteIdentical()throws Exception {
        Ranges remote=new Ranges();remote.cap=128*1024;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("capped-tail","https://example.test/movie",Map.of(),true);
            cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","tail"));
            assertEquals(512*1024,cache.target(uri).tail().length());
            assertArrayEquals(Arrays.copyOfRange(remote.body,remote.body.length-512*1024,remote.body.length),java.nio.file.Files.readAllBytes(cache.target(uri).tail().toPath()));
            assertEquals(5*M,cache.target(uri).length);assertEquals(remote.readBytes,cache.stats().getLong("networkReadBytes"));
        }
    }
    @Test public void cancellationKeepsOnlyContiguousPrefixAndNeverPublishesPartialTail()throws Exception {
        Ranges remote=new Ranges();File root=PodMediaPrefixCacheTest.root();String uri;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6*M)){
            uri=cache.register("cancel-tail","https://example.test/movie",Map.of(),true);
            remote.afterRead=()->{if(remote.readBytes>1536*1024)try{cache.execute("video.cache.window",new JSONObject().put("items",new org.json.JSONArray()));}catch(Exception e){throw new RuntimeException(e);}};
            try{cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","tail"));fail("cancelled preparation must not publish readiness");}catch(IOException expected){assertEquals("preparation_cancelled",expected.getMessage());}
            assertEquals(0,cache.target(uri).tail().length());assertEquals(remote.readBytes,cache.stats().getLong("networkReadBytes"));
        }
        remote.afterRead=()->{};try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6*M)){
            String fresh=cache.register("cancel-tail","https://fresh.test/movie",Map.of(),true);assertEquals(uri,fresh);assertEquals(5*M,cache.target(fresh).length);
            assertArrayEquals(Arrays.copyOf(remote.body,1536*1024),java.nio.file.Files.readAllBytes(cache.target(fresh).file.toPath()));assertEquals(0,cache.target(fresh).tail().length());
        }
    }
    @Test public void ignoredRange200CountsBytesSkippedInsideTheHttpTransport()throws Exception {
        byte[] body=new byte[4096];new Random(23).nextBytes(body);
        try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root())){
            java.util.concurrent.FutureTask<Void> served=new java.util.concurrent.FutureTask<>(()->{
                try(java.net.Socket socket=server.accept()){
                    socket.setSoTimeout(3000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;
                    while((line=input.readLine())!=null&&!line.isEmpty()){}
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
                }return null;
            });new Thread(served).start();
            String uri=cache.register("ignore-range","http://127.0.0.1:"+server.getLocalPort()+"/movie",Map.of(),true);
            PodMediaPrefixCache.Job job=cache.beginPreparation(uri,M);
            try{assertArrayEquals(Arrays.copyOfRange(body,2048,4096),cache.loadRange(job,2048,2048));assertEquals("HTTP skipped bytes are real network reads",4096,cache.stats().getLong("networkReadBytes"));}
            finally{cache.endPreparation(job);}
            served.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test public void response206WithoutContentRangeCannotMasqueradeAsACompleteSmallFile()throws Exception {
        try(java.net.ServerSocket server=new java.net.ServerSocket(0);PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root())){
            java.util.concurrent.FutureTask<Void> served=new java.util.concurrent.FutureTask<>(()->{
                try(java.net.Socket socket=server.accept()){
                    socket.setSoTimeout(3000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;
                    while((line=input.readLine())!=null&&!line.isEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\nConnection: close\r\n\r\n1234".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }return null;
            });new Thread(served).start();
            String uri=cache.register("missing-range","http://127.0.0.1:"+server.getLocalPort()+"/movie",Map.of(),true);
            PodMediaPrefixCache.Job job=cache.beginPreparation(uri,M);
            try{cache.prefetch(job);assertEquals(0,job.entry.file.length());assertEquals(-1,job.entry.length);assertNotNull(job.failure);assertEquals("missing_media_range",job.failure.getMessage());}
            finally{cache.endPreparation(job);}
            served.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    static void integer(byte[] bytes,int offset,int value){for(int i=3;i>=0;i--){bytes[offset+i]=(byte)value;value>>>=8;}}
    static Ranges largeMoov(){Ranges remote=new Ranges();integer(remote.body,0,24);integer(remote.body,4,0x66747970);integer(remote.body,24,3*M-24);integer(remote.body,28,0x6d646174);integer(remote.body,3*M,2*M);integer(remote.body,3*M+4,0x6d6f6f76);return remote;}
    @Test public void tailMoovAtParserLimitKeepsItsReadAheadGuardAndSurvivesRestart()throws Exception {
        Ranges remote=new Ranges();int moovStart=5*M-512*1024,tailStart=moovStart-64*1024;
        integer(remote.body,0,24);integer(remote.body,4,0x66747970);integer(remote.body,24,moovStart-24);integer(remote.body,28,0x6d646174);
        integer(remote.body,moovStart,512*1024);integer(remote.body,moovStart+4,0x6d6f6f76);
        File root=PodMediaPrefixCacheTest.root();String uri;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6*M)){
            uri=cache.register("large-moov","https://example.test/movie",Map.of(),true);
            cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",4*M).put("frameKey","moov"));
            assertEquals(576*1024,cache.target(uri).tail().length());assertTrue(cache.stats().getLong("bytes")<=4*M);
            assertArrayEquals(Arrays.copyOfRange(remote.body,tailStart,5*M),java.nio.file.Files.readAllBytes(cache.target(uri).tail().toPath()));
            long before=cache.stats().getLong("networkReadBytes");
            cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",4*M).put("frameKey","moov"));
            assertTrue("repeated preparation keeps the joint range budget",cache.stats().getLong("bytes")<=4*M);
            assertEquals("complete metadata needs no second download",before,cache.stats().getLong("networkReadBytes"));
        }
        remote.opened.clear();try(PodMediaPrefixCache cache=new PodMediaPrefixCache(root,remote,6*M)){
            String fresh=cache.register("large-moov","https://new.test/fresh",Map.of("Cookie","rotated"),true);assertEquals(uri,fresh);
            DataSource source=cache.factory(remote).createDataSource();source.open(new DataSpec.Builder().setUri(fresh).setPosition(tailStart).setLength(5*M-tailStart).build());
            assertArrayEquals(Arrays.copyOfRange(remote.body,tailStart,5*M),PodMediaPrefixCacheTest.read(source,5*M-tailStart));source.close();assertEquals(0,remote.opened.size());
        }
    }
    @Test public void frontMoovKeepsBoundedPrefixInsteadOfCachingTheWholeMovie()throws Exception {
        Ranges remote=new Ranges();integer(remote.body,0,24);integer(remote.body,4,0x66747970);integer(remote.body,24,4096);integer(remote.body,28,0x6d6f6f76);
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("front-moov","https://example.test/movie",Map.of(),true);
            cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","front"));
            // MP4 bytes estimates the sample prefix; a front moov needs no fallback suffix.
            assertEquals(2*M,cache.target(uri).file.length());assertEquals(0,cache.target(uri).tail().length());assertEquals(2*M,remote.readBytes);
        }
    }
    @Test public void metadataThatCannotFitNeverPublishesReadyOrExceedsBudget()throws Exception {
        Ranges remote=largeMoov();try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("over-budget","https://example.test/movie",Map.of(),true);
            // The 2 MiB moov exceeds the documented 512 KiB parser limit.
            try{cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","moov"));fail("oversized metadata must reject preparation");}
            catch(IOException expected){assertEquals("metadata_over_budget",expected.getMessage());}
            assertEquals(0,cache.target(uri).tail().length());assertTrue(cache.stats().getLong("bytes")<=2*M);
            assertFalse(new PodVideoFrameCache(cache.frameRoot).restore("moov",PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache)).getBoolean("available"));
        }
    }
    @Test public void metadataSuffixBeyondTheEntryBudgetIsRejectedEvenWithASmallMoov()throws Exception {
        Ranges remote=new Ranges();integer(remote.body,0,24);integer(remote.body,4,0x66747970);
        integer(remote.body,24,M-24);integer(remote.body,28,0x6d646174);integer(remote.body,M,4096);integer(remote.body,M+4,0x6d6f6f76);
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("oversized-suffix","https://example.test/movie",Map.of(),true);
            try{cache.execute("video.cache.prepare",new JSONObject().put("uri",uri).put("bytes",2*M).put("frameKey","suffix"));fail("suffix plus prefix exceeds the entry budget");}
            catch(IOException expected){assertEquals("metadata_over_budget",expected.getMessage());}
            assertEquals(0,cache.target(uri).tail().length());assertTrue(cache.stats().getLong("bytes")<=4*M);
            assertFalse(new PodVideoFrameCache(cache.frameRoot).restore("suffix",PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache)).getBoolean("available"));
        }
    }
    @Test public void mismatchedContentRangeNeverAppendsMisplacedBytes()throws Exception {
        Ranges remote=new Ranges();remote.wrongStart=true;
        try(PodMediaPrefixCache cache=new PodMediaPrefixCache(PodMediaPrefixCacheTest.root(),remote,6*M)){
            String uri=cache.register("misplaced","https://example.test/movie",Map.of(),true);
            PodMediaPrefixCache.Job job=cache.beginPreparation(uri,M);
            try{cache.prefetch(job);assertEquals(0,job.entry.file.length());assertEquals(1,cache.stats().getLong("failures"));}
            finally{cache.endPreparation(job);}
        }
    }
}
