package dev.podjs.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.CancellationSignal;
import org.json.JSONObject;
import okhttp3.*;
import okio.ByteString;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guest-owned WebSocket text messages and binary byte streams. Authentication
 * and application framing belong to the guest. Closing either handle closes both. */
final class PodWebSocketServices implements Closeable {
    private final Context context;
    private final PodByteStreamServices streams;
    private final Map<String,Connection> connections=new HashMap<>();
    private final Set<Connection> opening=new HashSet<>();
    private boolean closed;
    PodWebSocketServices(Context context,PodByteStreamServices streams){this.context=context;this.streams=streams;}
    private static int timeout(JSONObject args)throws Exception {Object n=args.get("timeoutMs");if(!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue()||((Number)n).intValue()<1||((Number)n).intValue()>900000)throw new IllegalArgumentException("Invalid timeoutMs");return ((Number)n).intValue();}
    private synchronized void check(){if(closed)throw new IllegalStateException("WebSocket service closed");}
    private synchronized Connection get(String id){check();Connection c=connections.get(id);if(c==null)throw new IllegalArgumentException("Unknown WebSocket");return c;}
    JSONObject execute(String method,JSONObject args,CancellationSignal cancellation)throws Exception {
        check();cancellation.throwIfCanceled();
        if(method.equals("websocket.close")){discard(args);return new JSONObject().put("closed",true);}
        if(method.equals("websocket.connect")){
            int timeout=timeout(args);String url=args.getString("url");URI uri=new URI(url);if(!"ws".equals(uri.getScheme())&&!"wss".equals(uri.getScheme()))throw new IllegalArgumentException("WebSocket URL required");
            OkHttpClient.Builder builder=new OkHttpClient.Builder().connectTimeout(timeout,TimeUnit.MILLISECONDS).readTimeout(0,TimeUnit.MILLISECONDS);
            if(args.optBoolean("bindLocalWifi",false)){Network network=networkFor(uri.getHost());if(network!=null)builder.socketFactory(network.getSocketFactory());}
            Request.Builder request=new Request.Builder().url(url);JSONObject headers=args.optJSONObject("headers");if(headers!=null){Iterator<String> names=headers.keys();while(names.hasNext()){String name=names.next();request.header(name,headers.getString(name));}}
            Connection c=new Connection(builder.build(),args.optBoolean("initialTextHandshake",false));synchronized(this){check();if(connections.size()+opening.size()>=4)throw new IllegalStateException("Too many WebSockets");opening.add(c);}
            cancellation.setOnCancelListener(c::close);
            try{
                c.start(request.build());c.awaitOpen(timeout);cancellation.throwIfCanceled();
                synchronized(this){check();String id=streams.attach(c.input,c.output,c);c.id=id;connections.put(id,c);opening.remove(c);cancellation.throwIfCanceled();return new JSONObject().put("id",id);}
            }catch(Exception error){if(c.id!=null)discard(new JSONObject().put("id",c.id));else c.close();throw error;}
            finally{synchronized(this){opening.remove(c);}cancellation.setOnCancelListener(null);}
        }
        String id=args.getString("id");Connection c=get(id);cancellation.setOnCancelListener(()->discardId(id));
        try{
            if(method.equals("websocket.readText")){String text=c.readText(timeout(args));cancellation.throwIfCanceled();return new JSONObject().put("text",text);}
            if(method.equals("websocket.writeText")){String text=args.getString("text");if(text.length()>65536)throw new IllegalArgumentException("WebSocket text too large");c.sendText(text);cancellation.throwIfCanceled();return new JSONObject().put("sent",true);}
            throw new IllegalArgumentException("Unknown WebSocket method");
        }catch(Exception error){discardId(id);throw error;}
        finally{cancellation.setOnCancelListener(null);}
    }
    private void discardId(String id){Connection c;synchronized(this){c=connections.remove(id);}if(c!=null){try{streams.discard(new JSONObject().put("id",id));}catch(Exception ignored){}c.close();}}
    void discard(JSONObject value){discardId(value.optString("id"));}
    public void close(){List<Connection> all;synchronized(this){if(closed)return;closed=true;all=new ArrayList<>(connections.values());all.addAll(opening);connections.clear();opening.clear();}for(Connection c:all){if(c.id!=null)try{streams.discard(new JSONObject().put("id",c.id));}catch(Exception ignored){}c.close();}}
    @SuppressWarnings("deprecation") private Network networkFor(String address)throws Exception {
        if(address==null||!address.matches("[0-9.]+"))return null;InetAddress target=InetAddress.getByName(address);if(!(target instanceof Inet4Address))return null;
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager==null)return null;
        for(Network network:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;android.net.LinkProperties props=manager.getLinkProperties(network);if(props==null)continue;for(LinkAddress link:props.getLinkAddresses())if(contains(link,target))return network;}
        return null;
    }
    static boolean contains(LinkAddress link,InetAddress target){return contains(link.getAddress(),link.getPrefixLength(),target);}
    static boolean contains(InetAddress address,int prefixLength,InetAddress target){byte[] local=address.getAddress(),remote=target.getAddress();if(local.length!=4||remote.length!=4)return false;int prefix=Math.max(0,Math.min(32,prefixLength));for(int i=0;i<4;i++){int bits=Math.max(0,Math.min(8,prefix-i*8)),mask=bits==0?0:(255<<(8-bits))&255;if((local[i]&mask)!=(remote[i]&mask))return false;}return true;}
    private final class Connection extends WebSocketListener implements Closeable {
        final OkHttpClient client;final Object lock=new Object();final ArrayDeque<byte[]> binary=new ArrayDeque<>();final ArrayDeque<String> texts=new ArrayDeque<>();final AtomicBoolean readingText=new AtomicBoolean();
        final boolean initialTextHandshake;boolean textReceived;
        WebSocket socket;String id;boolean opened,ended;IOException failure;int buffered,headOffset;
        Connection(OkHttpClient client,boolean initialTextHandshake){this.client=client;this.initialTextHandshake=initialTextHandshake;}
        void start(Request request){synchronized(lock){if(ended)return;socket=client.newWebSocket(request,this);}}
        public void onOpen(WebSocket socket,Response response){synchronized(lock){if(ended){socket.cancel();return;}opened=true;lock.notifyAll();}}
        public void onMessage(WebSocket socket,String text){synchronized(lock){if(ended||(initialTextHandshake&&textReceived))return;if(text.length()>65536||texts.size()>=8){fail(new IOException("WebSocket text queue overflow"));socket.cancel();return;}texts.add(text);textReceived=true;lock.notifyAll();}}
        public void onMessage(WebSocket socket,ByteString data){try{synchronized(lock){if(initialTextHandshake&&!textReceived)return;if(data.size()>4*1024*1024)throw new IOException("WebSocket binary message too large");while(!ended&&buffered+data.size()>4*1024*1024)lock.wait();if(ended)return;if(data.size()>0){binary.add(data.toByteArray());buffered+=data.size();lock.notifyAll();}}}catch(Exception error){fail(new IOException("WebSocket receive failed",error));socket.cancel();}}
        public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);finish();}
        public void onClosed(WebSocket socket,int code,String reason){finish();client.dispatcher().executorService().shutdown();}
        public void onFailure(WebSocket socket,Throwable error,Response response){fail(new IOException("WebSocket failed",error));client.dispatcher().executorService().shutdown();}
        void finish(){synchronized(lock){ended=true;lock.notifyAll();}}
        void fail(IOException error){synchronized(lock){failure=error;ended=true;lock.notifyAll();}}
        void available()throws IOException{if(ended)throw failure==null?new IOException("WebSocket closed"):failure;}
        void awaitOpen(int timeout)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+timeout;synchronized(lock){while(!opened){available();long wait=end-android.os.SystemClock.elapsedRealtime();if(wait<=0)throw new IOException("WebSocket connect timed out");lock.wait(wait);}available();}}
        String readText(int timeout)throws Exception{if(!readingText.compareAndSet(false,true))throw new IllegalStateException("Concurrent WebSocket text read");try{long end=android.os.SystemClock.elapsedRealtime()+timeout;synchronized(lock){while(texts.isEmpty()){available();long wait=end-android.os.SystemClock.elapsedRealtime();if(wait<=0)throw new IOException("WebSocket text timed out");lock.wait(wait);}return texts.remove();}}finally{readingText.set(false);}}
        void sendText(String text)throws IOException{synchronized(lock){available();if(socket==null||!socket.send(text))throw new IOException("WebSocket send queue closed");}}
        final InputStream input=new InputStream(){public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}public int read(byte[] b,int offset,int length)throws IOException{if(length==0)return 0;synchronized(lock){while(binary.isEmpty()&&!ended)try{lock.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("WebSocket read interrupted",e);}if(binary.isEmpty()){if(failure!=null)throw failure;return -1;}byte[] head=binary.peek();int n=Math.min(length,head.length-headOffset);System.arraycopy(head,headOffset,b,offset,n);headOffset+=n;buffered-=n;if(headOffset==head.length){binary.remove();headOffset=0;}lock.notifyAll();return n;}}};
        final OutputStream output=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public void write(byte[] bytes,int offset,int count)throws IOException{synchronized(lock){available();if(socket==null||!socket.send(ByteString.of(bytes,offset,count)))throw new IOException("WebSocket send queue closed");}}};
        public void close(){synchronized(lock){ended=true;binary.clear();texts.clear();buffered=0;if(socket!=null)socket.cancel();lock.notifyAll();}synchronized(PodWebSocketServices.this){if(id!=null)connections.remove(id,this);}client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
    }
}
