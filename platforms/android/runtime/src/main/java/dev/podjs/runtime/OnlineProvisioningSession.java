package dev.podjs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import org.json.JSONException;
import org.json.JSONObject;

/** Internet relay for the QR-authorized setup channel. The relay only carries the exact BLE
 * envelope {seq,n,c}; the E2E secret exists in this object's memory and the QR, never in a
 * request. The watch token never leaves memory. Nothing here is logged or persisted.
 * No monitor is held across HTTP or waits, so close() unblocks every caller immediately. */
public final class OnlineProvisioningSession implements ProvisioningSession {
    public static final String PROTOCOL="watchrss-online-setup-v1";
    static final String ORIGIN="https://sly-data-plane.watchrss.cn";
    static final String SESSIONS="/functions/v1/watch-pairing/sessions";
    static final long LIFETIME_MS=300000,POLL_MS=1500;
    static final int MAX_WIRE=16384,MAX_BODY=MAX_WIRE+1024;
    private static final Pattern TOKEN=Pattern.compile("[0-9a-fA-F]{64}");

    /** One immediate-reply exchange. abort() must unblock exchanges already in flight. */
    interface Http {Reply exchange(String method,String path,String token,byte[] body) throws IOException;void abort();}
    static final class Reply {final int status;final byte[] body;Reply(int status,byte[] body){this.status=status;this.body=body;}}
    interface Clock {long millis();}

    private final Http http;
    private final Clock clock;
    private final Executor closer;
    private final SecureRandom random=new SecureRandom();
    private final byte[] secret=new byte[32];
    private final String sessionId,deviceId,installId;
    private final long deadline;
    private final CountDownLatch closedSignal=new CountDownLatch(1);
    private volatile boolean closed;
    private String watchToken,claimToken;
    private long expiresAt;
    private int receiveSequence,awaiting=-1;
    private byte[] pendingResponse;

    public OnlineProvisioningSession(String deviceId,String installId){
        this(deviceId,installId,new UrlHttp(),()->System.nanoTime()/1000000L,task->{Thread thread=new Thread(task,"watchrss-pairing-close");thread.setDaemon(true);thread.start();});
    }
    OnlineProvisioningSession(String deviceId,String installId,Http http,Clock clock,Executor closer){
        if(deviceId==null||deviceId.isEmpty()||installId==null||installId.isEmpty())throw new IllegalArgumentException("Invalid setup identity");
        this.deviceId=deviceId;this.installId=installId;this.http=http;this.clock=clock;this.closer=closer;
        random.nextBytes(secret);byte[] id=new byte[16];random.nextBytes(id);
        sessionId=Base64.getUrlEncoder().withoutPadding().encodeToString(id);
        deadline=clock.millis()+LIFETIME_MS;
    }
    @Override public String sessionId(){return sessionId;}
    private void check(){
        if(!closed&&clock.millis()>=deadline){close();throw new Failure(Failure.EXPIRED,"二维码已过期，请重新开始");}
        if(closed)throw new Failure(Failure.CLOSED,"配对已结束，请在手表重新打开配对页面");
    }
    private Failure reject(){close();return new Failure(Failure.REJECTED,"配对未完成，请重新开始");}

    @Override public void start() throws Exception {
        synchronized(this){check();if(watchToken!=null)throw new IllegalStateException("配对已开始");}
        byte[] body=new JSONObject().put("sessionId",sessionId).put("deviceId",deviceId).put("installId",installId).toString().getBytes(StandardCharsets.UTF_8);
        Reply reply=send("POST",SESSIONS,null,body);
        if(reply==null){close();throw new Failure(Failure.OFFLINE,"手表暂时连不上网络");}
        JSONObject value=parse(reply);
        String watch=value.optString("watchToken"),claim=value.optString("claimToken");
        long expires=value.optLong("expiresAt",0);
        if(!sessionId.equals(value.optString("sessionId"))||!TOKEN.matcher(watch).matches()||!TOKEN.matcher(claim).matches()||expires<=0)throw reject();
        synchronized(this){check();watchToken=watch;claimToken=claim;expiresAt=expires;}
    }
    @Override public synchronized String qr() throws Exception {
        check();if(claimToken==null)throw new IllegalStateException("配对尚未开始");
        JSONObject descriptor=new JSONObject().put("protocol",PROTOCOL).put("transport",INTERNET).put("sessionId",sessionId)
            .put("secret",Base64.getEncoder().encodeToString(secret)).put("deviceId",deviceId).put("installId",installId)
            .put("claimToken",claimToken).put("expiresAt",expiresAt);
        return "wrss-pair:"+Base64.getUrlEncoder().withoutPadding().encodeToString(descriptor.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public JSONObject receive() throws Exception {
        final String token;final int expected;
        synchronized(this){check();if(watchToken==null||awaiting>=0)throw new IllegalStateException("配对响应次序错误");if(receiveSequence>15)throw reject();token=watchToken;expected=receiveSequence;}
        while(true){
            Reply reply=send("GET",SESSIONS+"/"+sessionId+"/request",token,null);
            if(reply!=null){
                Object wire=parse(reply).opt("wire");
                if(wire instanceof JSONObject){
                    int seq=((JSONObject)wire).optInt("seq",-1);
                    if(seq==expected){
                        JSONObject message=open((JSONObject)wire,expected);
                        synchronized(this){check();receiveSequence++;awaiting=expected;}
                        return message;
                    }
                    // The answered request may linger briefly after our response was accepted.
                    if(seq<0||seq!=expected-1)throw reject();
                }else if(wire!=null&&wire!=JSONObject.NULL)throw reject();
            }
            pause();
        }
    }
    private JSONObject open(JSONObject wire,int sequence) throws Exception {
        byte[] plain=null;
        try{
            if(wire.toString().length()>MAX_WIRE)throw new IllegalArgumentException();
            byte[] nonce=Base64.getDecoder().decode(wire.getString("n")),cipher=Base64.getDecoder().decode(wire.getString("c"));
            synchronized(this){check();plain=BleSetupCrypto.crypt(secret,sessionId,Cipher.DECRYPT_MODE,"phone",sequence,nonce,cipher);}
            JSONObject message=new JSONObject(new String(plain,StandardCharsets.UTF_8));
            message.put("_sequence",sequence);
            return message;
        }catch(Failure failure){throw failure;}
        catch(Exception error){check();throw reject();}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }

    @Override public void respond(int sequence,JSONObject result) throws Exception {
        final String token;final byte[] body;
        synchronized(this){
            check();if(awaiting<0||sequence!=awaiting)throw new IllegalStateException("配对响应次序错误");
            // Encrypt once: an uncertain acknowledgement is retried with the identical ciphertext.
            if(pendingResponse==null){
                byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] plain=result.toString().getBytes(StandardCharsets.UTF_8);
                try{
                    JSONObject wire=new JSONObject().put("seq",sequence).put("n",Base64.getEncoder().encodeToString(nonce))
                        .put("c",Base64.getEncoder().encodeToString(BleSetupCrypto.crypt(secret,sessionId,Cipher.ENCRYPT_MODE,"watch",sequence,nonce,plain)));
                    if(wire.toString().length()>MAX_WIRE)throw reject();
                    pendingResponse=new JSONObject().put("seq",sequence).put("wire",wire).toString().getBytes(StandardCharsets.UTF_8);
                }finally{Arrays.fill(plain,(byte)0);}
            }
            token=watchToken;body=pendingResponse;
        }
        while(true){
            Reply reply=send("POST",SESSIONS+"/"+sessionId+"/response",token,body);
            if(reply!=null){
                if(!parse(reply).optBoolean("accepted"))throw reject();
                synchronized(this){if(awaiting==sequence){awaiting=-1;pendingResponse=null;}}
                return;
            }
            pause();
        }
    }

    /** Returns null for transient failures; terminal answers close the session. */
    private Reply send(String method,String path,String token,byte[] body) throws Exception {
        check();
        Reply reply;
        try{reply=http.exchange(method,path,token,body);}
        catch(ProtocolException error){check();throw reject();}
        catch(IOException error){check();return null;}
        check();
        if(reply.status>=200&&reply.status<300)return reply;
        if(reply.status==429||reply.status==408||reply.status>=500)return null;
        if(reply.status==410){close();throw new Failure(Failure.EXPIRED,"二维码已过期，请重新开始");}
        throw reject();
    }
    private JSONObject parse(Reply reply){
        try{return new JSONObject(new String(reply.body,StandardCharsets.UTF_8));}catch(JSONException error){throw reject();}
    }
    private void pause() throws InterruptedException {
        long wait=Math.min(POLL_MS,Math.max(0,deadline-clock.millis()));
        closedSignal.await(wait,TimeUnit.MILLISECONDS);
        check();
    }

    @Override public void close(){
        final String token;
        synchronized(this){
            if(closed)return;closed=true;Arrays.fill(secret,(byte)0);
            token=watchToken;watchToken=null;claimToken=null;
            if(pendingResponse!=null)Arrays.fill(pendingResponse,(byte)0);pendingResponse=null;
        }
        closedSignal.countDown();
        http.abort();
        // Best effort only; the relay's TTL is the final cleanup when the network is down.
        if(token!=null)try{closer.execute(()->{try{http.exchange("POST",SESSIONS+"/"+sessionId+"/close",token,"{}".getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}});}catch(RuntimeException ignored){}
    }

    static final class UrlHttp implements Http {
        private final Set<HttpURLConnection> open=Collections.newSetFromMap(new ConcurrentHashMap<>());
        private boolean aborted;
        @Override public Reply exchange(String method,String path,String token,byte[] body) throws IOException {
            HttpURLConnection connection=(HttpURLConnection)new URL(ORIGIN+path).openConnection();
            synchronized(this){
                if(aborted&&!path.endsWith("/close")){connection.disconnect();throw new IOException("Pairing closed");}
                open.add(connection);
            }
            try{
                connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setRequestMethod(method);
                connection.setRequestProperty("Accept","application/json");
                if(token!=null)connection.setRequestProperty("x-watchrss-pairing-token",token);
                if(body!=null){
                    connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.length);
                    try(OutputStream out=connection.getOutputStream()){out.write(body);}
                }
                int status=connection.getResponseCode();
                if(status<200||status>=300)return new Reply(status,new byte[0]);
                try(InputStream in=connection.getInputStream()){return new Reply(status,readBounded(in));}
            }finally{open.remove(connection);connection.disconnect();}
        }
        @Override public void abort(){
            final HttpURLConnection[] active;
            synchronized(this){aborted=true;active=open.toArray(new HttpURLConnection[0]);}
            for(HttpURLConnection connection:active)connection.disconnect();
        }
        private static byte[] readBounded(InputStream in) throws IOException {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))>=0){if(out.size()+n>MAX_BODY)throw new ProtocolException("Reply too large");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
}
