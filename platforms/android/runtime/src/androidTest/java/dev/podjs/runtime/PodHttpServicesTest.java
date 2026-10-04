package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PodHttpServicesTest {
    private final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private Thread server(ServerSocket socket, String response, CountDownLatch accepted, AtomicReference<String> request) {
        Thread thread = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); byte[] b = new byte[4096]; int n = client.getInputStream().read(b); if (request != null && n > 0) request.set(new String(b, 0, n, StandardCharsets.US_ASCII)); OutputStream out = client.getOutputStream(); out.write(response.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); } catch (Exception ignored) { } });
        thread.start(); return thread;
    }

    private String url(ServerSocket socket) { return "http://127.0.0.1:" + socket.getLocalPort(); }
    private File file(String name) { File root = new File(context.getFilesDir(), "podjs/files"); assertTrue(root.exists() || root.mkdirs()); return new File(root, name); }

    @Test public void errorResponsesAreOptInAndRetainStatusAndBody() throws Exception {
        for(boolean accept:new boolean[]{false,true})try(ServerSocket socket=new ServerSocket(0);PodHttpServices services=new PodHttpServices(context)){
            String body="{\"detail\":\"refresh required\"}";
            Thread worker=server(socket,"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: "+body.length()+"\r\n\r\n"+body,new CountDownLatch(1),null);
            File output=file("http-error-response.json");output.delete();
            try{
                JSONObject args=new JSONObject().put("url",url(socket)).put("path",output.getName()).put("acceptHttpErrors",accept).put("readTimeoutMs",600000).put("timeoutMs",15000);
                if(accept){JSONObject result=services.execute("http.download",args);assertEquals(401,result.getInt("status"));assertEquals(body,new String(Files.readAllBytes(output.toPath()),StandardCharsets.UTF_8));}
                else{try{services.execute("http.download",args);fail("default download accepted HTTP error");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("http_status:401"));}assertFalse(output.exists());}
            }finally{output.delete();worker.join(1000);}
        }
    }

    @Test public void errorDownloadsRemainBoundedAndReadTimeoutIsValidated() throws Exception {
        try(ServerSocket socket=new ServerSocket(0);PodHttpServices services=new PodHttpServices(context)){
            Thread worker=server(socket,"HTTP/1.1 402 Payment Required\r\nContent-Length: 8\r\n\r\n12345678",new CountDownLatch(1),null);
            File output=file("http-error-bound.json");output.delete();
            try{services.execute("http.download",new JSONObject().put("url",url(socket)).put("path",output.getName()).put("acceptHttpErrors",true).put("maxBytes",4));fail("unbounded error response");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("download_too_large"));}finally{worker.join(1000);assertFalse(output.exists());}
            for(int timeout:new int[]{0,600001})try{services.execute("http.download",new JSONObject().put("url",url(socket)).put("path",output.getName()).put("readTimeoutMs",timeout));fail("invalid read timeout accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("invalid_read_timeout_ms"));}
        }
    }

    @Test public void streamsPayloadLargerThanLegacyChunkLimit() throws Exception {
        byte[] payload = new byte[320 * 1024]; for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        final ServerSocket socket = new ServerSocket(0); final CountDownLatch accepted = new CountDownLatch(1);
        Thread binaryServer = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); client.getInputStream().read(new byte[1024]); OutputStream out = client.getOutputStream(); out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(payload); out.flush(); } catch (Exception ignored) { } }); binaryServer.start();
        PodHttpServices services = new PodHttpServices(context); File target = file("http-large.bin");
        services.execute("http.download", new JSONObject().put("url", url(socket)).put("path", "http-large.bin"));
        assertArrayEquals(payload, Files.readAllBytes(target.toPath())); services.close(); socket.close();
    }

    @Test public void temporaryDownloadsAreDiscardedOrReleasedOnClose() throws Exception {
        PodHttpServices services=new PodHttpServices(context);
        File[] files={file("http-temporary-a.bin"),file("http-temporary-b.bin"),file("http-persistent.bin")};
        for(File file:files)file.delete();
        try {
            for(int i=0;i<files.length;i++)try(ServerSocket socket=new ServerSocket(0)){
                Thread worker=server(socket,"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",new CountDownLatch(1),null);
                JSONObject result=services.execute("http.download",new JSONObject().put("url",url(socket)).put("path",files[i].getName()).put("temporary",i<2));
                assertTrue(files[i].exists());if(i==0){services.discard(result);assertFalse(files[i].exists());}if(i==2){services.discard(result);assertTrue(files[i].exists());}worker.join(1000);
            }
            services.close();assertFalse(files[1].exists());assertTrue(files[2].exists());
        }finally{services.close();for(File file:files)file.delete();}
    }

    @Test public void textDownloadsHonorCharsetBomAndUtf8OutputLimit() throws Exception {
        String html="<article>中文原文 😀</article>";
        for(String encoding:new String[]{"GB18030","UTF-16","UTF-8"}){
            byte[] payload=html.getBytes(java.nio.charset.Charset.forName(encoding));
            try(ServerSocket socket=new ServerSocket(0);PodHttpServices services=new PodHttpServices(context)){
                String declared=encoding.equals("UTF-16")?"ISO-8859-1":encoding;
                Thread worker=server(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=\""+declared+"\"\r\nContent-Length: "+payload.length+"\r\n\r\n"+new String(payload,StandardCharsets.ISO_8859_1),new CountDownLatch(1),null);
                File target=file("http-charset-"+encoding+".html");target.delete();
                JSONObject result=services.execute("http.download",new JSONObject().put("url",url(socket)).put("path",target.getName()).put("text",true).put("temporary",true));
                assertEquals(html,new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));assertEquals(html.getBytes(StandardCharsets.UTF_8).length,result.getLong("size"));worker.join(1000);
            }
        }
        try(ServerSocket socket=new ServerSocket(0);PodHttpServices services=new PodHttpServices(context)){
            byte[] body="汉汉汉汉".getBytes(java.nio.charset.Charset.forName("GBK"));
            Thread worker=server(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=GBK\r\nContent-Length: "+body.length+"\r\n\r\n"+new String(body,StandardCharsets.ISO_8859_1),new CountDownLatch(1),null);
            File target=file("http-text-limit.html");Files.write(target.toPath(),new byte[]{42});
            try{services.execute("http.download",new JSONObject().put("url",url(socket)).put("path",target.getName()).put("text",true).put("maxBytes",8));fail("expanded UTF-8 exceeded limit");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("download_too_large"));}
            assertArrayEquals(new byte[]{42},Files.readAllBytes(target.toPath()));target.delete();worker.join(1000);
        }
    }

    @Test public void failedResponseLeavesExistingFile() throws Exception {
        File target = file("http-existing.bin"); byte[] old = "old-content".getBytes(StandardCharsets.UTF_8); Files.write(target.toPath(), old);
        ServerSocket socket = new ServerSocket(0); Thread thread = server(socket, "HTTP/1.1 500 Server Error\r\nContent-Length: 0\r\n\r\n", new CountDownLatch(1), null);
        try { new PodHttpServices(context).execute("http.download", new JSONObject().put("url", url(socket)).put("path", "http-existing.bin")); fail("500 accepted"); } catch (Exception expected) { }
        assertArrayEquals(old, Files.readAllBytes(target.toPath())); socket.close(); thread.join(1000);
    }

    @Test public void crossOriginRedirectDropsAuthorization() throws Exception {
        ServerSocket destination = new ServerSocket(0); CountDownLatch accepted = new CountDownLatch(1); AtomicReference<String> request = new AtomicReference<>();
        Thread destinationThread = server(destination, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", accepted, request);
        ServerSocket redirect = new ServerSocket(0); Thread redirectThread = server(redirect, "HTTP/1.1 302 Found\r\nLocation: " + url(destination) + "\r\nContent-Length: 0\r\n\r\n", new CountDownLatch(1), null);
        new PodHttpServices(context).execute("http.download", new JSONObject().put("url", url(redirect)).put("path", "http-redirect.bin").put("headers", new JSONObject().put("Authorization", "Bearer secret")));
        assertNotNull(request.get()); assertFalse(request.get().toLowerCase().contains("authorization:")); redirect.close(); destination.close(); redirectThread.join(1000); destinationThread.join(1000);
    }

    @Test public void cancelDisconnectsBlockedRequest() throws Exception {
        ServerSocket socket = new ServerSocket(0); CountDownLatch accepted = new CountDownLatch(1);
        Thread server = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); Thread.sleep(30_000); } catch (Exception ignored) { } }); server.start();
        PodHttpServices services = new PodHttpServices(context); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread request = new Thread(() -> { try { services.execute("http.download", new JSONObject().put("requestId", 41).put("url", url(socket)).put("path", "http-cancel.bin").put("timeoutMs", 120_000)); } catch (Throwable t) { error.set(t); } }); request.start();
        assertTrue(accepted.await(3, TimeUnit.SECONDS)); assertTrue(services.cancel(41)); request.join(3000); assertFalse(request.isAlive()); assertNotNull(error.get()); assertFalse(file("http-cancel.bin").exists()); services.close(); socket.close();
    }
}
