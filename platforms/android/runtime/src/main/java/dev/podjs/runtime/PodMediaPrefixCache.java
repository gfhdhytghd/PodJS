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

/** App-private Media3 range transport. A contiguous prefix and complete suffix are stored;
 * missing bytes (including MP4 tail seeks) immediately open the original upstream
 * at the requested position. Stable registrations reuse content-addressed bytes. No URL or headers are persisted. */
final class PodMediaPrefixCache implements AutoCloseable {
    static final String SCHEME="podjs-media-cache";
    static final int CHUNK=64*1024, MAX_ENTRY=4*1024*1024, MAX_ENTRIES=16;
    final File root;
    File frameRoot;
    final long maxTotal;
    final DataSource.Factory upstream;
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private final ExecutorService cancellations=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private Set<String> protectedTokens=new LinkedHashSet<>();
    private static final java.util.concurrent.atomic.AtomicLong ORDINALS=new java.util.concurrent.atomic.AtomicLong();
    private long windowRevision,generation,downloadedBytes,cacheReadBytes,networkReadBytes,failures;
    // A cached player consumes no network reserve, even if its rendition has a bitrate.
    private int foregroundNetworkReaders;
    private long totalBandwidth,foregroundBitrate,sampleStart,sampleBytes,bandwidthWindow,bandwidthUsed;
    private double bandwidthCredit;
    private boolean closed,pumping;
    private java.util.function.Consumer<JSONObject> resultSink=value->{};
    synchronized void setResultSink(java.util.function.Consumer<JSONObject> sink){resultSink=sink;}
    static final class Entry {
        final long ordinal=ORDINALS.incrementAndGet();final String token,remote;volatile String selectedRemote;List<String> mirrors=Collections.emptyList();boolean raced;long sampleEnd=-1,keyframeEnd=-1;final Map<String,String> headers;final File file;final boolean persistent;
        volatile long networkBytes,rangeRequests;long length=-1;int captureBytes=MAX_ENTRY;
        File tail(){return new File(file.getParentFile(),token+".tail");}
        Entry(String token,String remote,Map<String,String> headers,File file,boolean persistent){this.token=token;this.remote=remote;this.selectedRemote=remote;this.headers=headers;this.file=file;this.persistent=persistent;}
    }
    final class Job {
        final Entry entry;final int budget;final long epoch;volatile String itemToken;volatile long sourceGeneration;boolean background,preparing,done;int stage;volatile boolean active=true;volatile DataSource source;Exception failure;
        Job(Entry e,int budget,String itemToken,long sourceGeneration){entry=e;this.budget=budget;epoch=generation;this.itemToken=itemToken;this.sourceGeneration=sourceGeneration;}
        void cancel(){active=false;DataSource s=source;if(s!=null&&!cancellations.isShutdown())cancellations.execute(()->{try{s.close();}catch(Exception ignored){}});}
        boolean current(){synchronized(PodMediaPrefixCache.this){return active&&!closed&&epoch==generation&&entries.get(entry.token)==entry;}}
    }
    PodMediaPrefixCache(File root){this(root,new PodMediaHttp(),Math.max(6L*1024*1024,Math.min(16L*1024*1024,Runtime.getRuntime().maxMemory()/4)));}
    PodMediaPrefixCache(File root,DataSource.Factory upstream,long budget){
        this.root=root;this.frameRoot=root;this.upstream=upstream;this.maxTotal=Math.max(MAX_ENTRY,Math.min(16L*1024*1024,budget));
        // Files are opaque media bytes, never a durable capability. Registrations
        // must be recreated with a fresh authorized remote source after restart.
        if(!root.isDirectory())root.mkdirs();
        trimFiles();trimSnapshots();for(File file:root.listFiles()==null?new File[0]:root.listFiles())if(file.getName().matches("[a-f0-9]{64}\\.length")&&!new File(root,file.getName().replace(".length",".prefix")).isFile())file.delete();
    }
    private static String digest(String text)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
    synchronized String register(String key,String remote,Map<String,String> headers)throws Exception {return register(key,remote,headers,false);}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent)throws Exception {return register(key,remote,headers,persistent,Collections.emptyList());}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent,List<String> alternates)throws Exception {
        if(closed)throw new IOException("cache_closed");Uri u=Uri.parse(remote);
        if(key.isEmpty()||key.length()>2048||!("https".equals(u.getScheme())||"http".equals(u.getScheme()))||(u.getHost()==null||u.getHost().isEmpty())||u.getUserInfo()!=null||remote.length()>32768)throw new IllegalArgumentException("invalid_media_cache_source");
        List<String> mirrors=new ArrayList<>();mirrors.add(remote);
        if(alternates.size()>1)throw new IllegalArgumentException("too_many_media_mirrors");
        for(String alternate:alternates){Uri m=Uri.parse(alternate);if(alternate.length()>32768||!u.getScheme().equals(m.getScheme())||m.getHost()==null||m.getHost().isEmpty()||m.getUserInfo()!=null)throw new IllegalArgumentException("invalid_media_mirror");if(!mirrors.contains(alternate))mirrors.add(alternate);}
        TreeMap<String,String> clean=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);int bytes=0;
        if(headers.size()>32)throw new IllegalArgumentException("too_many_headers");
        for(Map.Entry<String,String> h:headers.entrySet()){String name=h.getKey(),value=h.getValue();bytes+=name.length()+value.length();if(!name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0||bytes>32768)throw new IllegalArgumentException("invalid_media_cache_headers");if(!name.equalsIgnoreCase("Range"))clean.put(name,value);}
        String token=digest(persistent?"content-v1\n"+key:key+"\n"+remote+"\n"+clean.toString()+(mirrors.size()>1?"\n"+mirrors.get(1):""));Entry e=entries.get(token);
        if(e!=null&&(!e.remote.equals(remote)||!e.headers.equals(clean)||!e.mirrors.equals(mirrors))){Job old=jobs.remove(token);if(old!=null)old.cancel();entries.remove(token);e=null;}
        if(e==null){e=new Entry(token,remote,Collections.unmodifiableMap(clean),new File(root,token+".prefix"),persistent);e.mirrors=Collections.unmodifiableList(mirrors);File lengthFile=new File(root,token+".length");if(lengthFile.isFile())try(DataInputStream input=new DataInputStream(new FileInputStream(lengthFile))){long length=input.readLong();if(length>=e.file.length())e.length=length;}catch(IOException ignored){}entries.put(token,e);trim();}
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
        if(method.equals("video.cache.register")){Map<String,String> h=new HashMap<>();JSONObject o=a.optJSONObject("headers");if(o!=null)for(Iterator<String> keys=o.keys();keys.hasNext();){String k=keys.next();h.put(k,o.getString(k));}return new JSONObject().put("uri",register(a.getString("key"),a.getString("url"),h,a.optBoolean("persistent",false),mirrors(a)));}
        if(method.equals("video.cache.stats"))return stats();
        if(method.equals("video.cache.clear")){windowRevision=Math.max(windowRevision,a.optLong("revision",0));long bytes=fileBytes();clear();return new JSONObject().put("removedBytes",bytes);}
        if(method.equals("video.cache.window")){
            long revision=a.optLong("revision",0);if(revision<windowRevision)return stats();windowRevision=revision;
            JSONArray items=a.optJSONArray("items");if(items==null||items.length()>MAX_ENTRIES)throw new IllegalArgumentException("invalid_media_cache_window");
            Set<String> wanted=new LinkedHashSet<>();protectedTokens.clear();foregroundBitrate=Math.max(0,a.optLong("foregroundBitrate",0));
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);Entry e=target(item.getString("uri"));wanted.add(e.token);protectedTokens.add(e.token);int budget=item.optInt("prefetchBytes",0);if(budget<0||budget>MAX_ENTRY)throw new IllegalArgumentException("invalid_prefix_budget");e.captureBytes=Math.max(budget,Math.min(MAX_ENTRY,item.optInt("captureBytes",MAX_ENTRY)));
                String itemToken=item.optString("itemToken","");long sourceGeneration=item.optLong("generation",0);if(!itemToken.isEmpty()&&(!itemToken.matches("[A-Za-z0-9_-]{1,96}")||sourceGeneration<0||sourceGeneration>9007199254740991L))throw new IllegalArgumentException("invalid_prefetch_identity");Job existing=jobs.get(e.token);if(budget>e.file.length()&&(existing==null||!existing.active||existing.budget<budget)){if(existing!=null)existing.cancel();Job j=new Job(e,budget,itemToken,sourceGeneration);jobs.put(e.token,j);}else if(existing!=null){existing.itemToken=itemToken;existing.sourceGeneration=sourceGeneration;}}
            for(Iterator<Map.Entry<String,Job>> it=jobs.entrySet().iterator();it.hasNext();){Map.Entry<String,Job> j=it.next();if(!wanted.contains(j.getKey())){j.getValue().cancel();it.remove();}}
            schedule();trim();return stats();
        }
        throw new IllegalArgumentException("unsupported_cache_method");
        }
    }
    /** Transfer callbacks include HTTP 200 range-skip bytes consumed during open().
     * Sources without callbacks still account each byte returned by read(). */
    private DataSource observedSource(Entry entry){return observedSource(entry,true);}
    private DataSource observedSource(Entry entry,boolean allowRace){
        DataSource source;
        synchronized(this){boolean race=allowRace&&upstream instanceof PodMediaHttp&&!entry.raced&&entry.file.length()==0&&entry.mirrors.size()>1;
            if(race){entry.raced=true;source=((PodMediaHttp)upstream).race(entry.mirrors,url->entry.selectedRemote=url);}
            else source=upstream.createDataSource();}
        return new ObservedSource(source,entry);
    }
    private static List<String> mirrors(JSONObject args)throws Exception {JSONArray values=args.optJSONArray("alternates");List<String> result=new ArrayList<>();if(values!=null)for(int i=0;i<values.length();i++)result.add(values.getString(i));return result;}
    /** A single fetch owner: first-frame stages first, then remaining prefixes, each in window order.
     * Foreground readers never enter this queue. Their bandwidth reserve still gates every read. */
    private synchronized void schedule(){if(pumping||closed)return;pumping=true;workers.execute(()->{
        while(true){Job next=null;synchronized(PodMediaPrefixCache.this){
            for(int stage=0;stage<2&&next==null;stage++){
                for(String token:protectedTokens){Job candidate=jobs.get(token);if(candidate!=null&&candidate.active&&!candidate.done&&candidate.stage==stage){next=candidate;break;}}
                if(next==null)for(Job candidate:jobs.values())if(candidate.active&&!candidate.done&&candidate.stage==stage){next=candidate;break;}
            }
            if(next==null||closed){pumping=false;PodMediaPrefixCache.this.notifyAll();return;}
        }fetchStep(next);}
    });}
    void prefetch(Job job){synchronized(this){schedule();while(job.current()&&!job.done)try{wait(100);}catch(InterruptedException error){Thread.currentThread().interrupt();job.cancel();job.failure=error;break;}}}
    private final class ObservedSource implements DataSource {
        final DataSource delegate;final Entry entry;final long ordinal,request=ORDINALS.incrementAndGet();long transferred,opened=-1,first=-1,last=-1,rangePosition,requested;boolean logged;
        ObservedSource(DataSource delegate,Entry entry){
            this.delegate=delegate;this.entry=entry;this.ordinal=entry.ordinal;
            delegate.addTransferListener(new TransferListener(){
                public void onTransferInitializing(DataSource source,DataSpec spec,boolean network){}
                public void onTransferStart(DataSource source,DataSpec spec,boolean network){}
                public void onBytesTransferred(DataSource source,DataSpec spec,boolean network,int bytes){if(bytes>0){synchronized(ObservedSource.this){transferred+=bytes;noteByte();}recordNetwork(entry,bytes);}}
                public void onTransferEnd(DataSource source,DataSpec spec,boolean network){}
            });
        }
        int responseCode(){return delegate instanceof androidx.media3.datasource.HttpDataSource?((androidx.media3.datasource.HttpDataSource)delegate).getResponseCode():delegate instanceof PodDouyinRequest?((PodDouyinRequest)delegate).responseCode():PodMediaHttp.responseCode(delegate);}
        public void addTransferListener(TransferListener listener){delegate.addTransferListener(listener);}
        public long open(DataSpec spec)throws IOException{opened=android.os.SystemClock.uptimeMillis();rangePosition=spec.position;requested=spec.length;synchronized(PodMediaPrefixCache.this){entry.rangeRequests+=PodMediaHttp.attempts(delegate,spec);}return delegate.open(spec);}
        public int read(byte[] bytes,int offset,int length)throws IOException{long before=transferred;int n=delegate.read(bytes,offset,length);if(!PodMediaHttp.countsTransfers(delegate)&&n>transferred-before){int extra=(int)(n-(transferred-before));transferred+=extra;noteByte();recordNetwork(entry,extra);}return n;}
        public Uri getUri(){return delegate.getUri();}
        public Map<String,List<String>> getResponseHeaders(){return delegate.getResponseHeaders();}
        synchronized void noteByte(){long now=android.os.SystemClock.uptimeMillis();if(first<0)first=now;last=now;}
        public void close()throws IOException{int status=Math.max(0,responseCode());try{delegate.close();}finally{if(!logged&&opened>=0){logged=true;android.util.Log.i("PodMediaCache","range_transfer entry="+ordinal+" request="+request+" position="+rangePosition+" requested_bytes="+Math.max(0,requested)+" network_bytes="+transferred+" ttfb_ms="+(first<0?0:Math.max(0,first-opened))+" read_ms="+(first<0?0:Math.max(0,last-first))+" status="+status);}}}
    }
    private void fetchStep(Job job){
        boolean succeeded=false;int status=0;boolean failed=false;
        try{
            long position; synchronized(this){position=job.entry.file.length();}
            long goal=job.stage==0?Math.min(job.budget,192*1024):Math.max(job.budget,job.entry.sampleEnd);
            int fallbackTail=PodMediaPrefixPreparation.fallbackTailBytes(job);
            if(fallbackTail>0)goal=Math.min(goal,job.budget-fallbackTail);
            boolean pendingFallback=job.preparing&&(position<8||job.entry.length<0);
            if(job.entry.sampleEnd>MAX_ENTRY-job.entry.tail().length())throw new IOException("samples_over_budget");
            goal=Math.min(goal,MAX_ENTRY-job.entry.tail().length());
            int requests=0;
            while(job.current()&&position<goal&&(job.entry.length<0||position<job.entry.length)){
                if(++requests>64)throw new IOException("too_many_media_ranges");
                if(job.background)backgroundTurn(job);
                DataSource source=observedSource(job.entry);job.source=source;
                long start=position;
                try{
                    if(!job.current())break;
                    long remaining=source.open(new DataSpec.Builder().setUri(job.entry.selectedRemote).setPosition(position).setLength(goal-position).setHttpRequestHeaders(job.entry.headers).build());
                    status=((ObservedSource)source).responseCode();
                    long responseEnd=metadata(job.entry,source,position,remaining,false);
                    byte[] buffer=new byte[CHUNK];
                    while(position<goal&&job.current()){
                        if(job.background)backgroundTurn(job);
                        int n=source.read(buffer,0,(int)Math.min(buffer.length,goal-position));
                        if(n==C.RESULT_END_OF_INPUT){
                            if(responseEnd>=0&&position<responseEnd)throw new EOFException("short_media_range");
                            synchronized(this){if(job.entry.length<0){job.entry.length=position;persistLength(job.entry);}}
                            break;
                        }
                        if(n==0)continue;
                        if(responseEnd>=0&&n>responseEnd-position)throw new IOException("oversized_media_range");
                        if(!throttle(job,n))break;
                        synchronized(this){downloadedBytes+=n;}if(!job.current())break;
                        append(job.entry,position,buffer,0,n,job.epoch);position+=n;
                        if(pendingFallback&&position>=8&&job.entry.length>=0){
                            fallbackTail=PodMediaPrefixPreparation.fallbackTailBytes(job);
                            if(fallbackTail>0)goal=Math.min(goal,job.budget-fallbackTail);
                            pendingFallback=false;
                        }
                    }
                }finally{try{source.close();}finally{if(job.source==source)job.source=null;}}
                if(position==start)throw new EOFException("empty_media_range");
            }
            if(job.current()&&job.stage==0){
                PodMediaPrefixMp4.cacheMetadata(this,job,true);job.stage=1;
                android.util.Log.i("PodMediaCache","keyframe_prefix entry="+job.entry.ordinal+" planned="+(job.entry.keyframeEnd>=0)+" keyframe_end="+Math.max(0,job.entry.keyframeEnd)+" network_bytes="+job.entry.networkBytes+" prefix_bytes="+job.entry.file.length()+" tail_bytes="+job.entry.tail().length());
                return;
            }
            succeeded=job.current()&&job.entry.file.length()>0&&job.entry.file.length()>=(job.entry.length>0?Math.min(goal,job.entry.length):goal);
            if(succeeded&&job.preparing)PodMediaPrefixPreparation.cacheFallback(this,job);
        }catch(Exception error){succeeded=false;job.failure=error;if(job.current()){status=httpStatus(error);failed=true;synchronized(this){failures++;}android.util.Log.i("PodMediaCache","prefetchFailure category="+(status>0?"http_"+status:"transport"));}}
        finally{if((succeeded||failed)&&job.current())publish(job,succeeded,status);synchronized(this){if(succeeded||failed||!job.current()){job.done=true;if(!job.preparing&&jobs.get(job.entry.token)==job)jobs.remove(job.entry.token);}notifyAll();trim();android.util.Log.i("PodMediaCache","prefetchProgress downloadedBytes="+downloadedBytes+" cacheBytes="+fileBytes());}}
    }
    /** Exact range reads account bytes even if cancellation or a short body prevents publication. */
    byte[] loadRange(Job job,long position,int count)throws Exception {
        byte[] bytes=new byte[count];int used=0,requests=0;
        while(used<count){
            if(++requests>64)throw new IOException("too_many_media_ranges");
            if(job.background)backgroundTurn(job);else if(!job.current())throw new IOException("preparation_cancelled");DataSource source=observedSource(job.entry);job.source=source;int before=used;
            try{
                long remaining=source.open(new DataSpec.Builder().setUri(job.entry.selectedRemote).setPosition(position+used).setLength(count-used).setHttpRequestHeaders(job.entry.headers).build());
                long responseEnd=metadata(job.entry,source,position+used,remaining,false);
                while(used<count){
                    if(job.background)backgroundTurn(job);int wanted=Math.min(CHUNK,count-used);if(!throttle(job,wanted))throw new IOException("preparation_cancelled");int n=source.read(bytes,used,wanted);
                    if(n<0){if(responseEnd<0||position+used<responseEnd)throw new EOFException("short_media_range");break;}
                    if(n>0){if(responseEnd>=0&&n>responseEnd-position-used)throw new IOException("oversized_media_range");used+=n;}
                }
            }finally{try{source.close();}finally{if(job.source==source)job.source=null;}}
            if(used==before)throw new EOFException("empty_media_range");
        }
        return bytes;
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
    synchronized void appendMetadata(Job job,byte[] bytes)throws IOException {
        if(!job.current())throw new IOException("preparation_cancelled");
        job.entry.captureBytes=MAX_ENTRY;long position=job.entry.file.length();
        append(job.entry,position,bytes,0,bytes.length,job.epoch);downloadedBytes+=bytes.length;
    }
    synchronized void fitPreparation(Job job,int budget)throws IOException {
        if(!job.current())throw new IOException("preparation_cancelled");
        long prefix=budget-job.entry.tail().length();
        if(prefix<0)throw new IOException("media_cache_budget_exceeded");
        if(job.entry.file.length()>prefix)try(RandomAccessFile file=new RandomAccessFile(job.entry.file,"rw")){file.setLength(prefix);}
    }
    synchronized void saveTail(Job job,byte[] bytes,int budget)throws Exception {
        if(!job.current())throw new IOException("preparation_cancelled");
        File temporary=File.createTempFile("tail-",".tmp",root);
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            // Publish only a complete suffix. Both ranges share the caller's byte budget.
            if(job.entry.file.length()>budget-bytes.length)try(RandomAccessFile prefix=new RandomAccessFile(job.entry.file,"rw")){prefix.setLength(budget-bytes.length);}
            java.nio.file.Files.move(temporary.toPath(),job.entry.tail().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            downloadedBytes+=bytes.length;trim();
        }finally{temporary.delete();}
    }
    private synchronized void publish(Job job,boolean success,int status){
        if(job.itemToken.isEmpty())return;
        try{JSONObject value=new JSONObject().put("itemToken",job.itemToken).put("generation",job.sourceGeneration).put("result",success?"success":"failure").put("httpStatus",status).put("category",success?"success":status==403?"http_403":status>=500?"http_5xx":status>=400?"http_4xx":"transport");
            resultSink.accept(new JSONObject().put("t","video.cache.result").put("value",value));
        }catch(Exception ignored){}
    }
    static int httpStatus(Throwable error){for(Throwable x=error;x!=null;x=x.getCause())if(x instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)x).responseCode;return 0;}
    private boolean throttle(Job j,int bytes)throws InterruptedException {
        while(j.current()){
            // Idle transfers are limited by the transport itself. Only reserve bandwidth for an actual foreground stream.
            synchronized(this){long budget=foregroundBitrate<=0||foregroundNetworkReaders==0?Long.MAX_VALUE:totalBandwidth<=0?0:Math.max(0,totalBandwidth-(foregroundBitrate*13+79)/80);long now=android.os.SystemClock.elapsedRealtime();long elapsed=bandwidthWindow==0?0:Math.max(0,now-bandwidthWindow);bandwidthWindow=now;
                if(budget==Long.MAX_VALUE)return true;
                if(budget>0){bandwidthCredit=Math.min(CHUNK,bandwidthCredit+elapsed*budget/1000d);if(bandwidthCredit>=bytes){bandwidthCredit-=bytes;return true;}}else bandwidthCredit=0;}
            Thread.sleep(250);
        }return false;
    }
    synchronized void recordNetwork(Entry entry,int n){entry.networkBytes+=n;recordNetwork(n);}
    synchronized void recordNetwork(int n){networkReadBytes+=n;long now=android.os.SystemClock.elapsedRealtime();if(sampleStart==0)sampleStart=now;sampleBytes+=n;long elapsed=now-sampleStart;if(elapsed>=500&&sampleBytes>=32*1024){long current=sampleBytes*1000/Math.max(1,elapsed);totalBandwidth=totalBandwidth>0?(totalBandwidth*3+current)/4:current;sampleBytes=0;sampleStart=now;}}
    private synchronized long metadata(Entry e,DataSource source,long position,long remaining,boolean unbounded)throws IOException {
        long total=-1,end=remaining>=0?position+remaining:-1;boolean rangeSeen=false;
        for(Map.Entry<String,List<String>> h:source.getResponseHeaders().entrySet())if(h.getKey()!=null&&h.getKey().equalsIgnoreCase("Content-Range"))for(String value:h.getValue()){
            rangeSeen=true;
            java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(value);
            if(!range.matches())throw new IOException("invalid_media_range");
            try{
                long start=Long.parseLong(range.group(1)),last=Long.parseLong(range.group(2)),length=Long.parseLong(range.group(3));
                if(start!=position||last<start||last>=length)throw new IOException("invalid_media_range");
                if(total>=0&&(total!=length||end!=last+1))throw new IOException("conflicting_media_range");
                total=length;end=last+1;
            }catch(NumberFormatException invalid){throw new IOException("invalid_media_range");}
        }
        if(!rangeSeen&&source instanceof ObservedSource&&((ObservedSource)source).responseCode()==206)throw new IOException("missing_media_range");
        if(total<0&&remaining>=0&&unbounded)total=position+remaining;
        if(total>=0){if(e.length>=0&&e.length!=total)throw new IOException("media_length_changed");e.length=total;}
        persistLength(e);
        android.util.Log.i("PodMediaCache","upstreamOpen entry="+e.ordinal+" position="+position+" totalBytes="+e.length+" prefixBytes="+e.file.length());
        return end;
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
    private void trimFiles(){
        File[] fs=files();Arrays.sort(fs,Comparator.comparingLong(File::lastModified));long bytes=fileBytes();int count=fs.length;
        for(int pass=0;pass<2;pass++)for(File f:fs){
            if(bytes<=maxTotal&&count<=MAX_ENTRIES)return;
            if(!f.isFile())continue;String t=f.getName().substring(0,64);
            if(pass==0&&protectedTokens.contains(t))continue;
            // The first requested source keeps foreground priority under a saturated window.
            if(pass==1&&!protectedTokens.isEmpty()&&t.equals(protectedTokens.iterator().next()))continue;
            bytes-=f.length()+new File(root,t+".tail").length();count--;f.delete();new File(root,t+".tail").delete();new File(root,t+".length").delete();
        }
    }
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
    @Override public synchronized void close(){closed=true;generation++;for(Job j:jobs.values())j.cancel();jobs.clear();protectedTokens.clear();trimSnapshots();entries.clear();workers.shutdownNow();cancellations.shutdown();if(upstream instanceof PodMediaHttp)((PodMediaHttp)upstream).close();notifyAll();}
    DataSource.Factory factory(DataSource.Factory fallback){return ()->new PrefixDataSource(fallback);}
    private final class PrefixDataSource implements DataSource {
        private final DataSource.Factory fallback;private final List<TransferListener> listeners=new ArrayList<>();private DataSource network;private DataSpec spec;private Entry entry;private long position,remaining,epoch;private boolean ended,hitLogged,networkCounted,cacheTransfer;
        PrefixDataSource(DataSource.Factory f){fallback=f;}
        @Override public void addTransferListener(TransferListener l){listeners.add(l);if(network!=null)network.addTransferListener(l);}
        @Override public long open(DataSpec data)throws IOException {
            close();spec=data;position=data.position;remaining=data.length;epoch=generation;ended=false;
            if(SCHEME.equals(data.uri.getScheme())){entry=target(data.uri.toString());cacheTransfer=true;for(TransferListener l:listeners){l.onTransferInitializing(this,data,false);l.onTransferStart(this,data,false);}if(entry.length>=0&&position>=entry.length){ended=true;return 0;}if(position>=entry.file.length()&&!(entry.tail().isFile()&&entry.length>=0&&position>=entry.length-entry.tail().length()&&position<entry.length))openNetwork();return remaining>=0?remaining:entry.length>=0?Math.max(0,entry.length-position):C.LENGTH_UNSET;}
            network=fallback.createDataSource();for(TransferListener l:listeners)network.addTransferListener(l);return network.open(data);
        }
        private void openNetwork()throws IOException {if(network!=null)return;network=observedSource(entry,false);for(TransferListener l:listeners)network.addTransferListener(l);Map<String,String> h=new HashMap<>(spec.httpRequestHeaders);h.putAll(entry.headers);DataSpec next=spec.buildUpon().setUri(entry.selectedRemote).setPosition(position).setLength(remaining).setHttpRequestHeaders(h).build();synchronized(PodMediaPrefixCache.this){foregroundNetworkReaders++;networkCounted=true;}try{long length=network.open(next);metadata(entry,network,position,length,remaining==C.LENGTH_UNSET);}catch(IOException|RuntimeException error){try{network.close();}catch(IOException ignored){}network=null;releaseNetworkReader();int status=httpStatus(error);android.util.Log.i("PodMediaCache","upstreamFailure category="+(status>0?"http_"+status:"transport"));throw error;}}
        @Override public int read(byte[] b,int offset,int length)throws IOException {
            if(length==0)return 0;if(ended||remaining==0)return C.RESULT_END_OF_INPUT;if(spec==null)throw new IOException("source_not_open");int wanted=remaining<0?length:(int)Math.min(length,remaining);int n=0;
            if(entry!=null&&network==null){n=PodMediaPrefixCache.this.read(entry,position,b,offset,wanted);if(n>0)for(TransferListener l:listeners)l.onBytesTransferred(this,spec,false,n);if(n>0&&!hitLogged){hitLogged=true;android.util.Log.i("PodMediaCache","prefixHit bytes="+n+" position="+position);}}
            if(n==0){if(entry!=null&&entry.length>=0&&position>=entry.length){ended=true;return C.RESULT_END_OF_INPUT;}if(network==null)openNetwork();n=network.read(b,offset,wanted);if(n<0){ended=true;releaseNetworkReader();return n;}if(entry!=null){append(entry,position,b,offset,n,epoch);}}
            position+=n;if(remaining>=0){remaining-=n;if(remaining==0)releaseNetworkReader();}return n;
        }
        private void releaseNetworkReader(){synchronized(PodMediaPrefixCache.this){if(networkCounted){networkCounted=false;foregroundNetworkReaders--;}}}
        @Override public Uri getUri(){return spec==null?null:spec.uri;}
        @Override public Map<String,List<String>> getResponseHeaders(){return network==null?Collections.emptyMap():network.getResponseHeaders();}
        @Override public void close()throws IOException {DataSource n=network;network=null;if(cacheTransfer){cacheTransfer=false;for(TransferListener l:listeners)l.onTransferEnd(this,spec,false);}spec=null;entry=null;position=0;remaining=C.LENGTH_UNSET;ended=false;hitLogged=false;try{if(n!=null)n.close();}finally{releaseNetworkReader();}}
    }
}
