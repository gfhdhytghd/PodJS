package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** JVM-only checks of the actual stream IO and byte envelope, with no radio/device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodByteBridgeTest {
    @Test public void streamReadDifferentialAndEof()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            for(int n:new int[]{1,65535,65536}){
                byte[] bytes=new byte[n];for(int i=0;i<n;i++)bytes[i]=(byte)i;
                String old=streams.attach(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),()->{});
                String raw=streams.attach(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),()->{});
                JSONObject a=args(old,n),b=args(raw,n).put("_podByteMode",true);
                JSONObject legacy=(JSONObject)streams.execute("stream.read",a,new CancellationSignal());
                JSONObject binary=(JSONObject)streams.execute("stream.read",b,new CancellationSignal());
                assertFalse(binary.has("dataBase64"));assertArrayEquals(android.util.Base64.decode(legacy.getString("dataBase64"),0),(byte[])binary.get("_podBytes"));
                JSONObject eof=(JSONObject)streams.execute("stream.read",b,new CancellationSignal());assertTrue(eof.getBoolean("eof"));assertEquals(0,((byte[])eof.get("_podBytes")).length);
                streams.execute("stream.close",new JSONObject().put("id",old),new CancellationSignal());
            }
        }
    }
    @Test public void exclusiveHandoffPreservesUnreadTailAndOriginalClose() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            java.util.concurrent.atomic.AtomicInteger closed=new java.util.concurrent.atomic.AtomicInteger();
            byte[] bytes={1,2,3,4,5};
            String original=streams.attach(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),closed::incrementAndGet);
            CancellationSignal signal=new CancellationSignal();
            JSONObject before=streams.execute("stream.read",args(original,2).put("_podByteMode",true),signal);
            assertArrayEquals(new byte[]{1,2},(byte[])before.get("_podBytes"));
            String owner=streams.execute("stream.claim",new JSONObject().put("id",original),signal).getString("id");
            assertNotEquals(original,owner);
            try{streams.execute("stream.read",args(original,3).put("_podByteMode",true),signal);fail();}
            catch(IllegalArgumentException expected){assertEquals("Unknown stream",expected.getMessage());}
            try{streams.execute("stream.claim",new JSONObject().put("id",owner),signal);fail();}
            catch(IllegalStateException expected){assertEquals("Stream is not at a handoff boundary",expected.getMessage());}
            JSONObject after=streams.execute("stream.read",args(owner,3).put("_podByteMode",true),signal);
            assertArrayEquals(new byte[]{3,4,5},(byte[])after.get("_podBytes"));
            streams.execute("stream.close",new JSONObject().put("id",original),signal);
            streams.execute("stream.close",new JSONObject().put("id",owner),signal);
            assertEquals(1,closed.get());
        }
    }
    @Test public void nativeSpoolKeepsTheNextFrameAndRejectsTruncation()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        int length=1_577_359;
        byte[] wire=new byte[length+1];
        for(int i=0;i<length;i++)wire[i]=(byte)i;
        wire[length]=91;
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            String id=streams.attach(new ByteArrayInputStream(wire),new ByteArrayOutputStream(),()->{});
            JSONObject request=new JSONObject().put("id",id).put("size",length).put("timeoutMs",5000);
            ByteArrayOutputStream file=new ByteArrayOutputStream();
            JSONObject result=streams.execute("stream.spool",request,new CancellationSignal(),file);
            assertEquals(length,result.getInt("size"));
            assertArrayEquals(Arrays.copyOf(wire,length),file.toByteArray());
            JSONObject next=streams.execute("stream.read",args(id,1),new CancellationSignal());
            assertArrayEquals(new byte[]{91},android.util.Base64.decode(next.getString("dataBase64"),0));
            try{
                streams.execute("stream.spool",request,new CancellationSignal(),new ByteArrayOutputStream());
                fail("Truncated frame was accepted");
            }catch(EOFException expected){}
        }
    }

    @Test public void spoolBeforeHandoffLeavesFramedChannelInControl() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        byte[] control=ByteBuffer.allocate(18).putInt(14).putInt(0x4c425632)
                .putInt(1 << 24).putInt(2).put(new byte[]{'{','}'}).array();
        ByteArrayOutputStream input=new ByteArrayOutputStream();
        input.write(new byte[]{1,2,3});
        input.write(control);
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            String original=streams.attach(new ByteArrayInputStream(input.toByteArray()),new ByteArrayOutputStream(),()->{});
            CancellationSignal signal=new CancellationSignal();
            JSONObject spool=new JSONObject().put("id",original).put("size",3).put("timeoutMs",1000);
            ByteArrayOutputStream copied=new ByteArrayOutputStream();
            streams.execute("stream.spool",spool,signal,copied);
            assertArrayEquals(new byte[]{1,2,3},copied.toByteArray());

            String owner=streams.execute("stream.claim",new JSONObject().put("id",original),signal).getString("id");
            try{
                streams.execute("stream.spool",spool,signal,new ByteArrayOutputStream());
                fail("Old handle spooled bytes after the handoff");
            }catch(IllegalArgumentException expected){
                assertEquals("Unknown stream",expected.getMessage());
            }
            streams.execute("stream.framedOpen",new JSONObject().put("id",owner),signal);
            try{
                streams.execute("stream.spool",spool.put("id",owner),signal,new ByteArrayOutputStream());
                fail("Raw spool consumed a framed record");
            }catch(IOException expected){
                assertEquals("AUTH_REVOKED",expected.getMessage());
            }
            JSONObject next=streams.execute("stream.framedNext",args(owner,1),signal);
            assertEquals(PodTransferFrames.CONTROL,next.getInt("kind"));
            assertEquals("{}",next.getString("control"));
            streams.execute("stream.framedRevoke",new JSONObject().put("id",owner),signal);
        }
    }

    @Test public void spoolRefreshesIdleDeadlineAndPreventsHandoffUntilReadFinishes() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        InetAddress loopback=InetAddress.getLoopbackAddress();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,loopback);
            Socket peer=new Socket(loopback,listener.getLocalPort());
            Socket endpoint=listener.accept();
            PodByteStreamServices streams=new PodByteStreamServices(context)){
            String id=streams.attach(endpoint.getInputStream(),endpoint.getOutputStream(),endpoint);
            JSONObject request=new JSONObject().put("id",id).put("size",5)
                    .put("timeoutMs",5000).put("idleTimeoutMs",750);
            CountDownLatch first=new CountDownLatch(1);
            ByteArrayOutputStream file=new ByteArrayOutputStream(){
                @Override public synchronized void write(byte[] bytes,int offset,int length){
                    super.write(bytes,offset,length);
                    first.countDown();
                }
            };
            Future<JSONObject> spooling=reader.submit(()->streams.execute("stream.spool",request,new CancellationSignal(),file));
            peer.getOutputStream().write(1);
            assertTrue(first.await(3,TimeUnit.SECONDS));
            try{
                streams.execute("stream.claim",new JSONObject().put("id",id),new CancellationSignal());
                fail("Claim succeeded during the spool read");
            }catch(IllegalStateException expected){
                assertEquals("Stream is not at a handoff boundary",expected.getMessage());
            }
            for(int index=2;index<=5;index++){
                Thread.sleep(330);
                peer.getOutputStream().write(index);
            }
            assertEquals(5,spooling.get(3,TimeUnit.SECONDS).getInt("size"));
            assertArrayEquals(new byte[]{1,2,3,4,5},file.toByteArray());

            // A quiet peer must be closed before the independent overall deadline.
            peer.setSoTimeout(3000);
            Future<JSONObject> stalled=reader.submit(()->streams.execute("stream.spool",
                    request.put("size",1).put("idleTimeoutMs",200),new CancellationSignal(),new ByteArrayOutputStream()));
            assertEquals(-1,peer.getInputStream().read());
            try{
                stalled.get(3,TimeUnit.SECONDS);
                fail("Expected idle timeout");
            }catch(java.util.concurrent.ExecutionException expected){
                assertEquals("Stream operation timed out",expected.getCause().getMessage());
            }
        }finally{
            reader.shutdownNow();
        }
    }

    @Test public void writeDifferentialAndCancellation()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            for(int n:new int[]{1,65536}){
                byte[] bytes=new byte[n];Arrays.fill(bytes,(byte)255);ByteArrayOutputStream a=new ByteArrayOutputStream(),b=new ByteArrayOutputStream();
                String old=streams.attach(new ByteArrayInputStream(bytes),a,()->{}),raw=streams.attach(new ByteArrayInputStream(bytes),b,()->{});
                streams.execute("stream.write",args(old,n).put("dataBase64",android.util.Base64.encodeToString(bytes,2)),new CancellationSignal());
                streams.execute("stream.write",args(raw,n).put("_podByteMode",true).put("_podBytes",bytes),new CancellationSignal());
                assertArrayEquals(a.toByteArray(),b.toByteArray());
                streams.execute("stream.close",new JSONObject().put("id",old),new CancellationSignal());streams.execute("stream.close",new JSONObject().put("id",raw),new CancellationSignal());
            }
            String id=streams.attach(new ByteArrayInputStream(new byte[1]),new ByteArrayOutputStream(),()->{});CancellationSignal cancellation=new CancellationSignal();cancellation.cancel();
            try{streams.execute("stream.read",args(id,1).put("_podByteMode",true),cancellation);fail();}catch(android.os.OperationCanceledException expected){}
        }
    }
    @Test public void emptyPayloadAndOversizeAreDistinct()throws Exception {
        assertEquals(0,PodBytePayload.input(new JSONObject().put("_podByteMode",true).put("_podBytes",new byte[0])).length);
        for(Object bad:new Object[]{"wrong",new byte[65537]})try{PodBytePayload.input(new JSONObject().put("_podByteMode",true).put("_podBytes",bad));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void nativeByteReadKeepsBaselineCodesAcrossTheTransportErrorTable()throws Exception {
        InputStream resource=getClass().getResourceAsStream("/transport-error-cases.json");
        assertNotNull("transport error table",resource);
        JSONArray cases;
        try(Scanner reader=new Scanner(resource,"UTF-8")){
            cases=new JSONArray(reader.useDelimiter("\\A").next());
        }
        LinkedBlockingQueue<String> results=new LinkedBlockingQueue<>();
        try(PodServices host=new PodServices(ApplicationProvider.getApplicationContext(),results::add)){
            host.attachByteBridge(new PodServices.ByteBridge(){
                public byte[] take(int id){throw new AssertionError("unexpected input attachment");}
                public int put(byte[] bytes){throw new AssertionError("unexpected output attachment");}
                public void drop(int id){throw new AssertionError("unexpected dropped attachment");}
            });
            PodByteStreamServices streams=ReflectionHelpers.getField(host,"byteStreams");
            for(int i=0;i<cases.length();i++){
                JSONObject row=cases.getJSONObject(i);
                if(row.isNull("exception"))continue; // Guest and WebView codes do not pass through the Java exception catch.
                String name=row.getString("name"),code=row.getString("baselineCode");
                String message=row.isNull("message")?null:row.getString("message");
                Exception failure=Class.forName(row.getString("exception")).asSubclass(Exception.class)
                        .getConstructor(String.class).newInstance(message);
                String id=streams.attach(new InputStream(){
                    @Override public int read()throws IOException{
                        if(failure instanceof IOException)throw (IOException)failure;
                        throw (RuntimeException)failure;
                    }
                },new ByteArrayOutputStream(),()->{});
                int requestId=i+1;
                host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",requestId)
                        .put("method","stream.read.bytes").put("args",args(id,1)));

                long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(results.isEmpty()&&System.nanoTime()<expires){
                    shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(1);
                }
                String line=results.poll();
                assertNotNull(name,line);
                JSONObject reply=new JSONObject(line);
                assertEquals(name,"service.result",reply.getString("t"));
                assertEquals(name,requestId,reply.getInt("id"));
                assertFalse(name,reply.getBoolean("ok"));
                assertEquals(name,code,reply.getString("code"));
                assertEquals(name,message==null?failure.getClass().getSimpleName():message,reply.getString("message"));
                assertEquals(name,!code.equals(row.getString("r15Code")),reply.optBoolean("nativeTransportFailure"));
            }
        }
    }
    private static JSONObject args(String id,int max)throws Exception{return new JSONObject().put("id",id).put("maxBytes",max).put("timeoutMs",1000);}
}
