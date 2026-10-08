package dev.podjs.runtime;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.SocketFactory;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Model TLS close_notify throwing on the lifecycle caller, with real pooled/in-flight sockets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodHttpServicesCloseTest {
    @Test public void idleSocketEvictionRunsOffLifecycleCaller()throws Exception {close(false);}
    @Test public void activeCallCancellationRunsOffLifecycleCaller()throws Exception {close(true);}
    private void close(boolean active)throws Exception {
        Context context=ApplicationProvider.getApplicationContext();Thread caller=Thread.currentThread();
        AtomicBoolean armed=new AtomicBoolean(),closedOnCaller=new AtomicBoolean();
        CountDownLatch socketClosed=new CountDownLatch(1),requestSeen=new CountDownLatch(1),releaseServer=new CountDownLatch(1);
        SocketFactory sockets=new SocketFactory() {
            public Socket createSocket(){return new Socket(){
                @Override public synchronized void close()throws IOException {
                    if(armed.get()) {
                        closedOnCaller.set(Thread.currentThread()==caller);socketClosed.countDown();
                        if(Thread.currentThread()==caller)throw new android.os.NetworkOnMainThreadException();
                    }
                    super.close();
                }
            };}
            public Socket createSocket(String host,int port)throws IOException{throw new AssertionError("unexpected factory overload");}
            public Socket createSocket(String host,int port,InetAddress local,int lp)throws IOException{throw new AssertionError("unexpected factory overload");}
            public Socket createSocket(InetAddress host,int port)throws IOException{throw new AssertionError("unexpected factory overload");}
            public Socket createSocket(InetAddress host,int port,InetAddress local,int lp)throws IOException{throw new AssertionError("unexpected factory overload");}
        };
        OkHttpClient client=new OkHttpClient.Builder().socketFactory(sockets).build();
        PodHttpServices services=new PodHttpServices(context,client);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Future<?> served=workers.submit(()-> {
                try(Socket peer=server.accept()) {
                    BufferedReader reader=new BufferedReader(new InputStreamReader(peer.getInputStream()));
                    String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                    requestSeen.countDown();
                    if(!active){peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(java.nio.charset.StandardCharsets.US_ASCII));peer.getOutputStream().flush();}
                    if(!releaseServer.await(5,TimeUnit.SECONDS))throw new AssertionError("server cleanup timed out");
                }catch(Exception error){throw new RuntimeException(error);}
            });
            Future<?> request=workers.submit(()-> {
                try {services.execute("http.download",new JSONObject().put("url","http://127.0.0.1:"+server.getLocalPort()+"/").put("path","close-test"));if(active)throw new AssertionError("cancelled download completed");}
                catch(Exception error){if(!active)throw new RuntimeException(error);}
            });
            assertTrue(requestSeen.await(5,TimeUnit.SECONDS));
            if(!active)request.get(5,TimeUnit.SECONDS);
            armed.set(true);services.close();services.close();
            try{services.execute("http.download",new JSONObject());fail("closed publication gate");}
            catch(IllegalStateException expected){assertEquals("service_closed",expected.getMessage());}
            assertTrue(socketClosed.await(5,TimeUnit.SECONDS));assertFalse(closedOnCaller.get());
            request.get(5,TimeUnit.SECONDS);
            assertTrue(client.dispatcher().executorService().awaitTermination(5,TimeUnit.SECONDS));
            if(active)assertFalse(new File(PodTakeover.filesRoot(context),"close-test").exists());
            releaseServer.countDown();served.get(5,TimeUnit.SECONDS);
        }finally {
            services.close();releaseServer.countDown();workers.shutdownNow();
            new File(PodTakeover.filesRoot(context),"close-test").delete();
        }
    }
}
