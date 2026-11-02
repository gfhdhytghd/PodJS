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

/** One bounded pool per media cache. No probes, cookie jar, URL logs or automatic retries.
 * A race is opt-in for byte-identical mirrors, once before any prefix is published. */
final class PodMediaHttp implements DataSource.Factory, AutoCloseable {
    private final OkHttpClient client;
    private final ExecutorService racers=Executors.newFixedThreadPool(2);
    private volatile IOException stopped;private volatile boolean closed;
    PodMediaHttp(){
        client=new OkHttpClient.Builder().connectionPool(new ConnectionPool(4,2,TimeUnit.MINUTES))
            .dns(new CachedDns()).connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build();
    }
    public DataSource createDataSource(){return new Stream();}
    DataSource race(List<String> urls,Consumer<String> winner){return new Race(urls,winner);}
    private void check()throws IOException{if(stopped!=null)throw stopped;if(closed)throw new IOException("media_transport_closed");}
    private void failed(IOException error){int status=PodMediaPrefixCache.httpStatus(error);if(status==403||status==429){stopped=error;client.dispatcher().cancelAll();}}
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
        private volatile Call call;private volatile boolean closed,busy;private boolean disposed;private final Object io=new Object();
        private final DataSource source=PodDouyinRequest.factory(new OkHttpDataSource.Factory(request->{
            Call next=client.newCall(request);call=next;if(closed||PodMediaHttp.this.closed||stopped!=null)next.cancel();return next;
        }),Collections.emptyMap()).createDataSource();
        public void addTransferListener(TransferListener listener){source.addTransferListener(listener);}
        public long open(DataSpec spec)throws IOException{synchronized(io){check();if(closed)throw new InterruptedIOException("media_cancelled");busy=true;try{return source.open(spec);}catch(IOException error){failed(error);throw error;}finally{busy=false;}}}
        public int read(byte[] bytes,int offset,int length)throws IOException{synchronized(io){check();if(closed)throw new InterruptedIOException("media_cancelled");busy=true;try{return source.read(bytes,offset,length);}catch(IOException error){failed(error);throw error;}finally{busy=false;}}}
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
                try{opened=completions.take().get();}catch(ExecutionException error){Throwable cause=error.getCause();last=cause instanceof IOException?(IOException)cause:new IOException("media_race_failed",cause);check();continue;}
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
