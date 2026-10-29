package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import org.json.JSONObject;

/** All relay traffic is an injected local fake; never opens UrlHttp. */
public final class ProvisioningTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    interface Throwing{void run()throws Exception;}
    static void failure(String reason,Throwing action)throws Exception{try{action.run();throw new AssertionError("accepted invalid operation");}catch(ProvisioningSession.Failure e){check(reason.equals(e.reason));}}
    static class Relay implements OnlineProvisioningSession.Http {
        String id;byte[] secret;int seq;Object sequenceAdvertised;boolean releasePolicy;boolean transientResponse,phoneClosed,offlineProbe;byte[] firstResponse;JSONObject received;
        final List<String> calls=new ArrayList<>();
        public OnlineProvisioningSession.Reply exchange(String method,String path,String token,byte[] body){
            calls.add(method+" "+path);
            try{
                if(path.equals(OnlineProvisioningSession.SESSIONS)){
                    JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));id=request.getString("sessionId");
                    check(!request.has("secret"));check(token==null);
                    return reply(200,new JSONObject().put("sessionId",id).put("watchToken","a".repeat(64)).put("claimToken","b".repeat(64)).put("expiresAt",100).put("messageSequenceMax",sequenceAdvertised).put("ownershipReleaseV1",releasePolicy));
                }
                check("a".repeat(64).equals(token));
                if(path.endsWith("/request")){
                    if(phoneClosed)return reply(410,new JSONObject());
                    if(offlineProbe)return reply(503,new JSONObject());
                    byte[] nonce=new byte[12];nonce[0]=(byte)(seq+1);
                    byte[] plain=new JSONObject().put("action","configure").put("payload",new JSONObject().put("value","private-fixture")).toString().getBytes(StandardCharsets.UTF_8);
                    return reply(200,new JSONObject().put("wire",new JSONObject().put("seq",seq).put("n",b64(nonce)).put("c",b64(BleSetupCrypto.crypt(secret,id,Cipher.ENCRYPT_MODE,"phone",seq,nonce,plain,sequenceAdvertised instanceof Number&&((Number)sequenceAdvertised).intValue()==255?255:15)))));
                }
                if(path.endsWith("/response")){
                    if(firstResponse==null)firstResponse=body.clone();else check(Arrays.equals(firstResponse,body));
                    if(transientResponse){transientResponse=false;return reply(503,new JSONObject());}
                    JSONObject wire=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONObject("wire");
                    received=new JSONObject(new String(BleSetupCrypto.crypt(secret,id,Cipher.DECRYPT_MODE,"watch",wire.getInt("seq"),Base64.getDecoder().decode(wire.getString("n")),Base64.getDecoder().decode(wire.getString("c")),sequenceAdvertised instanceof Number&&((Number)sequenceAdvertised).intValue()==255?255:15),StandardCharsets.UTF_8));
                    return reply(200,new JSONObject().put("accepted",true));
                }
                if(path.endsWith("/ownership/challenge")||path.endsWith("/ownership/release"))return reply(200,new JSONObject().put("ownershipReleased",true));
                check(path.endsWith("/close"));return reply(200,new JSONObject());
            }catch(Exception e){throw new AssertionError(e);}
        }
        public void abort(){}
    }
    static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
    static OnlineProvisioningSession.Reply reply(int status,JSONObject body){return new OnlineProvisioningSession.Reply(status,body.toString().getBytes(StandardCharsets.UTF_8));}
    static JSONObject qr(OnlineProvisioningSession s)throws Exception{return new JSONObject(new String(Base64.getUrlDecoder().decode(s.qr().substring("wrss-pair:".length())),StandardCharsets.UTF_8));}
    static OnlineProvisioningSession open(Relay relay,long[] clock)throws Exception{
        OnlineProvisioningSession s=new OnlineProvisioningSession("watch-fixture","install-fixture",relay,()->clock[0],Runnable::run);s.start();
        JSONObject descriptor=qr(s);check(descriptor.getString("protocol").equals(OnlineProvisioningSession.PROTOCOL));check(!descriptor.has("watchToken"));relay.secret=Base64.getDecoder().decode(descriptor.getString("secret"));return s;
    }
    static void crypto()throws Exception{
        byte[] key=new byte[32],nonce=new byte[12],plain="private-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] sealed=BleSetupCrypto.crypt(key,"session",Cipher.ENCRYPT_MODE,"phone",0,nonce,plain);
        check(Arrays.equals(plain,BleSetupCrypto.crypt(key,"session",Cipher.DECRYPT_MODE,"phone",0,nonce,sealed)));
        for(String direction:new String[]{"watch","phone"}){try{BleSetupCrypto.crypt(key,direction.equals("phone")?"other-session":"session",Cipher.DECRYPT_MODE,direction,0,nonce,sealed);throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){}}
        for(int sequence:new int[]{-1,16})try{BleSetupCrypto.crypt(key,"session",Cipher.ENCRYPT_MODE,"phone",sequence,nonce,plain);throw new AssertionError();}catch(IllegalArgumentException expected){}
        try{BleSetupCrypto.crypt(key,"session",Cipher.DECRYPT_MODE,"phone",1,nonce,sealed);throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){}
    }
    static void roundtrip()throws Exception{
        Relay relay=new Relay();OnlineProvisioningSession s=open(relay,new long[]{0});
        JSONObject message=s.receive();check(message.getInt("_sequence")==0);check(message.getString("action").equals("configure"));
        try{s.respond(1,new JSONObject());throw new AssertionError();}catch(IllegalStateException expected){}
        relay.transientResponse=true;s.respond(0,new JSONObject().put("bound",true));check(relay.received.getBoolean("bound"));
        check(relay.calls.stream().filter(v->v.endsWith("/response")).count()==2);relay.seq=1;check(s.receive().getInt("_sequence")==1);
        s.close();s.close();failure(ProvisioningSession.Failure.CLOSED,()->s.receive());
        java.lang.reflect.Field secret=OnlineProvisioningSession.class.getDeclaredField("secret");secret.setAccessible(true);check(Arrays.equals((byte[])secret.get(s),new byte[32]));
    }
    static void expiryAndRejectedWire()throws Exception{
        long[] clock={0};Relay relay=new Relay();OnlineProvisioningSession s=open(relay,clock);clock[0]=300000;failure(ProvisioningSession.Failure.EXPIRED,()->s.receive());
        Relay wrong=new Relay();OnlineProvisioningSession rejected=open(wrong,new long[]{0});wrong.seq=3;failure(ProvisioningSession.Failure.REJECTED,()->rejected.receive());
        Relay oversized=new Relay();OnlineProvisioningSession large=open(oversized,new long[]{0});large.receive();failure(ProvisioningSession.Failure.REJECTED,()->large.respond(0,new JSONObject().put("data","x".repeat(17000))));
    }
    static void pendingConfirmationLiveness()throws Exception{
        Relay relay=new Relay();OnlineProvisioningSession s=open(relay,new long[]{0});
        check(s.receive().getInt("_sequence")==0);
        s.checkActive();s.checkActive(); // No consume or sequence advance while configure is held.
        relay.offlineProbe=true;failure(ProvisioningSession.Failure.OFFLINE,s::checkActive);relay.offlineProbe=false;
        s.checkActive();s.respond(0,new JSONObject().put("bound",true));
        relay.seq=1;check(s.receive().getInt("_sequence")==1);
        relay.phoneClosed=true;failure(ProvisioningSession.Failure.EXPIRED,s::checkActive);
        failure(ProvisioningSession.Failure.CLOSED,()->s.respond(1,new JSONObject().put("bound",true)));
    }
    static void cancellation()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),aborted=new CountDownLatch(1);
        Relay relay=new Relay(){public OnlineProvisioningSession.Reply exchange(String method,String path,String token,byte[] body){
            if(path.endsWith("/request")){entered.countDown();try{check(aborted.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}return reply(200,new JSONObject());}return super.exchange(method,path,token,body);
        }public void abort(){aborted.countDown();}};
        OnlineProvisioningSession s=open(relay,new long[]{0});ExecutorService pool=Executors.newSingleThreadExecutor();
        try{Future<?> pending=pool.submit(()->{try{failure(ProvisioningSession.Failure.CLOSED,()->s.receive());}catch(Exception e){throw new RuntimeException(e);}});check(entered.await(2,TimeUnit.SECONDS));s.close();pending.get(2,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }
    static void negotiatedBudgetAndRpc()throws Exception {
        for(Object maximum:new Object[]{null,"255",256,Boolean.TRUE,255}) {
            Relay relay=new Relay();relay.sequenceAdvertised=maximum;relay.releasePolicy=true;OnlineProvisioningSession session=open(relay,new long[]{0});
            int expected=maximum instanceof Integer&&((Integer)maximum)==255?255:15;check(session.messageSequenceMax()==expected);check(session.ownershipReleaseSupported()==(expected==255));
            if(expected==255){
                for(String path:new String[]{"../close","/ownership/release","ownership/release?x=1","response"})try{session.sessionRequest(path,new JSONObject());throw new AssertionError("unsafe RPC");}catch(IllegalArgumentException good){}
                check(session.sessionRequest("ownership/challenge",new JSONObject().put("targetUserId","synthetic")).getInt("status")==200);
            }else try{session.sessionRequest("ownership/challenge",new JSONObject());throw new AssertionError("unnegotiated RPC");}catch(UnsupportedOperationException good){}
            for(int n=0;n<=expected;n++){relay.seq=n;relay.firstResponse=null;check(session.receive().getInt("_sequence")==n);session.respond(n,new JSONObject().put("success",true));}
            failure(ProvisioningSession.Failure.REJECTED,()->session.receive());
        }
    }
    static void sharedWireVectors()throws Exception {
        JSONObject vectors=new JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of("tests/fixtures/pairing-ux-wire-v1.json")));
        JSONObject descriptor=new JSONObject(new String(Base64.getUrlDecoder().decode(vectors.getString("qr").substring(10)),StandardCharsets.UTF_8));
        byte[] secret=Base64.getDecoder().decode(descriptor.getString("secret"));String id=descriptor.getString("sessionId");
        org.json.JSONArray cases=vectors.getJSONArray("vectors");
        for(int i=0;i<cases.length();i++){
            JSONObject v=cases.getJSONObject(i),wire=v.getJSONObject("wire");byte[] nonce=Base64.getDecoder().decode(wire.getString("n")),plain=v.getString("plaintext").getBytes(StandardCharsets.UTF_8),cipher=Base64.getDecoder().decode(wire.getString("c"));int seq=wire.getInt("seq");String direction=v.getString("direction");
            check(Arrays.equals(cipher,BleSetupCrypto.crypt(secret,id,Cipher.ENCRYPT_MODE,direction,seq,nonce,plain,255)));
            check(Arrays.equals(plain,BleSetupCrypto.crypt(secret,id,Cipher.DECRYPT_MODE,direction,seq,nonce,cipher,255)));
            if(seq>15)try{BleSetupCrypto.crypt(secret,id,Cipher.DECRYPT_MODE,direction,seq,nonce,cipher);throw new AssertionError("legacy accepted extended wire");}catch(IllegalArgumentException expected){}
            try{BleSetupCrypto.crypt(secret,id,Cipher.ENCRYPT_MODE,direction,256,nonce,plain,255);throw new AssertionError("256 accepted");}catch(IllegalArgumentException expected){}
        }
        System.out.println("PASS: phone shared wire vectors "+cases.length()+" directions/seq0,16,17,18,254,255 byte-exact; default15 and seq256 rejected");
    }
    public static void main(String[] args)throws Exception{crypto();sharedWireVectors();negotiatedBudgetAndRpc();roundtrip();expiryAndRejectedWire();pendingConfirmationLiveness();cancellation();System.out.println("PASS: provisioning crypto direction/session/sequence, QR isolation, relay retry, ownership, expiry, bounded wire, pending-confirmation liveness, cancellation and secret cleanup");}
}
