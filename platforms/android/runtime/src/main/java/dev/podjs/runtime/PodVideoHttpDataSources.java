package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.datasource.*;
import java.io.IOException;
import java.util.*;

/** Playback cache bypass changes bytes only. Every HTTP exit shares the process media policy. */
final class PodVideoHttpDataSources {
    private static final class Shared {static final PodMediaHttp HTTP=new PodMediaHttp(true);}
    static DataSource.Factory create(Map<String,String> headers,TransferListener listener){
        Map<String,String> defaults=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);defaults.putAll(headers);
        return ()->new DataSource(){
            DataSource source;final List<TransferListener> listeners=new ArrayList<>();
            {if(listener!=null)listeners.add(listener);}
            public void addTransferListener(TransferListener value){listeners.add(value);if(source!=null)source.addTransferListener(value);}
            public long open(DataSpec spec)throws IOException {
                close();source=Shared.HTTP.createDataSource();for(TransferListener value:listeners)source.addTransferListener(value);
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
        DataSource.Factory direct=new DefaultDataSource.Factory(context,create(headers,null));
        // Capability resolution is mandatory even when cached bytes are bypassed.
        return cache==null?direct:cache.factory(direct,bypassCache);
    }
    private PodVideoHttpDataSources(){}
}
