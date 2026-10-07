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
        server.createContext("/upload",exchange->{byte[] received=exchange.getRequestBody().readAllBytes();if(!Arrays.equals(received,payload))throw new AssertionError("body mismatch");byte[] body="ok".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
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
        dualStackFallback(root,server.getAddress().getPort());
        }finally{server.stop(0);executor.shutdownNow();for(File file:root.listFiles())file.delete();root.delete();}
        System.out.println("PASS: file POST 4 MiB, bounded response, redirect refusal, path confinement, call deadline, charset, temporary cleanup, dual-stack fallback (loopback only)");
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
