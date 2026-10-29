package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import org.json.JSONObject;
import java.io.Closeable;

/** One foreground-only QR-authorized setup channel. Business state is owned by the app.
 * provision.start {transport:'internet'|'bluetooth'}; a missing transport keeps the original
 * Bluetooth behavior for older bundles. Only Bluetooth needs nearby-device permission. */
final class PodBleProvisionServices implements Closeable {
    private final Context context;
    private ProvisioningSession session;
    private boolean closed,starting;
    private String sessionId,startOwner;
    PodBleProvisionServices(Context context){this.context=context;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation) throws Exception {
        if("provision.close".equals(method)){cancel(args.optString("sessionId"));return new JSONObject().put("closed",true);}
        if("provision.start".equals(method)){
            final boolean internet=ProvisioningSession.INTERNET.equals(args.optString("transport",ProvisioningSession.BLUETOOTH));
            final ProvisioningSession opened;
            final String openedId;
            synchronized(this){
                cancellation.throwIfCanceled();
                if(closed||session!=null)throw new IllegalStateException("配对服务忙碌");
                if(!internet)PodBlePermissions.require(context,false,true);
                opened=internet?new OnlineProvisioningSession(args.getString("deviceId"),args.getString("installId")):new BleProvisioningSession(context,args.getString("deviceId"),args.getString("installId"));
                session=opened;openedId=opened.sessionId();sessionId=openedId;starting=true;startOwner=args.getString("requestId");
            }
            try{opened.start();JSONObject value=new JSONObject().put("qr",opened.qr()).put("sessionId",openedId).put("messageSequenceMax",opened.messageSequenceMax()).put("ownershipReleaseV1",opened.ownershipReleaseSupported()).put("transport",internet?ProvisioningSession.INTERNET:ProvisioningSession.BLUETOOTH);synchronized(this){if(session==opened)starting=false;}return value;}
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
    synchronized void cancel(String expected){if(expected.equals(sessionId))closeSession();}
    /** A cancelled start has no session id on the guest side yet; it still owns the opening session. */
    synchronized void cancelStart(String owner){if(starting&&owner.equals(startOwner))closeSession();}
    synchronized void closeSession(){if(session!=null)session.close();session=null;sessionId=null;startOwner=null;starting=false;}
    public synchronized void close(){closed=true;closeSession();}
}
