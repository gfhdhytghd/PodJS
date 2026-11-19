package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.datasource.*;
import java.io.IOException;
import java.util.*;

/** Playback cache bypass changes bytes only. Every HTTP exit shares the process media policy. */
final class PodVideoHttpDataSources {
    private static final class Shared {static final PodMediaHttp HTTP=new PodMediaHttp(true);}
    static final class RetryCacheMiss extends IOException {}
    static final class PrepareGate {
        private volatile boolean cacheOnly;
        PrepareGate(boolean cacheOnly){this.cacheOnly=cacheOnly;}
        boolean cacheOnly(){return cacheOnly;}
        // Native admission may retry the same MediaSource after a decoder denial.
        void restrict(){cacheOnly=true;}
        void ready(){cacheOnly=false;}
        void requireUpstream() throws RetryCacheMiss {
            if(cacheOnly)throw new RetryCacheMiss();
        }
    }
    static DataSource.Factory create(Map<String,String> headers,TransferListener listener){
        return create(headers,listener,null);
    }
    private static DataSource.Factory create(Map<String,String> headers,TransferListener listener,PrepareGate gate){
        Map<String,String> defaults=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);defaults.putAll(headers);
        return ()->new DataSource(){
            DataSource source;final List<TransferListener> listeners=new ArrayList<>();
            {if(listener!=null)listeners.add(listener);}
            public void addTransferListener(TransferListener value){listeners.add(value);if(source!=null)source.addTransferListener(value);}
            public long open(DataSpec spec)throws IOException {
                close();if(gate!=null)gate.requireUpstream();source=Shared.HTTP.createDataSource();for(TransferListener value:listeners)source.addTransferListener(value);
                Map<String,String> merged=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);merged.putAll(defaults);merged.putAll(spec.httpRequestHeaders);
                return source.open(spec.buildUpon().setHttpRequestHeaders(merged).build());
            }
            public int read(byte[] bytes,int offset,int length)throws IOException{return source.read(bytes,offset,length);}
            public Uri getUri(){return source==null?null:source.getUri();}
            public Map<String,List<String>> getResponseHeaders(){return source==null?Collections.emptyMap():source.getResponseHeaders();}
            public void close()throws IOException{DataSource previous=source;source=null;if(previous!=null)previous.close();}
        };
    }
    static DataSource.Factory playback(android.content.Context context,PodMediaPrefixCache cache,Map<String,String> headers,boolean bypassCache){
        return playback(context,cache,headers,bypassCache,null);
    }
    static DataSource.Factory playback(android.content.Context context,PodMediaPrefixCache cache,Map<String,String> headers,boolean bypassCache,PrepareGate gate){
        DataSource.Factory direct=new DefaultDataSource.Factory(context,create(headers,null,gate));
        // Capability resolution is mandatory even when cached bytes are bypassed.
        return cache==null?direct:cache.factory(direct,bypassCache,gate);
    }
    private PodVideoHttpDataSources(){}
}
