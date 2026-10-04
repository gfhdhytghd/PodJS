package dev.podjs.runtime;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.CancellationSignal;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public final class PodByteStreamServicesTest {
    static final class Pair implements AutoCloseable {
        final Socket client,server;
        Pair()throws Exception{try(ServerSocket listener=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){client=new Socket(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort());server=listener.accept();}client.setSoTimeout(2000);server.setSoTimeout(2000);}
        public void close()throws Exception{client.close();server.close();}
    }
    private PodByteStreamServices service(){return new PodByteStreamServices(InstrumentationRegistry.getInstrumentation().getTargetContext());}
    @Test public void boundedReadWriteAndEof()throws Exception{
        try(PodByteStreamServices s=service();Pair pair=new Pair()){
            String id=s.attach(pair.server.getInputStream(),pair.server.getOutputStream(),pair.server);
            pair.client.getOutputStream().write(new byte[]{1,2,3,4});
            JSONObject read=s.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",2).put("timeoutMs",1000),new CancellationSignal());assertArrayEquals(new byte[]{1,2},Base64.decode(read.getString("dataBase64"),Base64.DEFAULT));assertFalse(read.getBoolean("eof"));
            read=s.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",8).put("timeoutMs",1000),new CancellationSignal());assertArrayEquals(new byte[]{3,4},Base64.decode(read.getString("dataBase64"),Base64.DEFAULT));
            JSONObject write=s.execute("stream.write",new JSONObject().put("id",id).put("dataBase64","BQY=").put("timeoutMs",1000),new CancellationSignal());assertEquals(2,write.getInt("written"));assertEquals(5,pair.client.getInputStream().read());assertEquals(6,pair.client.getInputStream().read());
            pair.client.shutdownOutput();assertTrue(s.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",8).put("timeoutMs",1000),new CancellationSignal()).getBoolean("eof"));assertTrue(pair.server.isClosed());
        }
    }
    @Test public void timeoutClosesBlockedReaderAndOwner()throws Exception{
        try(PodByteStreamServices s=service();Pair pair=new Pair()){
            String id=s.attach(pair.server.getInputStream(),pair.server.getOutputStream(),pair.server);long start=android.os.SystemClock.elapsedRealtime();
            try{s.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",8).put("timeoutMs",40),new CancellationSignal());fail("Expected timeout");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("timed out"));}
            assertTrue(android.os.SystemClock.elapsedRealtime()-start<1000);assertTrue(pair.server.isClosed());assertEquals(-1,pair.client.getInputStream().read());
        }
    }
    @Test public void cancellationAndServiceCloseUnblockOutstandingIo()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{for(boolean cancel:new boolean[]{true,false})try(PodByteStreamServices s=service();Pair pair=new Pair()){
            CountDownLatch started=new CountDownLatch(1);java.io.InputStream input=new java.io.FilterInputStream(pair.server.getInputStream()){@Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{started.countDown();return super.read(bytes,offset,length);}};String id=s.attach(input,pair.server.getOutputStream(),pair.server);CancellationSignal signal=new CancellationSignal();
            Future<?> pending=worker.submit(()->{try{s.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",8).put("timeoutMs",5000),signal);throw new AssertionError("Expected closed read");}catch(Exception expected){}});
            assertTrue(started.await(1,TimeUnit.SECONDS));if(cancel)signal.cancel();else s.close();pending.get(1,TimeUnit.SECONDS);assertTrue(pair.server.isClosed());
        }}finally{worker.shutdownNow();}
    }
    @Test public void refusedOversizedWriteClosesItsHandle()throws Exception{
        try(PodByteStreamServices s=service();Pair pair=new Pair()){
            String id=s.attach(pair.server.getInputStream(),pair.server.getOutputStream(),pair.server);String encoded=Base64.encodeToString(new byte[65537],Base64.NO_WRAP);
            try{s.execute("stream.write",new JSONObject().put("id",id).put("dataBase64",encoded).put("timeoutMs",1000),new CancellationSignal());fail("Expected bound rejection");}catch(IllegalArgumentException expected){}
            assertTrue(pair.server.isClosed());
        }
    }
    @Test public void nativeRfcommListenerLifecycleWhenAvailable()throws Exception{
        try(PodByteStreamServices s=service()){
            JSONObject status=s.execute("rfcomm.status",new JSONObject(),new CancellationSignal());
            android.util.Log.i("PodByteStreamTest","rfcomm status="+status.toString());
            org.junit.Assume.assumeTrue(status.getBoolean("enabled"));
            JSONObject listener=s.execute("rfcomm.listen",new JSONObject().put("name","PodJS Stream Verification").put("uuid",java.util.UUID.randomUUID().toString()),new CancellationSignal());
            String id=listener.getString("id");
            assertTrue(s.execute("rfcomm.close",new JSONObject().put("id",id),new CancellationSignal()).getBoolean("closed"));
            assertTrue(s.execute("rfcomm.close",new JSONObject().put("id",id),new CancellationSignal()).getBoolean("closed"));
        }
    }
    @Test public void blockedStreamReadDoesNotBlockDatabaseDispatch()throws Exception{
        BlockingQueue<String> events=new LinkedBlockingQueue<>();
        try(PodServices host=new PodServices(InstrumentationRegistry.getInstrumentation().getTargetContext(),events::offer);Pair pair=new Pair()){
            java.lang.reflect.Field field=PodServices.class.getDeclaredField("byteStreams");field.setAccessible(true);PodByteStreamServices streams=(PodByteStreamServices)field.get(host);
            CountDownLatch reading=new CountDownLatch(1);java.io.InputStream input=new java.io.FilterInputStream(pair.server.getInputStream()){@Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{reading.countDown();return super.read(bytes,offset,length);}};
            String id=streams.attach(input,pair.server.getOutputStream(),pair.server);
            host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",1).put("method","stream.read").put("args",new JSONObject().put("id",id).put("maxBytes",8).put("timeoutMs",5000)));
            assertTrue(reading.await(1,TimeUnit.SECONDS));
            host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",2).put("method","sql.query").put("args",new JSONObject().put("sql","SELECT 7 AS n")));
            String response=events.poll(1,TimeUnit.SECONDS);assertNotNull(response);JSONObject value=new JSONObject(response);assertEquals(2,value.getInt("id"));assertTrue(value.getBoolean("ok"));assertEquals(7,value.getJSONArray("value").getJSONObject(0).getInt("n"));
            host.dispatch(new JSONObject().put("t","service.cancel").put("id",1));assertTrue(pair.server.isClosed());assertNull(events.poll(50,TimeUnit.MILLISECONDS));
        }
    }
}
