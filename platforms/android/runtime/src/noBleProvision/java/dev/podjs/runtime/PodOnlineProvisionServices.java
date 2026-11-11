package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import org.json.JSONObject;
import java.io.Closeable;

/** One foreground-only QR-authorized setup channel. Business state is owned by the app.
 * Internet-only build: legacy Bluetooth provisioning is excluded. */
final class PodBleProvisionServices implements Closeable {
    private final Context context;
    private ProvisioningSession session;
    private boolean closed,starting;
    private String sessionId,startOwner;
    PodBleProvisionServices(Context context){this.context=context;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation) throws Exception {
        if("provision.close".equals(method)){cancel(args.optString("sessionId"),args.optString("reason","error"));return new JSONObject().put("closed",true);}
        if("provision.start".equals(method)){
            if (!ProvisioningSession.INTERNET.equals(args.optString("transport",ProvisioningSession.INTERNET))) throw new IllegalArgumentException("仅支持联网配对");
            final ProvisioningSession opened;
            final String openedId;
            synchronized(this){
                cancellation.throwIfCanceled();
                if(closed||session!=null)throw new IllegalStateException("配对服务忙碌");
                opened=new OnlineProvisioningSession(args.getString("deviceId"),args.getString("installId"),args.optString("appVersion"));
                session=opened;openedId=opened.sessionId();sessionId=openedId;starting=true;startOwner=args.getString("requestId");
            }
            try{opened.start();JSONObject value=new JSONObject().put("qr",opened.qr()).put("sessionId",openedId).put("messageSequenceMax",opened.messageSequenceMax()).put("ownershipReleaseV1",opened.ownershipReleaseSupported()).put("transport",ProvisioningSession.INTERNET);synchronized(this){if(session==opened)starting=false;}return value;}
            catch(Exception e){synchronized(this){if(session==opened)closeSession();}throw e;}
        }
        final ProvisioningSession active;
        synchronized(this){if(closed||session==null||starting||!args.optString("sessionId").equals(sessionId))throw new IllegalStateException("配对未启动");active=session;}
        if("provision.sessionRequest".equals(method))return active.sessionRequest(args.getString("path"),args.getJSONObject("body"));
        if("provision.receive".equals(method))return active.receive();
        if("provision.check".equals(method)){active.checkActive();return new JSONObject().put("active",true);}
        if("provision.respond".equals(method)){active.respond(args.getInt("sequence"),args.getJSONObject("result"));return new JSONObject().put("sent",true);}
        throw new IllegalArgumentException("不支持的配对操作");
    }
    /** Called by the service cancellation listener on its calling thread, never queued. */
    void cancelOperation(String method,JSONObject args,String reason){
        if("provision.start".equals(method))cancelStart(args.optString("requestId"),reason);
        else cancel(args.optString("sessionId"),reason);
    }
    synchronized void cancel(String expected){cancel(expected,"error");}
    synchronized void cancel(String expected,String reason){if(expected.equals(sessionId))closeSession(reason);}
    /** The guest may cancel after start finished but before its result was delivered. */
    synchronized void cancelStart(String owner){cancelStart(owner,"error");}
    synchronized void cancelStart(String owner,String reason){if(owner.equals(startOwner))closeSession(reason);}
    synchronized void closeSession(){closeSession("error");}
    synchronized void closeSession(String reason){if(session!=null)session.close(reason);session=null;sessionId=null;startOwner=null;starting=false;}
    public synchronized void close(){closed=true;closeSession();}
}
