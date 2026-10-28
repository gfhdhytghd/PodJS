package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PodVideoHttpDataSourcesTest {
    @Test public void realMedia3RequestsPreserveSuppliedHeadersAndUseDataSpecRanges() throws Exception {
        ArrayBlockingQueue<Map<String,String>> received = new ArrayBlockingQueue<>(8);
        WireServer server = new WireServer(received);
        try {
            String url = "http://127.0.0.1:"+server.port()+"/media?signature=synthetic";
            Map<String,String> defaults = new HashMap<>();
            defaults.put("User-Agent","Mozilla/5.0");defaults.put("Referer","https://www.bilibili.com/");defaults.put("Cookie","synthetic-session");
            DefaultHttpDataSource.Factory factory = PodVideoHttpDataSources.create(defaults,null);
            open(factory,new DataSpec.Builder().setUri(Uri.parse(url)).build());
            Map<String,String> old = received.poll(5,TimeUnit.SECONDS);assertNotNull(old);
            assertEquals("Mozilla/5.0",old.get("user-agent"));assertEquals("https://www.bilibili.com/",old.get("referer"));
            assertEquals("synthetic-session",old.get("cookie"));assertNull(old.get("range"));assertEquals("identity",old.get("accept-encoding"));
            System.out.println("WRSS_MEDIA3_WIRE "+new JSONObject(old).toString());
            defaults.clear();defaults.put("User-Agent","Mozilla/5.0");defaults.put("Range","bytes=0-");
            factory = PodVideoHttpDataSources.create(defaults,null);
            open(factory,new DataSpec.Builder().setUri(Uri.parse(url)).build());
            Map<String,String> watch = received.poll(5,TimeUnit.SECONDS);assertNotNull(watch);
            assertEquals("bytes=0-",watch.get("range"));assertNull(watch.get("referer"));assertNull(watch.get("cookie"));
            System.out.println("WRSS_WATCH_CDN_WIRE "+new JSONObject(watch).toString());
            open(factory,new DataSpec.Builder().setUri(Uri.parse(url)).setPosition(7).setLength(2).build());
            Map<String,String> seek = received.poll(5,TimeUnit.SECONDS);
            assertEquals("bytes=7-8",seek.get("range"));assertEquals("Mozilla/5.0",seek.get("user-agent"));
        } finally { server.close(); }
    }
    private static final class WireServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread worker;
        private volatile boolean closed;
        private volatile Throwable failure;
        WireServer(ArrayBlockingQueue<Map<String,String>> received) throws Exception {
            socket = new ServerSocket();socket.bind(new InetSocketAddress("127.0.0.1",0));
            worker = new Thread(()->{
                while(!closed)try(Socket peer=socket.accept()){
                    peer.setSoTimeout(5000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.US_ASCII));
                    assertNotNull(input.readLine());Map<String,String> headers=new HashMap<>();
                    for(String line;(line=input.readLine())!=null&&!line.isEmpty();){int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).toLowerCase(java.util.Locale.ROOT),line.substring(colon+1).trim());}
                    received.add(headers);String range=headers.get("range");byte[] body=range==null?new byte[16]:new byte[2];
                    String head="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n";
                    if(range!=null)head+="Content-Range: "+(range.equals("bytes=7-8")?"bytes 7-8/16":"bytes 0-1/16")+"\r\n";
                    OutputStream output=peer.getOutputStream();output.write((head+"\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
                }catch(Throwable error){if(!closed)failure=error;}
            },"Media3WireFixture");worker.setDaemon(true);worker.start();
        }
        int port(){return socket.getLocalPort();}
        public void close() throws Exception {closed=true;socket.close();worker.join(5000);assertFalse(worker.isAlive());if(failure!=null)throw new AssertionError(failure);}
    }
    private static void open(DefaultHttpDataSource.Factory factory, DataSpec spec) throws Exception {
        DefaultHttpDataSource source = factory.createDataSource();
        try { source.open(spec);assertEquals(2,source.read(new byte[2],0,2)); }
        finally { source.close(); }
    }
}
