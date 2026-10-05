package dev.podjs.runtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A Network-scoped lease; never binds the application process to the hotspot. */
final class PodGuidedWifiServices implements AutoCloseable {
    private final Context context;
    private final PodAcousticServices gate;
    private final ConnectivityManager manager;
    private final WifiManager wifi;
    private long sequence,generation;
    private volatile boolean closed;
    private volatile Lease lease;
    private volatile HttpURLConnection http;
    private static final int MAX_BODY=2*1024*1024;
    private static final class Lease {
        long id; volatile Network network; volatile ConnectivityManager.NetworkCallback callback;
        volatile boolean available,stopped; final CountDownLatch ready=new CountDownLatch(1);
    }
    PodGuidedWifiServices(Context context,PodAcousticServices gate){this.context=context;this.gate=gate;manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);}
    Object execute(String method,JSONObject args) throws Exception {
        gate.requireDebug();if(closed)throw new IllegalStateException("Guided WiFi closed");
        if("acoustic.wifi.close".equals(method)){stop(args.getLong("session"));return JSONObject.NULL;}
        if("acoustic.wifi.connect".equals(method))return connect(args);
        if(!"acoustic.wifi.request".equals(method))throw new IllegalArgumentException("Unknown guided WiFi operation");
        Lease active=lease;if(active==null||active.id!=args.getLong("session")||!active.available||active.stopped)throw new IllegalStateException("Guided WiFi session expired");
        String host=args.getString("host"),path=args.getString("path"),token=args.getString("token");int port=args.getInt("port");
        if(host.length()>253||!host.matches("[A-Za-z0-9.:-]+")||port<1||port>65535||token.length()>4096)throw new IllegalArgumentException("Invalid phone endpoint");
        boolean upload="pushFavorites".equals(path)||"pushWatchLater".equals(path);
        if(!upload&&!"pullRemoteInput".equals(path))throw new IllegalArgumentException("Unsupported guided WiFi route");
        URL url=new URL(new URI("http",null,host,port,"/"+path,null,null).toASCIIString()+"?token="+URLEncoder.encode(token,"UTF-8"));
        HttpURLConnection connection=(HttpURLConnection)(active.network==null?url.openConnection():active.network.openConnection(url));
        synchronized(this){if(lease!=active||active.stopped){connection.disconnect();throw new IllegalStateException("Guided WiFi cancelled");}if(http!=null){connection.disconnect();throw new IllegalStateException("Guided WiFi request busy");}http=connection;}
        java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timer.schedule(connection::disconnect,45,TimeUnit.SECONDS);
        try {
            connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
            if(upload){String body=args.getString("body");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BODY)throw new IllegalArgumentException("Guided WiFi body limit");connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();if(status<200||status>299)throw new IOException("请求失败："+status);
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];try(InputStream input=connection.getInputStream()){int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||active.stopped)throw new IOException("Guided WiFi cancelled");if(output.size()+n>MAX_BODY)throw new IOException("Guided WiFi response limit");output.write(buffer,0,n);}}
            String body=output.toString("UTF-8");return new JSONObject(body.trim().isEmpty()?"{}":body);
        }finally{timer.shutdownNow();connection.disconnect();synchronized(this){if(http==connection)http=null;}}
    }
    private Object connect(JSONObject args) throws Exception {
        final long epoch; synchronized(this){epoch=generation;}
        gate.requireActive();
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED||(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED))throw new SecurityException("Guided WiFi permission required");
        String ssid=args.getString("ssid"),password=args.getString("passphrase");if(ssid.length()>32||password.length()>63)throw new IllegalArgumentException("WiFi credentials too long");
        final Lease next=new Lease();synchronized(this){if(closed||generation!=epoch||Thread.currentThread().isInterrupted())throw new IllegalStateException("Guided WiFi cancelled");if(lease!=null)throw new IllegalStateException("Guided WiFi busy");next.id=++sequence;lease=next;}
        try {
            String current=wifi.getConnectionInfo()==null?null:wifi.getConnectionInfo().getSSID();if(current!=null)current=current.replaceAll("^\"|\"$","");
            if(ssid.trim().isEmpty()||ssid.equals(current)){
                Network network=manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
                next.network=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?network:null;next.available=true;
            }else{
                if(password.trim().isEmpty())throw new IllegalStateException("手机和手表需要连接到同一个 WiFi");
                WifiNetworkSpecifier specifier=new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build();
                ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
                    public void onAvailable(Network network){synchronized(PodGuidedWifiServices.this){if(lease!=next||next.stopped)return;next.network=network;next.available=true;}next.ready.countDown();}
                    public void onUnavailable(){stop(next.id);}
                    public void onLost(Network network){stop(next.id);}
                };
                synchronized(this){if(lease!=next||next.stopped)throw new IllegalStateException("Guided WiFi cancelled");next.callback=callback;manager.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build(),callback,45000);}
                if(!next.ready.await(45,TimeUnit.SECONDS)||!next.available||next.stopped)throw new IllegalStateException("手表未能连接到手机热点");
            }
            synchronized(this){if(lease!=next||next.stopped)throw new IllegalStateException("Guided WiFi cancelled");}
            return new JSONObject().put("session",next.id);
        }catch(Exception error){stop(next.id);throw error;}
    }
    void stop(long id){Lease old;HttpURLConnection request;synchronized(this){old=lease;if(old==null||old.id!=id)return;lease=null;old.stopped=true;request=http;http=null;}
        old.ready.countDown();if(request!=null)request.disconnect();if(old.callback!=null)try{manager.unregisterNetworkCallback(old.callback);}catch(Exception ignored){}
    }
    void cancel(){Lease old;synchronized(this){generation++;old=lease;}if(old!=null)stop(old.id);}
    public void close(){closed=true;cancel();}
}
