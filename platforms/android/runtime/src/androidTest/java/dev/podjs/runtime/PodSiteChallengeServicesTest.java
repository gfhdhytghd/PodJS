package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Local fixture only. Compile is not evidence that a device WebView ran it. */
public class PodSiteChallengeServicesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final class Reply implements PodSiteChallengeServices.Callback {
        final CountDownLatch done=new CountDownLatch(1);volatile boolean active=true;JSONObject value;String code;
        public boolean isActive(){return active;}
        public void complete(JSONObject value){this.value=value;done.countDown();}
        public void fail(String code,String message){this.code=code;done.countDown();}
        void await() throws Exception {assertTrue("No callback",done.await(15,TimeUnit.SECONDS));}
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0);final Thread worker;
        final String nonce=Long.toHexString(System.nanoTime());final AtomicInteger challenges=new AtomicInteger();
        final AtomicReference<Throwable> error=new AtomicReference<>();volatile boolean closed;
        Fixture() throws Exception {
            worker=new Thread(()->{while(!closed)try(Socket client=socket.accept()){
                client.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                String first=in.readLine(),cookie="";for(String line;(line=in.readLine())!=null&&!line.isEmpty();)if(line.toLowerCase().startsWith("cookie:"))cookie=line.substring(7).trim();
                boolean stalled=first.contains("/stall"),ready=cookie.contains("fixture_token="+nonce),gate=cookie.contains("fixture_gate="+nonce);
                String body,headers="";String type="text/html";
                if(!stalled&&(ready||gate)){
                    body="<rss><channel><title>Fixture</title></channel></rss>";type="application/rss+xml";
                    if(gate)headers="Set-Cookie: fixture_token="+nonce+"; Path=/; HttpOnly\r\nSet-Cookie: sessionid=unrelated-account; Path=/; HttpOnly\r\n";
                }else{
                    challenges.incrementAndGet();body="<!DOCTYPE html><html><script>"+(stalled?"":"setTimeout(function(){document.cookie='fixture_gate="+nonce+"; Path=/';location.reload()},50)")+"</script>Security check</html>";
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nCache-Control: no-store\r\n"+headers+"Content-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(bytes);
            }catch(Exception failure){if(!closed)error.compareAndSet(null,failure);}});worker.start();
        }
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
        JSONObject options(String path) throws Exception{return new JSONObject().put("url",url(path)).put("userAgent","PodJS fixture reader").put("cookieNames",new JSONArray().put("fixture_token")).put("timeoutMs",10000);}
        public void close() throws Exception {closed=true;socket.close();worker.join(5000);assertFalse(worker.isAlive());if(error.get()!=null)throw new AssertionError(error.get());}
    }
    @Test public void realHiddenWebViewGetsCookieThenOrdinaryHttpGetsRssAndCacheSurvivesServiceRecreation() throws Exception {
        try(Fixture fixture=new Fixture();PodSiteChallengeServices service=new PodSiteChallengeServices(context);PodHttpServices http=new PodHttpServices(context)){
            JSONObject options=fixture.options("/feed");Reply before=new Reply();service.dispatch(options,false,before);before.await();assertNull(before.code);
            Reply acquired=new Reply();service.dispatch(options,true,acquired);acquired.await();assertNull(acquired.code);assertNotNull(acquired.value);
            String cookie=acquired.value.getString("cookie");assertEquals("fixture_token="+fixture.nonce,cookie);assertFalse(cookie.contains("sessionid"));assertTrue(fixture.challenges.get()>0);
            JSONObject result=http.execute("http.download",new JSONObject().put("url",fixture.url("/feed")).put("path","site-challenge-fixture.xml").put("headers",new JSONObject().put("Cookie",cookie).put("User-Agent","PodJS fixture reader")));
            java.io.File root=PodTakeover.filesRoot(context);java.io.File output=new java.io.File(root,"site-challenge-fixture.xml");assertTrue(new String(Files.readAllBytes(output.toPath()),StandardCharsets.UTF_8).startsWith("<rss>"));assertEquals(200,result.getInt("status"));assertTrue(output.delete());
            try(PodSiteChallengeServices recreated=new PodSiteChallengeServices(context)){
                Reply cached=new Reply();recreated.dispatch(options,false,cached);cached.await();assertEquals(cookie,cached.value.getString("cookie"));
                Reply wrongUa=new Reply();recreated.dispatch(new JSONObject(options.toString()).put("userAgent","Another reader"),false,wrongUa);wrongUa.await();assertEquals("",wrongUa.value.getString("cookie"));
            }
        }
    }
    @Test public void stalledChallengeTimesOutAndCancellingQueuedRequestDoesNotCancelItsOwner() throws Exception {
        try(Fixture fixture=new Fixture();PodSiteChallengeServices service=new PodSiteChallengeServices(context)){
            Reply stalled=new Reply();service.dispatch(fixture.options("/stall").put("timeoutMs",250),true,stalled);
            Reply cancelled=new Reply();service.dispatch(fixture.options("/feed"),true,cancelled);cancelled.active=false;service.cancel(cancelled);
            stalled.await();assertEquals("challenge_timeout",stalled.code);assertEquals(1,cancelled.done.getCount());
        }
    }
    @Test public void rejectsNonPublicSchemesCredentialUrlsAndCrossOriginNavigation() throws Exception {
        try(PodSiteChallengeServices service=new PodSiteChallengeServices(context)){
            for(String url:new String[]{"file:///tmp/x","javascript:alert(1)","https://user:password@example.com/feed","http://example.com/feed"}){
                Reply reply=new Reply();service.dispatch(new JSONObject().put("url",url).put("userAgent","Reader").put("cookieNames",new JSONArray().put("token")),true,reply);reply.await();assertEquals("invalid_argument",reply.code);
            }
            PodSiteChallengeServices.Options options=new PodSiteChallengeServices.Options(new JSONObject().put("url","https://example.com/feed").put("userAgent","Reader").put("cookieNames",new JSONArray().put("token")));
            assertFalse(options.sameOrigin("https://other.example.com/feed"));assertFalse(options.sameOrigin("https://example.com:444/feed"));assertTrue(options.sameOrigin("https://example.com:443/next"));
        }
    }
}
