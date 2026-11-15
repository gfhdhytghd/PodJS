package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded connection pools share one process-wide origin/credential cooldown policy.
 * No probes, cookie jar, URL logs or automatic retries.
 * A race is opt-in for byte-identical mirrors, once before any prefix is published. */
final class PodMediaHttp implements DataSource.Factory, AutoCloseable {
    private final OkHttpClient client;
    private final ExecutorService racers=Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    private final java.util.function.LongSupplier now;
    private final boolean direct;
    private static final Object POLICY=new Object();
    private static final class Cooldown {final IOException error;final long until;final boolean server;final java.util.function.LongSupplier clock;Cooldown(IOException e,long u,boolean s,java.util.function.LongSupplier n){error=e;until=u;server=s;clock=n;}}
    private static final Map<String,Cooldown> cooldowns=new LinkedHashMap<>();
    PodMediaHttp(){this(android.os.SystemClock::elapsedRealtime,false);}
    PodMediaHttp(boolean direct){this(android.os.SystemClock::elapsedRealtime,direct);}
    PodMediaHttp(java.util.function.LongSupplier now){this(now,false);}
    private PodMediaHttp(java.util.function.LongSupplier now,boolean direct){
        this.now=now;this.direct=direct;
        client=new OkHttpClient.Builder().connectionPool(new ConnectionPool(4,2,TimeUnit.MINUTES))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .dns(new CachedDns()).connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
            .eventListenerFactory(call->new TransportEvents())
            .addNetworkInterceptor(chain->{
                Request request=chain.request();DataSpec spec=requestSpec(request);check(spec);
                PodMediaRequestPolicy.check(request);
                Response response=chain.proceed(request);
                if(response.code()==403||response.code()==429)failed(new HttpDataSource.InvalidResponseCodeException(response.code(),response.message(),null,response.headers().toMultimap(),spec,new byte[0]),spec);
                return response;
            }).retryOnConnectionFailure(false).build();
    }
    /** Numeric request identity and closed phases; never host names or request fields. */
    private static final java.util.concurrent.atomic.AtomicLong REQUESTS=new java.util.concurrent.atomic.AtomicLong();
    private static final class TransportEvents extends okhttp3.EventListener {
        private final long request=REQUESTS.incrementAndGet();
        private void phase(String phase){android.util.Log.i("PodMediaCache","transport phase="+phase+" request="+request+" mono_ms="+android.os.SystemClock.uptimeMillis());}
        @Override public void callStart(Call c){phase("call_start");}
        @Override public void dnsStart(Call c,String host){phase("dns_start");}
        @Override public void dnsEnd(Call c,String host,List<InetAddress> addresses){phase("dns_end");}
        @Override public void connectStart(Call c,InetSocketAddress address,Proxy proxy){phase("connect_start");}
        @Override public void secureConnectStart(Call c){phase("tls_start");}
        @Override public void secureConnectEnd(Call c,Handshake handshake){phase("tls_end");}
        @Override public void connectionAcquired(Call c,Connection connection){phase(connection.protocol()==Protocol.HTTP_2?"connection_h2":"connection_http1");}
        @Override public void requestHeadersEnd(Call c,Request request){phase("request_headers");}
        @Override public void responseHeadersStart(Call c){phase("response_start");}
        @Override public void responseHeadersEnd(Call c,Response response){phase("response_headers");}
        @Override public void responseBodyStart(Call c){phase("body_start");}
        @Override public void callEnd(Call c){phase("call_end");}
        @Override public void callFailed(Call c,IOException error){phase(c.isCanceled()?"cancelled":error instanceof java.net.SocketTimeoutException?"timeout":"io_failure");}
    }
    public DataSource createDataSource(){return new Stream();}
    DataSource race(List<String> urls,Consumer<String> winner){return new Race(urls,winner);}
    private void check()throws IOException{if(closed)throw new IOException("media_transport_closed");}
    private static String scope(DataSpec spec){
        HttpUrl url=HttpUrl.get(spec.uri.toString());TreeMap<String,String> credentials=new TreeMap<>();
        for(Map.Entry<String,String> h:spec.httpRequestHeaders.entrySet())if(h.getKey().equalsIgnoreCase("Cookie")||h.getKey().equalsIgnoreCase("Authorization")||h.getKey().equalsIgnoreCase("Proxy-Authorization"))credentials.put(h.getKey().toLowerCase(Locale.ROOT),h.getValue());
        try{return url.scheme()+"://"+url.host()+":"+url.port()+":"+PodVideoFrameCache.hash(credentials.toString());}catch(Exception error){throw new IllegalStateException("media_scope_unavailable",error);}
    }
    private static DataSpec requestSpec(Request request){
        TreeMap<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(String name:request.headers().names())headers.put(name,request.header(name));
        return new DataSpec.Builder().setUri(request.url().toString()).setHttpRequestHeaders(headers).build();
    }
    private void check(DataSpec spec)throws IOException {
        check();synchronized(POLICY){Cooldown value=cooldowns.get(scope(spec));
            if(value!=null){if(value.clock.getAsLong()<value.until)throw value.error;cooldowns.remove(scope(spec));}
        }
    }
    void recover(){synchronized(POLICY){cooldowns.entrySet().removeIf(e->!e.getValue().server||e.getValue().clock.getAsLong()>=e.getValue().until);}}
    private void failed(IOException error,DataSpec spec){
        int status=PodMediaPrefixCache.httpStatus(error);if(status!=403&&status!=429)return;
        long retry=0;for(Throwable e=error;e!=null;e=e.getCause())if(e instanceof HttpDataSource.InvalidResponseCodeException)retry=PodDouyinRequest.retryAfter(((HttpDataSource.InvalidResponseCodeException)e).headerFields);
        synchronized(POLICY){
            // Only real HTTP responses reach here. Local admission refusals do not renew a deadline.
            Cooldown previous=cooldowns.get(scope(spec));
            java.util.function.LongSupplier clock=previous==null?now:previous.clock;
            long received=clock.getAsLong();boolean active=previous!=null&&received<previous.until;
            if(!active){clock=now;received=clock.getAsLong();}
            long deadline=received+Math.max(30_000,retry);
            boolean server=retry>0||active&&previous.server;
            if(active&&previous.until>deadline){deadline=previous.until;error=previous.error;}
            cooldowns.put(scope(spec),new Cooldown(error,deadline,server,clock));
            while(cooldowns.size()>64)cooldowns.remove(cooldowns.keySet().iterator().next());
        }
    }
    public void close(){closed=true;racers.shutdownNow();client.dispatcher().cancelAll();client.connectionPool().evictAll();}
    static final class CachedDns implements Dns {
        private final LinkedHashMap<String,List<InetAddress>> entries=new LinkedHashMap<>(16,.75f,true);
        private final Map<String,Long> expires=new HashMap<>();
        public List<InetAddress> lookup(String host)throws UnknownHostException{
            synchronized(this){List<InetAddress> found=entries.get(host);if(found!=null&&expires.get(host)>System.nanoTime())return found;}
            List<InetAddress> addresses=Dns.SYSTEM.lookup(host);
            synchronized(this){entries.put(host,addresses);expires.put(host,System.nanoTime()+TimeUnit.SECONDS.toNanos(60));while(entries.size()>32){String oldest=entries.keySet().iterator().next();entries.remove(oldest);expires.remove(oldest);}}
            return addresses;
        }
    }
    private final class Stream implements DataSource {
        private PodMediaRequestPolicy.Binding credentials;private volatile Call call;private volatile boolean closed,busy;private boolean disposed;private final Object io=new Object();
        private final DataSource source=PodDouyinRequest.factory(new OkHttpDataSource.Factory(request->{
            Request bound=request.newBuilder().tag(PodMediaRequestPolicy.Binding.class,credentials).build();
            Call next=client.newCall(bound);call=next;if(closed||PodMediaHttp.this.closed)next.cancel();return next;
        }),Collections.emptyMap()).createDataSource();
        public void addTransferListener(TransferListener listener){source.addTransferListener(listener);}
        public long open(DataSpec spec)throws IOException{synchronized(io){credentials=PodMediaRequestPolicy.bind(HttpUrl.get(spec.uri.toString()),Headers.of(spec.httpRequestHeaders),direct);credentials.check(HttpUrl.get(spec.uri.toString()),Headers.of(spec.httpRequestHeaders));check(spec);if(closed)throw new InterruptedIOException("media_cancelled");busy=true;try{return source.open(spec);}finally{busy=false;}}}
        public int read(byte[] bytes,int offset,int length)throws IOException{synchronized(io){check();if(closed)throw new InterruptedIOException("media_cancelled");busy=true;try{return source.read(bytes,offset,length);}finally{busy=false;}}}
        public Uri getUri(){return source.getUri();}
        public Map<String,List<String>> getResponseHeaders(){return source.getResponseHeaders();}
        int responseCode(){return ((PodDouyinRequest)source).responseCode();}
        public void close()throws IOException{closed=true;Call active=call;
            // Cancel the socket before waiting for an in-flight reader. Never close a
            // response body concurrently with read(): Okio's source is not thread-safe.
            if(active!=null&&busy)active.cancel();dispose();
        }
        private void dispose()throws IOException{synchronized(io){if(!disposed){disposed=true;source.close();}}}
        void cancel(){closed=true;Call active=call;if(active!=null)active.cancel();try{dispose();}catch(IOException ignored){}}
    }
    static boolean countsTransfers(DataSource source){return source instanceof PodMediaHttp.Stream||source instanceof PodMediaHttp.Race;}
    static int attempts(DataSource source,DataSpec spec){return source instanceof PodMediaHttp.Race&&spec.position==0?Math.min(2,((PodMediaHttp.Race)source).urls.size()):1;}
    static int responseCode(DataSource source){return source instanceof PodMediaHttp.Stream?((PodMediaHttp.Stream)source).responseCode():source instanceof PodMediaHttp.Race?((PodMediaHttp.Race)source).responseCode():0;}
    private final class Race implements DataSource {
        final List<String> urls;final Consumer<String> selected;final List<TransferListener> listeners=new ArrayList<>();
        final List<Stream> attempts=new ArrayList<>();Stream winner;byte first;boolean firstPending,closed;
        Race(List<String> urls,Consumer<String> selected){this.urls=urls;this.selected=selected;}
        public void addTransferListener(TransferListener listener){listeners.add(listener);}
        public long open(DataSpec spec)throws IOException{
            check();CompletionService<Opened> completions=new ExecutorCompletionService<>(racers);
            int count=spec.position==0?Math.min(2,urls.size()):1;
            synchronized(this){if(closed)throw new InterruptedIOException("media_cancelled");
                for(int index=0;index<count;index++){final String url=urls.get(index);Stream stream=new Stream();attempts.add(stream);for(TransferListener listener:listeners)stream.addTransferListener(listener);
                    completions.submit(()->{try{long remaining=stream.open(spec.buildUpon().setUri(url).build());byte[] b=new byte[1];int n=stream.read(b,0,1);if(n!=1)throw new EOFException("empty_media_range");return new Opened(stream,url,remaining,b[0]);}catch(Exception error){stream.cancel();throw error;}});
                }
            }
            IOException last=new IOException("media_race_failed");
            try{for(int done=0;done<count;done++){
                Opened opened;
                try{opened=completions.take().get();}catch(ExecutionException error){Throwable cause=error.getCause();last=cause instanceof IOException?(IOException)cause:new IOException("media_race_failed",cause);if(PodMediaPrefixCache.httpStatus(last)==403||PodMediaPrefixCache.httpStatus(last)==429)throw last;check();continue;}
                synchronized(this){check();if(closed)throw new InterruptedIOException("media_cancelled");winner=opened.stream;first=opened.first;firstPending=true;selected.accept(opened.url);}
                for(Stream stream:attempts)if(stream!=winner)stream.cancel();
                return opened.length;
            }throw last;}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("media_cancelled");}
            finally{if(winner==null)for(Stream stream:attempts)stream.cancel();}
        }
        public int read(byte[] bytes,int offset,int length)throws IOException{check();if(length==0)return 0;if(firstPending){firstPending=false;bytes[offset]=first;return 1;}return winner.read(bytes,offset,length);}
        public Uri getUri(){return winner==null?null:winner.getUri();}
        public Map<String,List<String>> getResponseHeaders(){return winner==null?Collections.emptyMap():winner.getResponseHeaders();}
        int responseCode(){return winner==null?0:winner.responseCode();}
        public void close()throws IOException{synchronized(this){closed=true;}for(Stream stream:attempts)if(stream==winner)stream.close();else stream.cancel();}
    }
    private static final class Opened {
        final Stream stream;final String url;final long length;final byte first;
        Opened(Stream stream,String url,long length,byte first){this.stream=stream;this.url=url;this.length=length;this.first=first;}
    }
}
