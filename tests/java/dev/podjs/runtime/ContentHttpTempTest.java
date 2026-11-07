package dev.podjs.runtime;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Production HTTP/pool, real child SIGKILL, synthetic loopback/files only. */
public final class ContentHttpTempTest {
    static final List<Process> children=new ArrayList<>();
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void wipe(Path root)throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    static long responseBytes(Path root)throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){return paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().endsWith(".lock")).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}}
    static Process child(String mode,Path root,String url)throws Exception{Process child=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-ea","-cp",System.getProperty("java.class.path"),ContentHttpTempTest.class.getName(),mode,root.toString(),url).start();children.add(child);return child;}
    static JSONObject ready(Process child)throws Exception{String line=new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();if(line==null)throw new AssertionError("child did not publish: "+new String(child.getErrorStream().readAllBytes()));return new JSONObject(line);}
    static void kill(Process child)throws Exception{child.destroyForcibly();check(child.waitFor(5,TimeUnit.SECONDS),"child termination");check(child.exitValue()==137,"expected actual SIGKILL");}
    static JSONObject request(String url){return new JSONObject().put("url",url).put("path","sync2-content-http/slot-0/response-00000000-0000-0000-0000-000000000000.data").put("managedTemp",true).put("temporary",true).put("method","GET").put("maxBytes",1048576).put("callTimeoutMs",5000);}
    public static void main(String[] args)throws Exception{
        if(args.length>0){
            Path root=Paths.get(args[1]);PodTakeover.root=root.toFile();
            if(args[0].equals("quota")){PodHttpTempFiles pool=new PodHttpTempFiles(root.toFile());try(PodHttpTempFiles.Lease lease=pool.acquire()){System.out.println("ACQUIRED");}catch(IOException expected){System.out.println(expected.getMessage());}return;}
            if(args[0].equals("response")){PodHttpServices http=new PodHttpServices(new Context());JSONObject out=http.execute("http.download",request(args[2]));System.out.println(out);System.out.flush();System.in.read();}
            else {PodHttpTempFiles pool=new PodHttpTempFiles(root.toFile());PodHttpTempFiles.Lease lease=pool.acquire();File part=new File(lease.file+".download-1");try(FileOutputStream out=new FileOutputStream(part)){out.write(new byte[1189]);out.getFD().sync();}System.out.println(new JSONObject().put("path",root.relativize(part.toPath()).toString()));System.out.flush();System.in.read();lease.close();}
            return;
        }
        Path root=Files.createTempDirectory(Paths.get(System.getenv().getOrDefault("TMPDIR",System.getProperty("java.io.tmpdir"))),"content-http-pool-");PodTakeover.root=root.toFile();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/reply",e->{byte[] data=new byte[1189];e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});
        server.createContext("/large",e->{try{byte[] data=new byte[1048577];e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}finally{e.close();}});server.start();String url="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            // This case also reproduces on the old HttpServices: it leaves 1189.
            Process response=child("response",root,url+"/reply");JSONObject published=ready(response);Path file=root.resolve(published.getString("path"));check(Files.size(file)==1189,"published response size");kill(response);
            try(PodHttpServices restarted=new PodHttpServices(new Context())){check(!Files.exists(file),"startup must reap SIGKILL response (1189 bytes)");check(responseBytes(root)==0,"startup response bytes");}
            System.out.println("PASS startup reaps 1189-byte response after real SIGKILL");

            Process partial=child("partial",root,url);Path part=root.resolve(ready(partial).getString("path"));check(Files.size(part)==1189,"partial response size");
            PodHttpTempFiles peer=new PodHttpTempFiles(root.toFile());check(Files.exists(part),"startup must preserve active foreign slot");List<PodHttpTempFiles.Lease> held=new ArrayList<>();
            try{for(int i=0;i<7;i++)held.add(peer.acquire());boolean quota=false;try{peer.acquire();}catch(IOException expected){quota=expected.getMessage().equals("http_temp_quota");}check(quota,"cross-process eight-slot quota");kill(partial);new PodHttpTempFiles(root.toFile());check(!Files.exists(part),"startup reaps partial after SIGKILL");try(PodHttpTempFiles.Lease reused=peer.acquire()){check(!reused.file.toPath().equals(part),"unique response identity");}}
            finally{if(partial.isAlive())kill(partial);for(PodHttpTempFiles.Lease lease:held)lease.close();}
            System.out.println("PASS partial SIGKILL, live foreign ownership, cross-process total quota and restart reuse");
            List<PodHttpTempFiles.Lease> local=new ArrayList<>();try{
                PodHttpTempFiles owner=new PodHttpTempFiles(root.toFile());for(int i=0;i<8;i++){PodHttpTempFiles.Lease lease=owner.acquire();local.add(lease);Files.write(lease.file.toPath(),new byte[1189]);}
                new PodHttpTempFiles(root.toFile());Process foreign=child("quota",root,url);String result=new BufferedReader(new InputStreamReader(foreign.getInputStream())).readLine();check(foreign.waitFor(5,TimeUnit.SECONDS)&&foreign.exitValue()==0,"foreign quota process");check("http_temp_quota".equals(result),"same-JVM startup must not release OS locks");for(PodHttpTempFiles.Lease lease:local)check(lease.file.length()==1189,"same-JVM startup must preserve all live responses");
            }finally{for(PodHttpTempFiles.Lease lease:local)lease.close();}
            System.out.println("PASS same-JVM startup preserves all eight process locks against a foreign allocator");

            PodHttpTempFiles pool=new PodHttpTempFiles(root.toFile());for(int i=0;i<100;i++)try(PodHttpTempFiles.Lease lease=pool.acquire()){Files.write(lease.file.toPath(),new byte[1189]);}
            check(responseBytes(root)==0,"repeated requests do not accumulate");check(PodHttpTempFiles.MAX_TOTAL_BYTES==8L*1024*1024,"reserved total bytes");
            try(PodHttpServices http=new PodHttpServices(new Context())){
                JSONObject out=http.execute("http.download",request(url+"/reply"));check(out.getLong("size")==1189,"managed read");check(http.execute("http.release",new JSONObject().put("path",out.getString("path"))).getBoolean("released"),"release owner");
                JSONObject newer=http.execute("http.download",request(url+"/reply"));
                check(!http.execute("http.release",new JSONObject().put("path",out.getString("path"))).getBoolean("released"),"late release is idempotent");
                check(Files.size(root.resolve(newer.getString("path")))==1189,"late release must preserve the new response");http.execute("http.release",new JSONObject().put("path",newer.getString("path")));
                boolean oversized=false;try{http.execute("http.download",request(url+"/large"));}catch(IOException expected){oversized=expected.getMessage().equals("download_too_large");}check(oversized,"single response bound");check(responseBytes(root)==0,"oversize reclaims its partial and slot");
                boolean badMaximum=false;try{http.execute("http.download",request(url+"/reply").put("maxBytes",1048577));}catch(IllegalArgumentException expected){badMaximum=true;}check(badMaximum,"native maximum cannot exceed reservation");
                JSONObject pending=http.execute("http.download",request(url+"/reply"));http.discard(pending);check(responseBytes(root)==0,"lost bridge reply cleanup");
                JSONObject last=http.execute("http.download",request(url+"/reply"));check(responseBytes(root)==1189,"pending response");
            }
            check(responseBytes(root)==0,"service close cleanup");
            Path legacy=root.resolve("sync2-content-http/.pod-write-legacy.part");Files.write(legacy,new byte[1189]);new PodHttpTempFiles(root.toFile());check(!Files.exists(legacy),"legacy orphan migration");
            System.out.println("PASS 1MiB response/8MiB reserved quota, release/reuse, invalid bound, overflow, lost reply, close and legacy orphan cleanup");
        }finally{for(Process child:children)if(child.isAlive())kill(child);server.stop(0);wipe(root);}
    }
}
