package dev.podjs.runtime;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** App-private Media3 range transport. Only contiguous prefixes are stored;
 * missing bytes (including MP4 tail seeks) immediately open the original upstream
 * at the requested position. Stable registrations reuse content-addressed bytes. No URL or headers are persisted. */
final class PodMediaPrefixCache implements AutoCloseable {
    static final String SCHEME="podjs-media-cache";
    static final int CHUNK=64*1024, MAX_ENTRY=4*1024*1024, MAX_ENTRIES=16;
    final File root;
    File frameRoot;
    final long maxTotal;
    final DataSource.Factory upstream;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ExecutorService cancellations=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private Set<String> protectedTokens=new HashSet<>();
    private long windowRevision,generation,downloadedBytes,cacheReadBytes,networkReadBytes,failures;
    private long totalBandwidth,foregroundBitrate,sampleStart,sampleBytes,bandwidthWindow,bandwidthUsed;
    private double bandwidthCredit;
    private boolean closed;
    private java.util.function.Consumer<JSONObject> resultSink=value->{};
    synchronized void setResultSink(java.util.function.Consumer<JSONObject> sink){resultSink=sink;}
    static final class Entry {
        final String token,remote;final Map<String,String> headers;final File file;
        long length=-1;int captureBytes=MAX_ENTRY;
        File tail(){return new File(file.getParentFile(),token+".tail");}
        Entry(String token,String remote,Map<String,String> headers,File file){this.token=token;this.remote=remote;this.headers=headers;this.file=file;}
    }
    final class Job {
        final Entry entry;final int budget;final long epoch;final String itemToken;final long sourceGeneration;boolean background,preparing;volatile boolean active=true;volatile DataSource source;
        Job(Entry e,int budget,String itemToken,long sourceGeneration){entry=e;this.budget=budget;epoch=generation;this.itemToken=itemToken;this.sourceGeneration=sourceGeneration;}
        void cancel(){active=false;DataSource s=source;if(s!=null&&!cancellations.isShutdown())cancellations.execute(()->{try{s.close();}catch(Exception ignored){}});}
        boolean current(){synchronized(PodMediaPrefixCache.this){return active&&!closed&&epoch==generation&&entries.get(entry.token)==entry;}}
    }
    PodMediaPrefixCache(File root){this(root,PodDouyinRequest.factory(new DefaultHttpDataSource.Factory().setConnectTimeoutMs(12000).setReadTimeoutMs(25000),Collections.emptyMap()),Math.max(6L*1024*1024,Math.min(16L*1024*1024,Runtime.getRuntime().maxMemory()/4)));}
    PodMediaPrefixCache(File root,DataSource.Factory upstream,long budget){
        this.root=root;this.frameRoot=root;this.upstream=upstream;this.maxTotal=Math.max(MAX_ENTRY,Math.min(16L*1024*1024,budget));
        // Files are opaque media bytes, never a durable capability. Registrations
        // must be recreated with a fresh authorized remote source after restart.
        if(!root.isDirectory())root.mkdirs();
        trimFiles();trimSnapshots();for(File file:root.listFiles()==null?new File[0]:root.listFiles())if(file.getName().matches("[a-f0-9]{64}\\.length")&&!new File(root,file.getName().replace(".length",".prefix")).isFile())file.delete();
    }
    private static String digest(String text)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
    synchronized String register(String key,String remote,Map<String,String> headers)throws Exception {return register(key,remote,headers,false);}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent)throws Exception {
        if(closed)throw new IOException("cache_closed");Uri u=Uri.parse(remote);
        if(key.isEmpty()||key.length()>2048||!("https".equals(u.getScheme())||"http".equals(u.getScheme()))||(u.getHost()==null||u.getHost().isEmpty())||u.getUserInfo()!=null||remote.length()>32768)throw new IllegalArgumentException("invalid_media_cache_source");
        TreeMap<String,String> clean=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);int bytes=0;
        if(headers.size()>32)throw new IllegalArgumentException("too_many_headers");
        for(Map.Entry<String,String> h:headers.entrySet()){String name=h.getKey(),value=h.getValue();bytes+=name.length()+value.length();if(!name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0||bytes>32768)throw new IllegalArgumentException("invalid_media_cache_headers");if(!name.equalsIgnoreCase("Range"))clean.put(name,value);}
        String token=digest(persistent?"content-v1\n"+key:key+"\n"+remote+"\n"+clean.toString());Entry e=entries.get(token);
        if(e!=null&&(!e.remote.equals(remote)||!e.headers.equals(clean))){Job old=jobs.remove(token);if(old!=null)old.cancel();entries.remove(token);e=null;}
        if(e==null){e=new Entry(token,remote,Collections.unmodifiableMap(clean),new File(root,token+".prefix"));File lengthFile=new File(root,token+".length");if(lengthFile.isFile())try(DataInputStream input=new DataInputStream(new FileInputStream(lengthFile))){long length=input.readLong();if(length>=e.file.length())e.length=length;}catch(IOException ignored){}entries.put(token,e);trim();}
        return SCHEME+"://play/"+token+".mp4";
    }
    private String token(String uri){Uri u=Uri.parse(uri);if(!SCHEME.equals(u.getScheme())||!"play".equals(u.getHost()))return null;String p=u.getLastPathSegment();return p!=null&&p.matches("[a-f0-9]{64}\\.mp4")?p.substring(0,64):null;}
    synchronized Entry target(String uri)throws IOException {String t=token(uri);Entry e=t==null?null:entries.get(t);if(e==null)throw new IOException("media_cache_registration_missing");return e;}
    synchronized boolean owns(String uri){try{target(uri);return true;}catch(IOException e){return false;}}
    JSONObject execute(String method,JSONObject a)throws Exception {
        if(method.equals("video.cache.prepare"))return PodMediaPrefixPreparation.prepare(this,a);
        synchronized(this){
        if(method.equals("video.cache.inspect")){Entry e=target(a.getString("uri"));return new JSONObject().put("prefixBytes",e.file.length()).put("tailBytes",e.tail().length()).put("length",e.length);}
        if(method.equals("video.cache.remove")){Entry e=target(a.getString("uri"));Job j=jobs.remove(e.token);if(j!=null)j.cancel();entries.remove(e.token);e.file.delete();e.tail().delete();new File(root,e.token+".length").delete();return new JSONObject();}
        if(method.equals("video.cache.register")){Map<String,String> h=new HashMap<>();JSONObject o=a.optJSONObject("headers");if(o!=null)for(Iterator<String> keys=o.keys();keys.hasNext();){String k=keys.next();h.put(k,o.getString(k));}return new JSONObject().put("uri",register(a.getString("key"),a.getString("url"),h,a.optBoolean("persistent",false)));}
        if(method.equals("video.cache.stats"))return stats();
        if(method.equals("video.cache.clear")){windowRevision=Math.max(windowRevision,a.optLong("revision",0));long bytes=fileBytes();clear();return new JSONObject().put("removedBytes",bytes);}
        if(method.equals("video.cache.window")){
            long revision=a.optLong("revision",0);if(revision<windowRevision)return stats();windowRevision=revision;
            JSONArray items=a.optJSONArray("items");if(items==null||items.length()>MAX_ENTRIES)throw new IllegalArgumentException("invalid_media_cache_window");
            Set<String> wanted=new LinkedHashSet<>();List<Job> pending=new ArrayList<>();protectedTokens.clear();foregroundBitrate=Math.max(0,a.optLong("foregroundBitrate",0));
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);Entry e=target(item.getString("uri"));wanted.add(e.token);if(i==0)protectedTokens.add(e.token);int budget=item.optInt("prefetchBytes",0);if(budget<0||budget>MAX_ENTRY)throw new IllegalArgumentException("invalid_prefix_budget");e.captureBytes=Math.max(budget,Math.min(MAX_ENTRY,item.optInt("captureBytes",MAX_ENTRY)));
                String itemToken=item.optString("itemToken","");long sourceGeneration=item.optLong("generation",0);if(!itemToken.isEmpty()&&(!itemToken.matches("[A-Za-z0-9_-]{1,96}")||sourceGeneration<0||sourceGeneration>9007199254740991L))throw new IllegalArgumentException("invalid_prefetch_identity");Job existing=jobs.get(e.token);if(budget>e.file.length()&&(existing==null||!existing.active||existing.budget<budget||!existing.itemToken.equals(itemToken)||existing.sourceGeneration!=sourceGeneration)){if(existing!=null)existing.cancel();Job j=new Job(e,budget,itemToken,sourceGeneration);jobs.put(e.token,j);pending.add(j);}}
            for(Iterator<Map.Entry<String,Job>> it=jobs.entrySet().iterator();it.hasNext();){Map.Entry<String,Job> j=it.next();if(!wanted.contains(j.getKey())){j.getValue().cancel();it.remove();}}
            for(Job j:pending)workers.execute(()->prefetch(j));trim();return stats();
        }
        throw new IllegalArgumentException("unsupported_cache_method");
        }
    }
    void prefetch(Job job){
        DataSource source=null;boolean succeeded=false;int status=0;boolean failed=false;
        try{
            long position; synchronized(this){position=job.entry.file.length();}
            if(!job.current()||position>=job.budget)return;
            if(job.background)backgroundTurn(job);
            source=upstream.createDataSource();job.source=source;if(!job.current())return;
            long remaining=source.open(new DataSpec.Builder().setUri(job.entry.remote).setPosition(position).setHttpRequestHeaders(job.entry.headers).build());if(source instanceof androidx.media3.datasource.HttpDataSource)status=((androidx.media3.datasource.HttpDataSource)source).getResponseCode();else if(source instanceof PodDouyinRequest)status=((PodDouyinRequest)source).responseCode();metadata(job.entry,source,position,remaining,true);
            byte[] buffer=new byte[CHUNK];
            while(position<job.budget&&job.current()){
                int limit=(int)Math.min(buffer.length,job.budget-position);
                if(job.background)backgroundTurn(job);int n=source.read(buffer,0,limit);if(n==C.RESULT_END_OF_INPUT){synchronized(this){job.entry.length=position;}break;}if(n==0)continue;
                recordNetwork(n);if(!throttle(job,n))break;synchronized(this){downloadedBytes+=n;}if(!job.current())break;append(job.entry,position,buffer,0,n,job.epoch);position+=n;
            }
            succeeded=job.current();
        }catch(Exception error){if(job.current()){status=httpStatus(error);failed=true;synchronized(this){failures++;}android.util.Log.i("PodMediaCache","prefetchFailure category="+(status>0?"http_"+status:"transport"));}}
        finally{if(source!=null)try{source.close();}catch(Exception ignored){}if((succeeded||failed)&&job.current())publish(job,succeeded,status);synchronized(this){if(!job.preparing&&jobs.get(job.entry.token)==job)jobs.remove(job.entry.token);trim();android.util.Log.i("PodMediaCache","prefetchProgress downloadedBytes="+downloadedBytes+" cacheBytes="+fileBytes());}}
    }
    synchronized Job beginPreparation(String uri,int bytes)throws Exception {
        Entry e=target(uri);Job previous=jobs.get(e.token);if(previous!=null)previous.cancel();
        Job job=new Job(e,bytes,"",0);job.background=true;job.preparing=true;e.captureBytes=bytes;jobs.put(e.token,job);return job;
    }
    synchronized void endPreparation(Job job){if(jobs.get(job.entry.token)==job)jobs.remove(job.entry.token);job.active=false;trim();}
    static void backgroundTurn(Job job)throws Exception {
        // Never hold the admission ticket across network IO or decoding.
        if(!job.current())throw new IOException("preparation_cancelled");
        try(HostWorkPriority.Ticket ticket=HostWorkPriority.DEVICE.reserve(true)){ticket.enter(job::current);}
        if(!job.current())throw new IOException("preparation_cancelled");
    }
    synchronized void saveTail(Job job,byte[] bytes)throws Exception {
        if(!job.current())throw new IOException("preparation_cancelled");
        File temporary=File.createTempFile("tail-",".tmp",root);
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            java.nio.file.Files.move(temporary.toPath(),job.entry.tail().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            downloadedBytes+=bytes.length;networkReadBytes+=bytes.length;trim();
        }finally{temporary.delete();}
    }
    private void publish(Job job,boolean success,int status){
        if(job.itemToken.isEmpty())return;
        try{JSONObject value=new JSONObject().put("itemToken",job.itemToken).put("generation",job.sourceGeneration).put("result",success?"success":"failure").put("httpStatus",status).put("category",success?"success":status==403?"http_403":status>=500?"http_5xx":status>=400?"http_4xx":"transport");
            resultSink.accept(new JSONObject().put("t","video.cache.result").put("value",value));
        }catch(Exception ignored){}
    }
    static int httpStatus(Throwable error){for(Throwable x=error;x!=null;x=x.getCause())if(x instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)x).responseCode;return 0;}
    private boolean throttle(Job j,int bytes)throws InterruptedException {
        while(j.current()){
            // Idle transfers are limited by the transport itself. Only reserve bandwidth for an actual foreground stream.
            synchronized(this){long budget=foregroundBitrate<=0?Long.MAX_VALUE:totalBandwidth<=0?0:Math.max(0,totalBandwidth-(foregroundBitrate*13+79)/80);long now=android.os.SystemClock.elapsedRealtime();long elapsed=bandwidthWindow==0?0:Math.max(0,now-bandwidthWindow);bandwidthWindow=now;
                if(budget==Long.MAX_VALUE)return true;
                if(budget>0){bandwidthCredit=Math.min(CHUNK,bandwidthCredit+elapsed*budget/1000d);if(bandwidthCredit>=bytes){bandwidthCredit-=bytes;return true;}}else bandwidthCredit=0;}
            Thread.sleep(250);
        }return false;
    }
    private synchronized void recordNetwork(int n){networkReadBytes+=n;long now=android.os.SystemClock.elapsedRealtime();if(sampleStart==0)sampleStart=now;sampleBytes+=n;long elapsed=now-sampleStart;if(elapsed>=500&&sampleBytes>=32*1024){long current=sampleBytes*1000/Math.max(1,elapsed);totalBandwidth=totalBandwidth>0?(totalBandwidth*3+current)/4:current;sampleBytes=0;sampleStart=now;}}
    private synchronized void metadata(Entry e,DataSource source,long position,long remaining,boolean unbounded){
        for(Map.Entry<String,List<String>> h:source.getResponseHeaders().entrySet())if(h.getKey()!=null&&h.getKey().equalsIgnoreCase("Content-Range"))for(String value:h.getValue()){int slash=value.lastIndexOf('/');if(slash>=0)try{e.length=Long.parseLong(value.substring(slash+1));}catch(NumberFormatException ignored){}}
        if(e.length<0&&remaining>=0&&unbounded)e.length=position+remaining;
        persistLength(e);
        android.util.Log.i("PodMediaCache","upstreamOpen position="+position+" totalBytes="+e.length+" prefixBytes="+e.file.length());
    }
    private void persistLength(Entry e){if(e.length>=0&&!closed&&entries.get(e.token)==e&&e.file.isFile())try(DataOutputStream output=new DataOutputStream(new FileOutputStream(new File(root,e.token+".length")))){output.writeLong(e.length);}catch(IOException ignored){}}
    private synchronized void append(Entry e,long position,byte[] buffer,int offset,int n,long epoch)throws IOException {
        if(closed||epoch!=generation||entries.get(e.token)!=e||position!=e.file.length()||position>=Math.min(e.captureBytes,MAX_ENTRY-e.tail().length()))return;
        int count=(int)Math.min(n,Math.min(e.captureBytes,MAX_ENTRY-e.tail().length())-position);if(!root.isDirectory())root.mkdirs();try(RandomAccessFile f=new RandomAccessFile(e.file,"rw")){f.seek(position);f.write(buffer,offset,count);}if(!new File(root,e.token+".length").isFile())persistLength(e);e.file.setLastModified(System.currentTimeMillis());trim();
    }
    synchronized int read(Entry e,long position,byte[] bytes,int offset,int size)throws IOException {
        if(position<0||entries.get(e.token)!=e)return 0;
        if(position>=e.file.length()){
            File tail=e.tail();long start=e.length-tail.length();
            if(!tail.isFile()||e.length<0||position<start||position>=e.length)return 0;
            try(RandomAccessFile f=new RandomAccessFile(tail,"r")){f.seek(position-start);int n=f.read(bytes,offset,(int)Math.min(size,e.length-position));if(n>0)cacheReadBytes+=n;return Math.max(0,n);}
        }
        int n;try(RandomAccessFile f=new RandomAccessFile(e.file,"r")){f.seek(position);n=f.read(bytes,offset,(int)Math.min(size,e.file.length()-position));}if(n>0){cacheReadBytes+=n;e.file.setLastModified(System.currentTimeMillis());}return Math.max(0,n);
    }
    private File[] files(){File[] f=root.listFiles((dir,name)->name.matches("[a-f0-9]{64}\\.prefix"));return f==null?new File[0]:f;}
    private long fileBytes(){long n=0;for(File f:files())n+=f.length()+new File(root,f.getName().replace(".prefix",".tail")).length();return n;}
    private void trimFiles(){File[] fs=files();Arrays.sort(fs,Comparator.comparingLong(File::lastModified));long bytes=fileBytes();int count=fs.length;for(File f:fs){if(bytes<=maxTotal&&count<=MAX_ENTRIES)break;String t=f.getName().substring(0,64);if(protectedTokens.contains(t))continue;bytes-=f.length()+new File(root,t+".tail").length();count--;f.delete();new File(root,t+".tail").delete();new File(root,t+".length").delete();}}
    private void trim(){while(entries.size()>MAX_ENTRIES){String remove=null;for(String k:entries.keySet())if(!protectedTokens.contains(k)){remove=k;break;}if(remove==null)break;Entry e=entries.remove(remove);Job j=jobs.remove(remove);if(j!=null)j.cancel();e.file.delete();e.tail().delete();new File(root,e.token+".length").delete();}trimFiles();}
    private void trimSnapshots(){File[] fs=files();Arrays.sort(fs,Comparator.comparingLong(File::lastModified).reversed());for(int i=6;i<fs.length;i++){fs[i].delete();new File(root,fs[i].getName().replace(".prefix",".tail")).delete();new File(root,fs[i].getName().replace(".prefix",".length")).delete();}}
    /** One closed counter snapshot at a real foreground decoded first frame. */
    synchronized void logPlaybackStats(){
        android.util.Log.i("PodMediaCache","cache_stats mono_ms="+android.os.SystemClock.elapsedRealtime()+" files="+files().length+" bytes="+fileBytes()+" downloadedBytes="+downloadedBytes+" cacheReadBytes="+cacheReadBytes+" networkReadBytes="+networkReadBytes+" maxBytes="+maxTotal);
    }
    synchronized void logFirstCardStats(long seq){
        android.util.Log.i("PodMediaCache","cache_first_card seq="+seq+" mono_ms="+android.os.SystemClock.uptimeMillis()+" files="+files().length+" bytes="+fileBytes()+" downloadedBytes="+downloadedBytes+" cacheReadBytes="+cacheReadBytes+" networkReadBytes="+networkReadBytes+" maxBytes="+maxTotal);
    }
    synchronized JSONObject stats()throws Exception {return new JSONObject().put("files",files().length).put("bytes",fileBytes()).put("maxBytes",maxTotal).put("maxEntries",MAX_ENTRIES).put("activePrefetches",jobs.size()).put("downloadedBytes",downloadedBytes).put("networkReadBytes",networkReadBytes).put("cacheReadBytes",cacheReadBytes).put("failures",failures).put("bandwidthBytesPerSecond",totalBandwidth);}
    private void clear(){generation++;for(Job j:jobs.values())j.cancel();jobs.clear();entries.clear();protectedTokens.clear();for(File f:root.listFiles()==null?new File[0]:root.listFiles())if(f.getName().matches("[a-f0-9]{64}\\.(prefix|length|tail)"))f.delete();}
    @Override public synchronized void close(){closed=true;generation++;for(Job j:jobs.values())j.cancel();jobs.clear();protectedTokens.clear();trimSnapshots();entries.clear();workers.shutdownNow();cancellations.shutdown();}
    DataSource.Factory factory(DataSource.Factory fallback){return ()->new PrefixDataSource(fallback);}
    private final class PrefixDataSource implements DataSource {
        private final DataSource.Factory fallback;private final List<TransferListener> listeners=new ArrayList<>();private DataSource network;private DataSpec spec;private Entry entry;private long position,remaining,epoch;private boolean ended,hitLogged;
        PrefixDataSource(DataSource.Factory f){fallback=f;}
        @Override public void addTransferListener(TransferListener l){listeners.add(l);if(network!=null)network.addTransferListener(l);}
        @Override public long open(DataSpec data)throws IOException {
            close();spec=data;position=data.position;remaining=data.length;epoch=generation;ended=false;
            if(SCHEME.equals(data.uri.getScheme())){entry=target(data.uri.toString());if(entry.length>=0&&position>=entry.length){ended=true;return 0;}if(position>=entry.file.length()&&!(entry.tail().isFile()&&entry.length>=0&&position>=entry.length-entry.tail().length()&&position<entry.length))openNetwork();return remaining>=0?remaining:entry.length>=0?Math.max(0,entry.length-position):C.LENGTH_UNSET;}
            network=fallback.createDataSource();for(TransferListener l:listeners)network.addTransferListener(l);return network.open(data);
        }
        private void openNetwork()throws IOException {if(network!=null)return;network=upstream.createDataSource();for(TransferListener l:listeners)network.addTransferListener(l);Map<String,String> h=new HashMap<>(spec.httpRequestHeaders);h.putAll(entry.headers);DataSpec next=spec.buildUpon().setUri(entry.remote).setPosition(position).setLength(remaining).setHttpRequestHeaders(h).build();try{long length=network.open(next);metadata(entry,network,position,length,remaining==C.LENGTH_UNSET);}catch(IOException error){try{network.close();}catch(IOException ignored){}network=null;int status=httpStatus(error);android.util.Log.i("PodMediaCache","upstreamFailure category="+(status>0?"http_"+status:"transport"));throw error;}}
        @Override public int read(byte[] b,int offset,int length)throws IOException {
            if(length==0)return 0;if(ended||remaining==0)return C.RESULT_END_OF_INPUT;if(spec==null)throw new IOException("source_not_open");int wanted=remaining<0?length:(int)Math.min(length,remaining);int n=0;
            if(entry!=null&&network==null){n=PodMediaPrefixCache.this.read(entry,position,b,offset,wanted);if(n>0&&!hitLogged){hitLogged=true;android.util.Log.i("PodMediaCache","prefixHit bytes="+n+" position="+position);}}
            if(n==0){if(entry!=null&&entry.length>=0&&position>=entry.length){ended=true;return C.RESULT_END_OF_INPUT;}if(network==null)openNetwork();n=network.read(b,offset,wanted);if(n<0){ended=true;return n;}if(entry!=null){recordNetwork(n);append(entry,position,b,offset,n,epoch);}}
            position+=n;if(remaining>=0)remaining-=n;return n;
        }
        @Override public Uri getUri(){return spec==null?null:spec.uri;}
        @Override public Map<String,List<String>> getResponseHeaders(){return network==null?Collections.emptyMap():network.getResponseHeaders();}
        @Override public void close()throws IOException {DataSource n=network;network=null;spec=null;entry=null;position=0;remaining=C.LENGTH_UNSET;ended=false;hitLogged=false;if(n!=null)n.close();}
    }
}
