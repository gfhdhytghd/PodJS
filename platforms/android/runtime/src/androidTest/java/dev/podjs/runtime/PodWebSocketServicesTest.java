package dev.podjs.runtime;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.CancellationSignal;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

public final class PodWebSocketServicesTest {
    static final class Server implements AutoCloseable {
        final ServerSocket listener;Socket socket;DataInputStream input;DataOutputStream output;
        Server()throws Exception{this("127.0.0.1",0);}
        Server(String address,int port)throws Exception{listener=new ServerSocket(port,1,InetAddress.getByName(address));listener.setSoTimeout(3000);}
        void accept()throws Exception{socket=listener.accept();socket.setSoTimeout(3000);input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());ByteArrayOutputStream header=new ByteArrayOutputStream();while(!header.toString("US-ASCII").endsWith("\r\n\r\n")){assertTrue(header.size()<8192);header.write(input.readUnsignedByte());}String key=null;for(String line:header.toString("US-ASCII").split("\r\n"))if(line.toLowerCase().startsWith("sec-websocket-key:"))key=line.substring(line.indexOf(':')+1).trim();assertNotNull(key);String accept=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);output.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n");output.flush();}
        void send(int opcode,byte[] bytes)throws Exception{output.writeByte(0x80|opcode);if(bytes.length<126)output.writeByte(bytes.length);else if(bytes.length<=65535){output.writeByte(126);output.writeShort(bytes.length);}else{output.writeByte(127);output.writeLong(bytes.length);}output.write(bytes);output.flush();}
        byte[] read(int opcode)throws Exception{assertEquals(0x80|opcode,input.readUnsignedByte());int header=input.readUnsignedByte();assertTrue((header&128)!=0);int n=header&127;if(n==126)n=input.readUnsignedShort();else if(n==127){long size=input.readLong();assertTrue(size>=0&&size<=4*1024*1024);n=(int)size;}assertTrue(n<=4*1024*1024);byte[] mask=new byte[4],bytes=new byte[n];input.readFully(mask);input.readFully(bytes);for(int i=0;i<n;i++)bytes[i]^=mask[i%4];return bytes;}
        String url(){return "ws://127.0.0.1:"+listener.getLocalPort()+"/sync";}
        public void close()throws Exception{listener.close();if(socket!=null)socket.close();}
    }
    @Test public void textHandshakeAndBinaryFragmentationShareOwnedStream()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(Server server=new Server();PodByteStreamServices streams=new PodByteStreamServices(context);PodWebSocketServices ws=new PodWebSocketServices(context,streams)){
            Future<?> peer=worker.submit(()->{try{server.accept();assertEquals("hello",new String(server.read(1),StandardCharsets.UTF_8));server.send(1,"ack".getBytes(StandardCharsets.UTF_8));server.send(2,new byte[]{1,2});server.send(2,new byte[]{3,4});assertArrayEquals(new byte[]{5,6},server.read(2));}catch(Exception e){throw new RuntimeException(e);}});
            String id=ws.execute("websocket.connect",new JSONObject().put("url",server.url()).put("timeoutMs",2000),new CancellationSignal()).getString("id");ws.execute("websocket.writeText",new JSONObject().put("id",id).put("text","hello"),new CancellationSignal());assertEquals("ack",ws.execute("websocket.readText",new JSONObject().put("id",id).put("timeoutMs",2000),new CancellationSignal()).getString("text"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();while(bytes.size()<4){JSONObject r=streams.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",1).put("timeoutMs",2000),new CancellationSignal());bytes.write(Base64.decode(r.getString("dataBase64"),Base64.DEFAULT));}assertArrayEquals(new byte[]{1,2,3,4},bytes.toByteArray());
            streams.execute("stream.write",new JSONObject().put("id",id).put("dataBase64","BQY=").put("timeoutMs",2000),new CancellationSignal());peer.get(3,TimeUnit.SECONDS);streams.execute("stream.close",new JSONObject().put("id",id),new CancellationSignal());try{ws.execute("websocket.writeText",new JSONObject().put("id",id).put("text","closed"),new CancellationSignal());fail("closed handle");}catch(IllegalArgumentException expected){}
        }finally{worker.shutdownNow();}
    }
    @Test public void textTimeoutAndCancellationReleaseBothHandles()throws Exception{
        ExecutorService worker=Executors.newFixedThreadPool(2);android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try{for(boolean cancel:new boolean[]{false,true})try(Server server=new Server();PodByteStreamServices streams=new PodByteStreamServices(context);PodWebSocketServices ws=new PodWebSocketServices(context,streams)){
            Future<?> peer=worker.submit(()->{try{server.accept();assertEquals(-1,server.input.read());}catch(Exception e){throw new RuntimeException(e);}});
            String id=ws.execute("websocket.connect",new JSONObject().put("url",server.url()).put("timeoutMs",2000),new CancellationSignal()).getString("id");CancellationSignal signal=new CancellationSignal();CountDownLatch start=new CountDownLatch(1);
            Future<?> read=worker.submit(()->{start.countDown();try{ws.execute("websocket.readText",new JSONObject().put("id",id).put("timeoutMs",cancel?2000:30),signal);throw new AssertionError("must not read");}catch(Exception expected){}});assertTrue(start.await(1,TimeUnit.SECONDS));if(cancel){java.lang.reflect.Field mapField=PodWebSocketServices.class.getDeclaredField("connections");mapField.setAccessible(true);Object connection=((java.util.Map<?,?>)mapField.get(ws)).get(id);java.lang.reflect.Field busyField=connection.getClass().getDeclaredField("readingText");busyField.setAccessible(true);java.util.concurrent.atomic.AtomicBoolean busy=(java.util.concurrent.atomic.AtomicBoolean)busyField.get(connection);long until=android.os.SystemClock.elapsedRealtime()+1000;while(!busy.get()&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(1);assertTrue("Text read must be pending before cancellation",busy.get());signal.cancel();}read.get(2,TimeUnit.SECONDS);peer.get(2,TimeUnit.SECONDS);
            try{streams.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",1).put("timeoutMs",20),new CancellationSignal());fail("stream still exists");}catch(IllegalArgumentException expected){}
        }}finally{worker.shutdownNow();}
    }
    @Test public void firstTextHandshakeDropsEarlyBinaryAndIgnoresLaterText()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(Server server=new Server();PodByteStreamServices streams=new PodByteStreamServices(context);PodWebSocketServices ws=new PodWebSocketServices(context,streams)){
            Future<?> peer=worker.submit(()->{try{server.accept();server.send(2,new byte[4*1024*1024+1]);server.send(1,"signed-ack".getBytes(StandardCharsets.UTF_8));for(int i=0;i<12;i++)server.send(1,"ignored-after-ack".getBytes(StandardCharsets.UTF_8));server.send(2,new byte[]{7,8});assertArrayEquals(new byte[]{9},server.read(2));}catch(Exception e){throw new RuntimeException(e);}});
            String id=ws.execute("websocket.connect",new JSONObject().put("url",server.url()).put("timeoutMs",2000).put("initialTextHandshake",true),new CancellationSignal()).getString("id");
            assertEquals("signed-ack",ws.execute("websocket.readText",new JSONObject().put("id",id).put("timeoutMs",2000),new CancellationSignal()).getString("text"));
            JSONObject read=streams.execute("stream.read",new JSONObject().put("id",id).put("maxBytes",2).put("timeoutMs",2000),new CancellationSignal());assertArrayEquals(new byte[]{7,8},Base64.decode(read.getString("dataBase64"),Base64.DEFAULT));
            streams.execute("stream.write",new JSONObject().put("id",id).put("dataBase64","CQ==").put("timeoutMs",2000),new CancellationSignal());peer.get(3,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();}
    }
    @Test public void routePrefixMatchesNativeWifiSelection()throws Exception{
        assertTrue(PodWebSocketServices.contains(InetAddress.getByName("192.168.3.1"),24,InetAddress.getByName("192.168.3.2")));
        assertFalse(PodWebSocketServices.contains(InetAddress.getByName("192.168.3.1"),24,InetAddress.getByName("192.168.4.2")));
        assertTrue(PodWebSocketServices.contains(InetAddress.getByName("192.168.3.128"),25,InetAddress.getByName("192.168.3.255")));
        assertFalse(PodWebSocketServices.contains(InetAddress.getByName("192.168.3.128"),25,InetAddress.getByName("192.168.3.127")));
    }
}
