package dev.podjs.runtime;
import org.json.JSONObject;
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
        server.createContext("/stall",exchange->{try{Thread.sleep(1500);exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{exchange.close();}});
        server.start();String base="http://"+server.getAddress().getHostString()+":"+server.getAddress().getPort();
        try(PodHttpServices http=new PodHttpServices(new android.content.Context())){
            JSONObject out=http.execute("http.download",new JSONObject().put("url",base+"/upload").put("path","response").put("temporary",true).put("method","POST").put("bodyPath","input").put("maxBytes",100));
            if(out.getInt("status")!=200||out.getLong("size")!=2)throw new AssertionError("bad result");
            expectFailure(http,new JSONObject().put("url",base+"/large").put("path","oversize").put("maxBytes",100),"download_too_large");
            expectFailure(http,new JSONObject().put("url",base+"/redirect").put("path","redirect").put("followRedirects",false),"redirect_disabled");
            expectFailure(http,new JSONObject().put("url",base+"/upload").put("path","escape").put("method","POST").put("bodyPath","../secret"),"path_outside_root");
            long start=System.nanoTime();expectFailure(http,new JSONObject().put("url",base+"/stall").put("path","stall").put("callTimeoutMs",80),null);
            if(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>1000)throw new AssertionError("deadline did not disconnect");
            if(Arrays.stream(root.list()).anyMatch(p->p.contains(".download-")||p.equals("oversize")||p.equals("stall")))throw new AssertionError("leaked partial output");
        }finally{server.stop(0);executor.shutdownNow();for(File file:root.listFiles())file.delete();root.delete();}
        System.out.println("PASS: file POST 4 MiB, bounded response, redirect refusal, path confinement, call deadline, temporary cleanup (loopback only)");
    }
    private static void expectFailure(PodHttpServices http,JSONObject args,String message)throws Exception{
        try{http.execute("http.download",args);throw new AssertionError("expected failure");}catch(IOException|IllegalArgumentException|SecurityException expected){if(message!=null&&!expected.toString().contains(message))throw expected;}
    }
}
