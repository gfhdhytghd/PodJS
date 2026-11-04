package dev.podjs.runtime;
import org.json.JSONObject;
import okhttp3.*;
import javax.net.SocketFactory;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local loopback only. Executes the production file HTTP transport on the JVM. */
public final class CloudHttpTest {
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("pod-cloud-http").toFile();PodTakeover.root=root;
        byte[] payload=new byte[4*1024*1024+33];new Random(42).nextBytes(payload);
        File input=new File(root,"input");Files.write(input.toPath(),payload);
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        ExecutorService executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        java.util.concurrent.atomic.AtomicReference<String> receivedMethod=new java.util.concurrent.atomic.AtomicReference<>(),receivedEpoch=new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/upload",exchange->{receivedMethod.set(exchange.getRequestMethod());receivedEpoch.set(exchange.getRequestHeaders().getFirst("x-watchrss-library-epoch"));byte[] received=exchange.getRequestBody().readAllBytes();if(!Arrays.equals(received,payload))throw new AssertionError("body mismatch");byte[] body="ok".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/small-put",exchange->{byte[] received=exchange.getRequestBody().readAllBytes();if(!"PUT".equals(exchange.getRequestMethod())||!Arrays.equals(received,new byte[]{1,2,3}))throw new AssertionError("small PUT mismatch");exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/empty-put",exchange->{if(!"PUT".equals(exchange.getRequestMethod())||exchange.getRequestBody().readAllBytes().length!=0)throw new AssertionError("empty PUT mismatch");exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/large",exchange->{byte[] body=new byte[2048];exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/never");exchange.sendResponseHeaders(302,-1);exchange.close();});
        java.util.concurrent.atomic.AtomicReference<String> forwarded=new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/foreign",exchange->{forwarded.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/foreign-redirect",exchange->{exchange.getResponseHeaders().add("Location","http://localhost:"+server.getAddress().getPort()+"/foreign");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/error",exchange->{byte[] body="error".getBytes();exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/stall",exchange->{try{Thread.sleep(1500);exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{exchange.close();}});
        server.createContext("/text",exchange->{byte[] body="正文".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);exchange.getResponseHeaders().add("Content-Type","text/plain; charset=UTF-16LE");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();String base="http://"+server.getAddress().getHostString()+":"+server.getAddress().getPort();
        try {
        try(PodHttpServices http=new PodHttpServices(new android.content.Context())){
            JSONObject out=http.execute("http.download",new JSONObject().put("url",base+"/upload").put("path","response").put("temporary",true).put("method","POST").put("bodyPath","input").put("maxBytes",100));
            if(!"POST".equals(receivedMethod.get()))throw new AssertionError("POST method changed");
            JSONObject put=http.execute("http.download",new JSONObject().put("url",base+"/upload").put("path","put-response").put("temporary",true).put("method","PUT").put("bodyPath","input").put("maxBytes",100).put("headers",new JSONObject().put("x-watchrss-library-epoch","synthetic-epoch")));
            if(put.getInt("status")!=200||put.getLong("size")!=2||!"PUT".equals(receivedMethod.get())||!"synthetic-epoch".equals(receivedEpoch.get()))throw new AssertionError("PUT body/method/epoch changed");
            http.execute("http.download",new JSONObject().put("url",base+"/small-put").put("path","small-put-response").put("method","PUT").put("bodyBase64","AQID"));
            http.execute("http.download",new JSONObject().put("url",base+"/empty-put").put("path","empty-put-response").put("method","PUT"));
            expectFailure(http,new JSONObject().put("url",base+"/upload").put("path","put-escape").put("method","PUT").put("bodyPath","../secret"),"path_outside_root");
            expectFailure(http,new JSONObject().put("url",base+"/foreign-redirect").put("path","put-redirect").put("method","PUT").put("bodyPath","input"),"cross_origin_post_redirect");
            expectFailure(http,new JSONObject().put("url",base+"/upload").put("path","get-body").put("method","GET").put("bodyPath","input"),"unexpected_request_body");
            JSONObject text=http.execute("http.download",new JSONObject().put("url",base+"/text").put("path","text").put("text",true));if(!Files.readString(new File(root,"text").toPath()).equals("正文"))throw new AssertionError("text charset changed");
            if(out.getInt("status")!=200||out.getLong("size")!=2)throw new AssertionError("bad result");
            expectFailure(http,new JSONObject().put("url",base+"/large").put("path","oversize").put("maxBytes",100),"download_too_large");
            expectFailure(http,new JSONObject().put("url",base+"/redirect").put("path","redirect").put("followRedirects",false),"redirect_disabled");
            expectFailure(http,new JSONObject().put("url",base+"/upload").put("path","escape").put("method","POST").put("bodyPath","../secret"),"path_outside_root");
            http.execute("http.download",new JSONObject().put("url",base+"/foreign-redirect").put("path","foreign").put("headers",new JSONObject().put("Authorization","fixture-secret")));
            if(forwarded.get()!=null)throw new AssertionError("cross-origin authorization leaked");
            expectFailure(http,new JSONObject().put("url",base+"/foreign-redirect").put("path","post-redirect").put("method","POST"),"cross_origin_post_redirect");
            JSONObject accepted=http.execute("http.download",new JSONObject().put("url",base+"/error").put("path","accepted").put("acceptHttpErrors",true));
            if(accepted.getInt("status")!=500||!Files.readString(new File(root,"accepted").toPath()).equals("error"))throw new AssertionError("accepted HTTP error body changed");
            long start=System.nanoTime();expectFailure(http,new JSONObject().put("url",base+"/stall").put("path","stall").put("callTimeoutMs",80),null);
            if(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>1000)throw new AssertionError("deadline did not disconnect");
            if(Arrays.stream(root.list()).anyMatch(p->p.contains(".download-")||p.equals("oversize")||p.equals("stall")))throw new AssertionError("leaked partial output");
        }
        closeOffCallerThread(root,base);
        dualStackFallback(root,server.getAddress().getPort());
        }finally{server.stop(0);executor.shutdownNow();for(File file:root.listFiles())file.delete();root.delete();}
        System.out.println("PASS: file POST/PUT 4 MiB, PUT base64/empty/epoch, PUT redirect refusal and path confinement, bounded response, redirect refusal, path confinement, call deadline, charset, temporary cleanup, off-caller idle socket close, dual-stack fallback (loopback only)");
    }
    private static void closeOffCallerThread(File root,String base)throws Exception{
        Thread caller=Thread.currentThread();
        java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(),closedOnCaller=new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch socketClosed=new CountDownLatch(1);
        SocketFactory sockets=new SocketFactory(){
            public Socket createSocket(){return new Socket(){
                @Override public synchronized void close()throws IOException{
                    if(armed.get()) {closedOnCaller.set(Thread.currentThread()==caller);socketClosed.countDown();}
                    super.close();
                }
            };}
            public Socket createSocket(String h,int p)throws IOException{return new Socket(h,p);}
            public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return new Socket(h,p,l,lp);}
            public Socket createSocket(InetAddress h,int p)throws IOException{return new Socket(h,p);}
            public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return new Socket(h,p,l,lp);}
        };
        OkHttpClient client=new OkHttpClient.Builder().socketFactory(sockets).build();
        PodHttpServices http=new PodHttpServices(new android.content.Context(),client);
        http.execute("http.download",new JSONObject().put("url",base+"/text").put("path","close-thread").put("text",true));
        if(client.connectionPool().idleConnectionCount()==0)throw new AssertionError("fixture must retain a real idle connection");
        armed.set(true);http.close();http.close();
        if(!socketClosed.await(5,TimeUnit.SECONDS))throw new AssertionError("idle connection not released");
        if(closedOnCaller.get())throw new AssertionError("HTTP pool eviction performs socket I/O on lifecycle caller");
        if(!client.dispatcher().executorService().awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("HTTP cleanup executor leaked");
    }
    private static void dualStackFallback(File root,int port)throws Exception{
        java.util.concurrent.atomic.AtomicInteger ipv6Attempts=new java.util.concurrent.atomic.AtomicInteger(),closedAttempts=new java.util.concurrent.atomic.AtomicInteger();
        SocketFactory sockets=new SocketFactory(){
            public Socket createSocket(){return new Socket(){
                final CountDownLatch closed=new CountDownLatch(1);boolean stalled;
                @Override public void connect(SocketAddress endpoint,int timeout)throws IOException{
                    if(((InetSocketAddress)endpoint).getAddress() instanceof Inet6Address){
                        stalled=true;ipv6Attempts.incrementAndGet();
                        try{closed.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                        throw new SocketTimeoutException("synthetic broken IPv6 route");
                    }
                    super.connect(endpoint,timeout);
                }
                @Override public synchronized void close()throws IOException{if(stalled)closedAttempts.incrementAndGet();closed.countDown();super.close();}
            };}
            public Socket createSocket(String h,int p)throws IOException{return new Socket(h,p);}
            public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return new Socket(h,p,l,lp);}
            public Socket createSocket(InetAddress h,int p)throws IOException{return new Socket(h,p);}
            public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return new Socket(h,p,l,lp);}
        };
        OkHttpClient client=new OkHttpClient.Builder().fastFallback(true).followRedirects(false).followSslRedirects(false)
            .dns(host->Arrays.asList(InetAddress.getByName("2001:db8::1"),InetAddress.getByName("127.0.0.1"))).socketFactory(sockets).build();
        try(PodHttpServices http=new PodHttpServices(new android.content.Context(),client)){
            long began=System.nanoTime();JSONObject out=http.execute("http.download",new JSONObject().put("url","http://dual-stack.invalid:"+port+"/text").put("path","dual-stack").put("text",true));
            if(out.getInt("status")!=200||!Files.readString(new File(root,"dual-stack").toPath()).equals("正文"))throw new AssertionError("fallback body lost");
            if(ipv6Attempts.get()!=1||closedAttempts.get()<1||TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)>2000)throw new AssertionError("IPv4 waited for stalled IPv6 or losing socket leaked");
        }
    }
    private static void expectFailure(PodHttpServices http,JSONObject args,String message)throws Exception{
        try{http.execute("http.download",args);throw new AssertionError("expected failure");}catch(IOException|IllegalArgumentException|SecurityException expected){if(message!=null&&!expected.toString().contains(message))throw expected;}
    }
}
