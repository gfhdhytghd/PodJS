package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.datasource.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Closed media request outcome. API status -1 and signing false mean not applicable.
 * Referer selects Douyin traffic; it never enters the sink. Close reports intentional
 * prefix termination as success. Read/open/close failures are reported exactly once. */
final class PodDouyinRequest implements DataSource {
    private final DataSource source;private final Map<String,String> defaults;
    private boolean active,cookie;private int status;private long retry;private String error;
    PodDouyinRequest(DataSource source,Map<String,String> defaults){this.source=source;this.defaults=defaults;}
    static DataSource.Factory factory(DataSource.Factory source,Map<String,String> defaults){return ()->new PodDouyinRequest(source.createDataSource(),defaults);}
    @Override public void addTransferListener(TransferListener listener){source.addTransferListener(listener);}
    @Override public long open(DataSpec spec)throws IOException {
        active=false;cookie=false;status=0;retry=0;error="success";
        Map<String,String> headers=new HashMap<>(defaults);headers.putAll(spec.httpRequestHeaders);
        for(Map.Entry<String,String> h:headers.entrySet()){
            if(h.getKey().equalsIgnoreCase("Referer"))try{String host=Uri.parse(h.getValue()).getHost();active="www.douyin.com".equals(host)||"douyin.com".equals(host);}catch(Exception ignored){}
            if(h.getKey().equalsIgnoreCase("Cookie"))cookie=!h.getValue().isEmpty();
        }
        try{long length=source.open(spec);if(source instanceof HttpDataSource)status=((HttpDataSource)source).getResponseCode();retry=retryAfter(source.getResponseHeaders());return length;}
        catch(IOException failure){failed(failure);finish();throw failure;}
    }
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {try{int n=source.read(bytes,offset,length);if(n==-1)finish();return n;}catch(IOException failure){failed(failure);finish();throw failure;}}
    private void failed(IOException failure){error="network";for(Throwable e=failure;e!=null;e=e.getCause()){
        if(e instanceof HttpDataSource.InvalidResponseCodeException){HttpDataSource.InvalidResponseCodeException r=(HttpDataSource.InvalidResponseCodeException)e;status=r.responseCode;retry=retryAfter(r.headerFields);error=status==403?"forbidden":"http";break;}
        if(e instanceof java.net.SocketTimeoutException)error="timeout";
        if(e instanceof java.io.InterruptedIOException&&!error.equals("timeout"))error="cancelled";
    }}
    int responseCode(){return status;}
    static long retryAfter(Map<String,List<String>> headers){if(headers==null)return 0;for(Map.Entry<String,List<String>> h:headers.entrySet())if(h.getKey()!=null&&h.getKey().equalsIgnoreCase("Retry-After"))for(String value:h.getValue())try{long delay=value.trim().matches("[0-9]+")?Math.multiplyExact(Long.parseLong(value.trim()),1000):java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis();return Math.max(0,Math.min(9_007_199_254_740_991L,delay));}catch(Exception ignored){}return 0;}
    private void finish(){if(!active)return;active=false;try{PodBenchmark.begin("douyin_request",new JSONObject().put("category","media").put("http_status",Math.max(0,status)).put("douyin_status_code",-1).put("challenge",false).put("signing_ok",false).put("cookie_present",cookie).put("retry_after_ms",retry).put("error_code",error));}catch(Exception ignored){}}
    @Override public void close()throws IOException {try{source.close();}catch(IOException failure){failed(failure);throw failure;}finally{finish();}}
    @Override public Uri getUri(){return source.getUri();}
    @Override public Map<String,List<String>> getResponseHeaders(){return source.getResponseHeaders();}
}
