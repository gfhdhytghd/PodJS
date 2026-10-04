package dev.podjs.runtime;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guest-scoped RFCOMM listeners and bounded byte streams. No application
 * protocol, peer trust policy or automatic discovery is embedded here. */
final class PodByteStreamServices implements Closeable {
    private static final int MAX_HANDLES=4, MAX_BYTES=65536;
    private final Context context;
    private final Map<String,Endpoint> streams=new HashMap<>();
    private final Map<String,Listener> listeners=new HashMap<>();
    private final Map<String,TcpListener> tcpListeners=new HashMap<>();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private boolean closed;
    private long sequence;
    private static final class Listener implements Closeable {
        final BluetoothServerSocket socket;
        final AtomicBoolean accepting=new AtomicBoolean();
        Listener(BluetoothServerSocket socket){this.socket=socket;}
        public void close() throws IOException {socket.close();}
    }
    private static final class TcpListener implements Closeable {
        final ServerSocket socket;
        final AtomicBoolean accepting=new AtomicBoolean();
        TcpListener(ServerSocket socket){this.socket=socket;}
        public void close() throws IOException {socket.close();}
    }
    private static final class Endpoint implements Closeable {
        final InputStream input; final OutputStream output; final Closeable owner;
        final AtomicBoolean reading=new AtomicBoolean(),writing=new AtomicBoolean(),closed=new AtomicBoolean();
        Endpoint(InputStream input,OutputStream output,Closeable owner){this.input=input;this.output=output;this.owner=owner;}
        public void close() throws IOException {if(closed.compareAndSet(false,true))owner.close();}
    }
    PodByteStreamServices(Context context){this.context=context;}
    private BluetoothAdapter adapter(){BluetoothManager manager=context.getSystemService(BluetoothManager.class);return manager==null?null:manager.getAdapter();}
    private boolean permitted(){return Build.VERSION.SDK_INT<31||context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void requireBluetooth(){if(!permitted())throw new SecurityException("Bluetooth connection permission required");BluetoothAdapter adapter=adapter();if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("Bluetooth unavailable or disabled");}
    private synchronized void openCheck(){if(closed)throw new IllegalStateException("Stream service closed");}
    private synchronized void capacity(){openCheck();if(streams.size()+listeners.size()+tcpListeners.size()>=MAX_HANDLES)throw new IllegalStateException("Too many stream handles");}
    private static int number(JSONObject args,String name,int minimum,int maximum)throws Exception{Object raw=args.get(name);if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid "+name);double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value!=Math.rint(value)||value<minimum||value>maximum)throw new IllegalArgumentException("Invalid "+name);return (int)value;}
    private synchronized Endpoint stream(String id){openCheck();Endpoint s=streams.get(id);if(s==null||s.closed.get())throw new IllegalArgumentException("Unknown stream");return s;}
    private static void closeQuietly(Closeable closeable){try{closeable.close();}catch(Exception ignored){}}
    private void removeStream(String id){Endpoint endpoint;synchronized(this){endpoint=streams.remove(id);}if(endpoint!=null)closeQuietly(endpoint);}
    private void removeListener(String id){Listener listener;synchronized(this){listener=listeners.remove(id);}if(listener!=null)closeQuietly(listener);}
    private void removeTcpListener(String id){TcpListener listener;synchronized(this){listener=tcpListeners.remove(id);}if(listener!=null)closeQuietly(listener);}
    /** Package-private injection exercises actual stream/deadline ownership with
     * loopback sockets; it is not a guest API or a Bluetooth acceptance claim. */
    synchronized String attach(InputStream input,OutputStream output,Closeable owner){capacity();String id="stream-"+(++sequence);streams.put(id,new Endpoint(input,output,owner));return id;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation)throws Exception {
        openCheck();cancellation.throwIfCanceled();
        if("tcp.addresses".equals(method)){
            JSONArray addresses=new JSONArray();ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
            // Advertise addresses on the active Wi-Fi route, not cellular/VPN routes.
            if(manager!=null){android.net.Network network=manager.getActiveNetwork();NetworkCapabilities caps=manager.getNetworkCapabilities(network);LinkProperties links=manager.getLinkProperties(network);
                if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&links!=null)for(android.net.LinkAddress link:links.getLinkAddresses())if(link.getAddress() instanceof Inet4Address&&!link.getAddress().isLoopbackAddress())addresses.put(link.getAddress().getHostAddress());
            }
            cancellation.throwIfCanceled();return new JSONObject().put("addresses",addresses);
        }
        if("tcp.listen".equals(method)){
            int port=number(args,"port",0,65535);String address=args.getString("address");
            if(!address.equals("0.0.0.0")&&!address.equals("127.0.0.1"))throw new IllegalArgumentException("Invalid listen address");
            synchronized(this){capacity();ServerSocket socket=new ServerSocket();try{socket.setReuseAddress(true);socket.bind(new InetSocketAddress(address,port),4);cancellation.throwIfCanceled();String id="tcp-listener-"+(++sequence);tcpListeners.put(id,new TcpListener(socket));return new JSONObject().put("id",id).put("port",socket.getLocalPort());}catch(Exception e){closeQuietly(socket);throw e;}}
        }
        if("tcp.close".equals(method)){removeTcpListener(args.getString("id"));return new JSONObject().put("closed",true);}
        if("tcp.accept".equals(method)){
            String id=args.getString("id");int timeout=number(args,"timeoutMs",1,900000);TcpListener listener;synchronized(this){listener=tcpListeners.get(id);if(listener==null)throw new IllegalArgumentException("Unknown TCP listener");capacity();}
            if(!listener.accepting.compareAndSet(false,true))throw new IllegalStateException("Accept already pending");
            cancellation.setOnCancelListener(()->removeTcpListener(id));Socket socket=null;
            try{listener.socket.setSoTimeout(timeout);socket=listener.socket.accept();socket.setTcpNoDelay(true);cancellation.throwIfCanceled();String address=socket.getInetAddress().getHostAddress();String streamId=attach(socket.getInputStream(),socket.getOutputStream(),socket);socket=null;try{return new JSONObject().put("id",streamId).put("remoteAddress",address);}catch(Exception error){removeStream(streamId);throw error;}}
            finally{if(socket!=null)closeQuietly(socket);cancellation.setOnCancelListener(null);listener.accepting.set(false);}
        }
        if("rfcomm.status".equals(method)){BluetoothAdapter adapter=adapter();boolean permission=permitted();return new JSONObject().put("available",adapter!=null).put("permission",permission?"granted":"required").put("enabled",permission&&adapter!=null&&adapter.isEnabled());}
        if("rfcomm.listen".equals(method)){
            requireBluetooth();String name=args.getString("name");if(name.isEmpty()||name.length()>128)throw new IllegalArgumentException("Invalid service name");UUID uuid=UUID.fromString(args.getString("uuid"));
            synchronized(this){capacity();BluetoothServerSocket socket=adapter().listenUsingRfcommWithServiceRecord(name,uuid);try{cancellation.throwIfCanceled();String id="listener-"+(++sequence);listeners.put(id,new Listener(socket));return new JSONObject().put("id",id);}catch(Exception e){closeQuietly(socket);throw e;}}
        }
        if("rfcomm.close".equals(method)){removeListener(args.getString("id"));return new JSONObject().put("closed",true);}
        if("stream.close".equals(method)){removeStream(args.getString("id"));return new JSONObject().put("closed",true);}
        if("rfcomm.accept".equals(method)){
            String id=args.getString("id");int timeout=number(args,"timeoutMs",1,900000);Listener listener;synchronized(this){listener=listeners.get(id);if(listener==null)throw new IllegalArgumentException("Unknown listener");capacity();}
            if(!listener.accepting.compareAndSet(false,true))throw new IllegalStateException("Accept already pending");
            cancellation.setOnCancelListener(()->removeListener(id));BluetoothSocket socket=null;
            try{socket=listener.socket.accept(timeout);cancellation.throwIfCanceled();String address=socket.getRemoteDevice().getAddress(),name=socket.getRemoteDevice().getName();String streamId=attach(socket.getInputStream(),socket.getOutputStream(),socket);socket=null;try{return new JSONObject().put("id",streamId).put("remoteAddress",address).put("remoteName",name==null?JSONObject.NULL:name);}catch(Exception error){removeStream(streamId);throw error;}}
            finally{if(socket!=null)closeQuietly(socket);cancellation.setOnCancelListener(null);listener.accepting.set(false);}
        }
        String id=args.getString("id");Endpoint endpoint=stream(id);
        if(!"stream.read".equals(method)&&!"stream.write".equals(method))throw new IllegalArgumentException("Unknown stream method");
        int timeout=number(args,"timeoutMs",1,900000);AtomicBoolean busy="stream.read".equals(method)?endpoint.reading:endpoint.writing;
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("Concurrent stream operation");
        AtomicBoolean timedOut=new AtomicBoolean(),finished=new AtomicBoolean();ScheduledFuture<?> timer=null;
        cancellation.setOnCancelListener(()->removeStream(id));
        try{
            timer=deadlines.schedule(()->{if(finished.compareAndSet(false,true)){timedOut.set(true);removeStream(id);}},timeout,TimeUnit.MILLISECONDS);
            JSONObject result;
            if("stream.read".equals(method)){int max=number(args,"maxBytes",1,MAX_BYTES);byte[] data=new byte[max];int count=endpoint.input.read(data);if(count==0)throw new IOException("Stream read made no progress");if(count<0){removeStream(id);result=new JSONObject().put("eof",true).put("dataBase64","");}else result=new JSONObject().put("eof",false).put("dataBase64",Base64.encodeToString(data,0,count,Base64.NO_WRAP));}
            else{String encoded=args.getString("dataBase64");if(encoded.length()>4*((MAX_BYTES+2)/3))throw new IllegalArgumentException("Stream write too large");byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);if(bytes.length<1||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Invalid stream write size");endpoint.output.write(bytes);endpoint.output.flush();result=new JSONObject().put("written",bytes.length);}
            if(!finished.compareAndSet(false,true))throw new IOException("Stream operation timed out");cancellation.throwIfCanceled();return result;
        }catch(Exception error){removeStream(id);if(timedOut.get())throw new IOException("Stream operation timed out",error);throw error;}
        finally{finished.set(true);if(timer!=null)timer.cancel(false);cancellation.setOnCancelListener(null);busy.set(false);}
    }
    /** Dispose a handle created after its cancelled request lost delivery. */
    void discard(JSONObject value){String id=value.optString("id","");if(id.startsWith("stream-"))removeStream(id);else if(id.startsWith("listener-"))removeListener(id);else if(id.startsWith("tcp-listener-"))removeTcpListener(id);}
    public void close(){ArrayList<Closeable> handles=new ArrayList<>();synchronized(this){if(closed)return;closed=true;handles.addAll(streams.values());handles.addAll(listeners.values());handles.addAll(tcpListeners.values());streams.clear();listeners.clear();tcpListeners.clear();}for(Closeable handle:handles)closeQuietly(handle);deadlines.shutdownNow();}
}
