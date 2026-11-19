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
    static final int CHUNK=64*1024, MAX_ENTRY=4*1024*1024, MAX_ENTRIES=24;
    static final int MAX_REGISTRATIONS=1024;
    static final long MAX_METADATA_BYTES=8L*1024*1024;
    final File root;
    private final PodMediaPrefixDisk disk;
    File frameRoot;
    long maxTotal;volatile long frameDiskBytes=10L*1024*1024;
    final PodMediaLocalStore localStore;
    PodMediaStartupPreview startupPreview;
    private final Set<String> verifiedLocal=ConcurrentHashMap.newKeySet();
    boolean ownsLocal(String uri){return verifiedLocal.contains(uri);}
    void grantLocal(String uri){verifiedLocal.add(uri);}
    void revokeLocal(String uri){verifiedLocal.remove(uri);}
    private final Set<Object> busyMedia=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Integer> pinnedLocal=new HashMap<>();
    private volatile long trafficStoppedUntil;
    boolean trafficStopped(){return android.os.SystemClock.elapsedRealtime()<trafficStoppedUntil;}
    java.util.function.IntSupplier batteryPercent=()->100;
    synchronized void foregroundBusy(Object owner,boolean busy){if(busy)busyMedia.add(owner);else busyMedia.remove(owner);notifyAll();}
    synchronized boolean localPinned(String token){return pinnedLocal.containsKey(token);}
    synchronized boolean preStoreAllowed(){int battery=batteryPercent.getAsInt();return localStore.enabled&&!trafficStopped()&&busyMedia.isEmpty()&&foregroundNetworkReaders==0&&(battery<0||battery>=localStore.minBattery)&&root.getUsableSpace()>=localStore.minFree;}
    synchronized void checkNetwork(Entry entry)throws IOException {checkRevocation(entry);if(trafficStopped())throw new IOException("media_traffic_stopped");if(System.currentTimeMillis()>=entry.expiresAt)throw new IOException("media_source_expired");}
    synchronized void stopTraffic(){trafficStoppedUntil=android.os.SystemClock.elapsedRealtime()+30_000;for(Job job:jobs.values())job.cancel();notifyAll();}
    final DataSource.Factory upstream;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ExecutorService cancellations=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final Map<Entry,Set<ObservedSource>> foregroundSources=new IdentityHashMap<>();
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private Set<String> protectedTokens=new LinkedHashSet<>();
    private static final java.util.concurrent.atomic.AtomicLong ORDINALS=new java.util.concurrent.atomic.AtomicLong();
    private long windowRevision,generation,downloadedBytes,cacheReadBytes,networkReadBytes,failures;
    // A cached player consumes no network reserve, even if its rendition has a bitrate.
    private int foregroundNetworkReaders;
    private long totalBandwidth,foregroundBitrate,sampleStart,sampleBytes,bandwidthWindow,bandwidthUsed;
    private double bandwidthCredit;
    private boolean closed;private int pumps;
    private java.util.function.Consumer<JSONObject> resultSink=value->{};
    synchronized void setResultSink(java.util.function.Consumer<JSONObject> sink){resultSink=sink;}
    interface FrameListener { void window(java.util.List<Entry> entries); void bytes(Entry entry); default void clear() {} }
    private FrameListener frameListener;
    synchronized void setFrameListener(FrameListener listener){frameListener=listener;if(listener!=null)publishFrameWindow();}
    private void publishFrameWindow(){if(frameListener!=null){java.util.List<Entry> wanted=new ArrayList<>();for(String token:protectedTokens){Entry e=entries.get(token);if(e!=null)wanted.add(e);}frameListener.window(wanted);}}
    private void frameBytes(Entry entry){if(frameListener!=null)frameListener.bytes(entry);}
    static final class Entry {
        final long ordinal=ORDINALS.incrementAndGet();final String token,remote;volatile String selectedRemote;List<String> mirrors=Collections.emptyList();boolean raced;long sampleEnd=-1,keyframeEnd=-1;final Map<String,String> headers;final File file;final boolean persistent;
        String frameAudioUrl="", replacementKey; volatile int maxVideoShortEdge;
        volatile boolean revoked,oversized;
        String windowToken=""; long windowGeneration;
        volatile long expiresAt=Long.MAX_VALUE;volatile long networkBytes,rangeRequests;volatile long length=-1;int captureBytes=MAX_ENTRY;
        long nextTouch;
        File tail(){return new File(file.getParentFile(),token+".tail");}
        int mirrorIndex(){return Math.max(0,mirrors.indexOf(selectedRemote));}
        Entry(String token,String remote,Map<String,String> headers,File file,boolean persistent){this.token=token;this.remote=remote;this.selectedRemote=remote;this.headers=headers;this.file=file;this.persistent=persistent;}
    }
    final class Job {
        final Entry entry;final int budget;final long epoch;volatile String itemToken;volatile long sourceGeneration;boolean background,preparing,done,preStore,requireAudio,localReserved,localResolutionInspected;Entry localAudio;String localToken,localKey;JSONObject localMetadata;volatile boolean fetching;long deferredUntil;int stage;volatile boolean active=true;volatile DataSource source;Exception failure;
        Job(Entry e,int budget,String itemToken,long sourceGeneration){entry=e;this.budget=budget;epoch=generation;this.itemToken=itemToken;this.sourceGeneration=sourceGeneration;}
        java.util.function.BooleanSupplier requestCurrent=()->true;
        PodMediaPrefixCache owner(){return PodMediaPrefixCache.this;}
        void cancel(){active=false;DataSource s=source;if(s!=null&&!cancellations.isShutdown())cancellations.execute(()->{try{s.close();}catch(Exception ignored){}});}
        boolean current(){synchronized(PodMediaPrefixCache.this){return active&&!closed&&requestCurrent.getAsBoolean()&&epoch==generation&&entries.get(entry.token)==entry;}}
    }
    PodMediaPrefixCache(File root){this(root,new PodMediaHttp(),Math.max(6L*1024*1024,Math.min(16L*1024*1024,Runtime.getRuntime().maxMemory()/4)));}
    PodMediaPrefixCache(File root,DataSource.Factory upstream,long budget){
        this.root=root;this.disk=PodMediaPrefixDisk.open(root);this.frameRoot=root;this.upstream=upstream;this.maxTotal=Math.max(MAX_ENTRY,Math.min(16L*1024*1024,budget));
        // Files are opaque media bytes, never a durable capability. Registrations
        // must be recreated with a fresh authorized remote source after restart.
        if(!root.isDirectory())root.mkdirs();
        localStore=new PodMediaLocalStore(this);trimFiles();
        // Older builds wrote a directory-wide cap without recording which
        // keys opted in. Do not use that value to claim any unmarked bytes.
        new File(root,".short-edge").delete();
        for(File file:root.listFiles()==null?new File[0]:root.listFiles()){
            String name=file.getName();
            if(name.matches("[a-f0-9]{64}\\.limit\\.tmp")){file.delete();continue;}
            if(!name.matches("[a-f0-9]{64}\\.(length|tail|limit)"))continue;
            String token=name.substring(0,64);
            if(!new File(root,token+".prefix").isFile())deleteParts(token);
        }
        for(File prefix:files()){
            String token=prefix.getName().substring(0,64);
            int limit=storedPolicy(token,0);
            if(limit<=0)continue;
            long length=prefix.length();
            try(DataInputStream input=new DataInputStream(new FileInputStream(new File(root,token+".length")))){
                length=Math.max(length,input.readLong());
            }catch(IOException absent){}
            if(PodMediaPrefixMp4.cachedExceeds(prefix,new File(root,token+".tail"),length,limit))deleteParts(token);
        }
    }
    private int storedPolicy(String token,int field){
        try(RandomAccessFile input=new RandomAccessFile(new File(root,token+".limit"),"r")){
            input.seek(field*4L);
            return input.readInt();
        }catch(IOException absent){return 0;}
    }
    private void storePolicy(Entry entry)throws IOException {
        if(entry.maxVideoShortEdge<=0)return;
        int mirror=entry.mirrorIndex();
        if(storedPolicy(entry.token,0)==entry.maxVideoShortEdge&&storedPolicy(entry.token,1)==mirror)return;
        File policy=new File(root,entry.token+".limit"),temporary=new File(root,entry.token+".limit.tmp");
        try{
            try(FileOutputStream file=new FileOutputStream(temporary);DataOutputStream output=new DataOutputStream(file)){
                output.writeInt(entry.maxVideoShortEdge);output.writeInt(mirror);
                output.flush();file.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(),policy.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally{temporary.delete();}
    }
    private static String digest(String text)throws Exception {return PodVideoFrameCache.hash(text);}
    synchronized String register(String key,String remote,Map<String,String> headers)throws Exception {return register(key,remote,headers,false);}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent)throws Exception {return register(key,remote,headers,persistent,Collections.emptyList());}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent,List<String> alternates)throws Exception {return register(key,remote,headers,persistent,alternates,"");}
    synchronized String register(String key,String remote,Map<String,String> headers,boolean persistent,List<String> alternates,String frameAudioUrl)throws Exception {
        return register(key,remote,headers,persistent,alternates,frameAudioUrl,null,0);
    }
    private String register(String key,String remote,Map<String,String> headers,boolean persistent,List<String> alternates,String frameAudioUrl,String replacesUri,int limit)throws Exception {
        if(!frameAudioUrl.isEmpty()){Uri audio=Uri.parse(frameAudioUrl);if(frameAudioUrl.length()>32768||!("https".equals(audio.getScheme())||"http".equals(audio.getScheme()))||audio.getHost()==null||audio.getUserInfo()!=null)throw new IllegalArgumentException("invalid_frame_audio_source");}
        if(closed)throw new IOException("cache_closed");Uri u=Uri.parse(remote);
        // Keep the original validation and limits; expose only a closed reason,
        // never the source, key, signed query or credentials.
        String invalid=key.isEmpty()?"key_empty":key.length()>2048?"key_length":!("https".equals(u.getScheme())||"http".equals(u.getScheme()))?"scheme":u.getHost()==null||u.getHost().isEmpty()?"host":u.getUserInfo()!=null?"credentials":remote.length()>32768?"url_length":null;
        if(invalid!=null)throw new IllegalArgumentException("invalid_media_cache_source_"+invalid);
        List<String> mirrors=new ArrayList<>();mirrors.add(remote);
        if(alternates.size()>1)throw new IllegalArgumentException("too_many_media_mirrors");
        for(String alternate:alternates){Uri m=Uri.parse(alternate);if(alternate.length()>32768||!u.getScheme().equals(m.getScheme())||m.getHost()==null||m.getHost().isEmpty()||m.getUserInfo()!=null)throw new IllegalArgumentException("invalid_media_mirror");if(!mirrors.contains(alternate))mirrors.add(alternate);}
        TreeMap<String,String> clean=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);int bytes=0;
        if(headers.size()>32)throw new IllegalArgumentException("too_many_headers");
        for(Map.Entry<String,String> h:headers.entrySet()){String name=h.getKey(),value=h.getValue();bytes+=name.length()+value.length();if(!name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0||bytes>32768)throw new IllegalArgumentException("invalid_media_cache_headers");if(!name.equalsIgnoreCase("Range"))clean.put(name,value);}
        for(String remoteUrl:mirrors)PodMediaRequestPolicy.check(okhttp3.HttpUrl.get(remoteUrl),okhttp3.Headers.of(clean));
        if(!frameAudioUrl.isEmpty())PodMediaRequestPolicy.check(okhttp3.HttpUrl.get(frameAudioUrl),okhttp3.Headers.of(clean));
        String token=digest((limit>0?"short-edge:"+limit+"\n":"")+(frameAudioUrl.isEmpty()?"":"companion:"+digest(frameAudioUrl)+"\n")+(persistent?"content-v1\n"+key:key+"\n"+remote+"\n"+clean.toString()+(mirrors.size()>1?"\n"+mirrors.get(1):"")));Entry e=entries.get(token);
        Entry superseded=null;
        if(replacesUri!=null&&!persistent){
            // The key survives guest LRU eviction. Only opt-in registrations
            // sharing that exact key can replace one another.
            Entry explicit=entries.get(token(replacesUri));
            if(explicit!=null&&explicit.maxVideoShortEdge==limit&&key.equals(explicit.replacementKey)&&!token.equals(explicit.token))superseded=explicit;
            if(superseded==null)for(Entry candidate:entries.values())if(candidate.maxVideoShortEdge==limit&&key.equals(candidate.replacementKey)&&!token.equals(candidate.token)){
                superseded=candidate;break;
            }
        }
        if(e==null||!e.remote.equals(remote)||!e.headers.equals(clean)||!e.mirrors.equals(mirrors)){
            Entry next=new Entry(token,remote,Collections.unmodifiableMap(clean),new File(root,token+".prefix"),persistent);next.mirrors=Collections.unmodifiableList(mirrors);next.frameAudioUrl=frameAudioUrl;
            // A returned URI is a process-local capability, not an evictable
            // bitmap. Reject new registrations at the byte limit without
            // invalidating addresses still held by players or the guest.
            next.replacementKey=replacesUri!=null&&!persistent?key:null;
            next.maxVideoShortEdge=limit;
            int replacing=superseded==null?0:1;
            long replacedBytes=superseded==null?0:metadataBytes(superseded);
            if(entries.size()+(e==null?1:0)-replacing>MAX_REGISTRATIONS
                ||metadataBytes()-(e==null?0:metadataBytes(e))-replacedBytes+metadataBytes(next)>MAX_METADATA_BYTES)
                throw new IOException("media_cache_metadata_budget");
            if(e!=null){Job old=jobs.remove(token);if(old!=null)old.cancel();}
            e=next;
            if(limit>0&&e.file.isFile()){
                if(storedPolicy(token,0)!=limit)deleteParts(token);
                else{
                    int mirror=storedPolicy(token,1);
                    if(mirror>0&&mirror<mirrors.size()){e.selectedRemote=mirrors.get(mirror);e.raced=true;}
                }
            }
            storePolicy(e);
            File lengthFile=new File(root,token+".length");if(lengthFile.isFile())try(DataInputStream input=new DataInputStream(new FileInputStream(lengthFile))){long length=input.readLong();if(length>=e.file.length())e.length=length;}catch(IOException ignored){}
            entries.put(token,e);
        }
        if(superseded!=null)remove(superseded);
        trim();
        return SCHEME+"://play/"+token+".mp4";
    }
    private String token(String uri){Uri u=Uri.parse(uri);if(!SCHEME.equals(u.getScheme())||!"play".equals(u.getHost()))return null;String p=u.getLastPathSegment();return p!=null&&p.matches("[a-f0-9]{64}\\.mp4")?p.substring(0,64):null;}
    synchronized Entry target(String uri)throws IOException {
        Entry entry=entries.get(token(uri));
        if(entry==null)throw new IOException("media_cache_registration_missing");
        return entry;
    }
    synchronized Entry registration(String uri){return entries.get(token(uri));}
    synchronized boolean owns(String uri){try{target(uri);return true;}catch(IOException e){return false;}}
    JSONObject execute(String method,JSONObject a)throws Exception {return execute(method,a,()->true);}
    JSONObject execute(String method,JSONObject a,java.util.function.BooleanSupplier current)throws Exception {
        if(!current.getAsBoolean())throw new IOException("media_request_cancelled");
        if(method.equals("video.cache.local.prepare"))return prepareLocal(a,current);
        if(method.equals("video.cache.local.list")){
            int limit=a.optInt(PodVideoShortEdge.KEY);
            return new JSONObject().put("items",localStore.list(limit));
        }
        if(method.equals("video.cache.local.remove")){localStore.remove(PodMediaLocalStore.token(a.getString("key")));return new JSONObject();}
        if(method.equals("video.cache.local.lookup")){JSONObject item=localStore.lookupToken(PodMediaLocalStore.token(a.getString("key")),a.optInt(PodVideoShortEdge.KEY));return new JSONObject().put("item",item==null?JSONObject.NULL:item);}
        if(method.equals("video.cache.local.watched")){localStore.watched(a.getString("key"));return new JSONObject();}
        if(method.equals("video.cache.local.clear")){cancelLocal();localStore.clear();return new JSONObject();}
        if(method.equals("video.cache.local.cancel")){cancelLocal();return new JSONObject();}
        if(method.equals("video.cache.local.configure")){localStore.configure(a);frameDiskBytes=a.optLong("frameBytes",frameDiskBytes);if(frameDiskBytes<128*1024||frameDiskBytes>10L*1024*1024)throw new IllegalArgumentException("invalid_frame_budget");if(!localStore.enabled)cancelLocal();synchronized(this){maxTotal=a.optLong("headBytes",maxTotal);if(maxTotal<128*1024||maxTotal>16L*1024*1024)throw new IllegalArgumentException("invalid_head_budget");trim();}return new JSONObject();}
        if(method.equals("video.cache.stopTraffic")){stopTraffic();return new JSONObject();}
        if(method.equals("video.cache.prepare"))return PodMediaPrefixPreparation.prepare(this,a);
        synchronized(this){
        if(method.equals("video.cache.inspect")){Entry e=target(a.getString("uri"));return new JSONObject().put("prefixBytes",e.file.length()).put("tailBytes",e.tail().length()).put("length",e.length);}
        if(method.equals("video.cache.remove")){remove(target(a.getString("uri")));return new JSONObject();}
        if(method.equals("video.cache.register")){
            if(!current.getAsBoolean())throw new IOException("media_request_cancelled");
            Map<String,String> headers=new HashMap<>();
            JSONObject values=a.optJSONObject("headers");
            if(values!=null)for(Iterator<String> keys=values.keys();keys.hasNext();){
                String key=keys.next();headers.put(key,values.getString(key));
            }
            String replacesUri=a.isNull("replacesUri")?null:a.getString("replacesUri");
            String uri=register(a.getString("key"),a.getString("url"),headers,a.optBoolean("persistent",false),
                mirrors(a),a.optString("audioUrl",""),replacesUri,Math.max(0,a.optInt(PodVideoShortEdge.KEY)));
            Entry entry=target(uri);
            entry.expiresAt=Math.min(entry.expiresAt,a.optLong("expiresAt",Long.MAX_VALUE));
            return new JSONObject().put("uri",uri);
        }
        if(method.equals("video.cache.stats"))return stats();
        if(method.equals("video.cache.clear")){windowRevision=Math.max(windowRevision,a.optLong("revision",0));long bytes=fileBytes();clear();return new JSONObject().put("removedBytes",bytes);}
        if(method.equals("video.cache.window")){
            long revision=a.optLong("revision",0);if(revision<windowRevision)return stats();windowRevision=revision;
            JSONArray items=a.optJSONArray("items");if(items==null||items.length()>MAX_ENTRIES)throw new IllegalArgumentException("invalid_media_cache_window");
            Set<String> wanted=new LinkedHashSet<>();protectedTokens.clear();foregroundBitrate=Math.max(0,a.optLong("foregroundBitrate",0));
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);Entry e;
                String uri=item.getString("uri");
                String registered=token(uri);
                if(registered==null)throw new IOException("media_cache_registration_missing");
                e=entries.get(registered);
                if(e==null)continue; // A queued window can still name a dropped registration.
                wanted.add(e.token);protectedTokens.add(e.token);int budget=item.optInt("prefetchBytes",0);if(budget<0||budget>MAX_ENTRY)throw new IllegalArgumentException("invalid_prefix_budget");e.captureBytes=Math.max(budget,Math.min(MAX_ENTRY,item.optInt("captureBytes",MAX_ENTRY)));
                String itemToken=item.optString("itemToken","");long sourceGeneration=item.optLong("generation",0);if(!itemToken.isEmpty()&&(!itemToken.matches("[A-Za-z0-9_-]{1,96}")||sourceGeneration<0||sourceGeneration>9007199254740991L))throw new IllegalArgumentException("invalid_prefetch_identity");e.windowToken=itemToken;e.windowGeneration=sourceGeneration;Job existing=jobs.get(e.token);if(budget>e.file.length()&&(existing==null||!existing.active||existing.localToken==null&&existing.budget<budget)){if(existing!=null)existing.cancel();Job j=new Job(e,budget,itemToken,sourceGeneration);j.preStore=item.optBoolean("preStore",false);jobs.put(e.token,j);}else if(existing!=null){existing.itemToken=itemToken;existing.sourceGeneration=sourceGeneration;
                    // A stock preparation promoted into the live window must no longer
                    // wait for foreground playback to end. Preserve its bytes and owner.
                    if(budget>0&&existing.localToken==null){existing.background=false;existing.deferredUntil=0;}
                }}
            for(Iterator<Map.Entry<String,Job>> it=jobs.entrySet().iterator();it.hasNext();){Map.Entry<String,Job> j=it.next();if(!wanted.contains(j.getKey())&&j.getValue().localToken==null){j.getValue().cancel();it.remove();}}
            publishFrameWindow();schedule();trim();return stats();
        }
        throw new IllegalArgumentException("unsupported_cache_method");
        }
    }
    private static void checkRevocation(Entry entry)throws IOException{
        if(entry==null||!entry.revoked)return;
        if(entry.oversized)throw new PodVideoShortEdge.Rejected();
        throw new IOException("media_cache_registration_missing");
    }
    /** Mark first to fence readers, then close their delegates outside the cache lock. */
    private void revoke(Entry entry){
        entry.revoked=true;
        Set<ObservedSource> active=foregroundSources.remove(entry);
        if(active!=null&&!cancellations.isShutdown())for(ObservedSource reader:active){
            cancellations.execute(()->{try{reader.close();}catch(IOException ignored){}});
        }
    }
    private synchronized void remove(Entry entry){
        if(entries.get(entry.token)!=entry)return;
        revoke(entry);
        Job job=jobs.remove(entry.token);
        if(job!=null){
            job.cancel();
            if(job.localAudio!=null)remove(job.localAudio);
        }
        entries.remove(entry.token);
        protectedTokens.remove(entry.token);
        deleteParts(entry.token);
        publishFrameWindow();
        notifyAll();
    }
    private void deleteParts(String token){
        synchronized(disk){
            disk.discard(token);
            for(String suffix:new String[]{".prefix",".tail",".length",".limit"})new File(root,token+suffix).delete();
            disk.update(token,new File(root,token+".prefix"));
        }
    }
    void rejectResolution(String uri){
        if(uri.startsWith("file:")){localStore.removeUri(uri);return;}
        String token=token(uri);
        if(token==null)return;
        synchronized(this){
            Entry entry=entries.get(token);
            if(entry!=null)rejectResolution(entry);
            else deleteParts(token);
        }
    }
    synchronized void rejectResolution(Entry entry){
        if(entries.get(entry.token)!=entry)return;
        entry.oversized=true;
        revoke(entry);
        if(protectedTokens.contains(entry.token)){
            Job binding=new Job(entry,0,entry.windowToken,entry.windowGeneration);
            binding.failure=new PodVideoShortEdge.Rejected();
            publish(binding,false,0);
        }
        remove(entry);
    }
    private synchronized void cancelLocal(){for(Job job:jobs.values())if(job.localToken!=null)job.cancel();notifyAll();}
    private JSONObject prepareLocal(JSONObject args,java.util.function.BooleanSupplier current)throws Exception {
        String key=args.getString("key"),token=PodMediaLocalStore.token(key);
        JSONObject metadata=args.getJSONObject("metadata");if(metadata.toString().length()>8192)throw new IllegalArgumentException("local_metadata_budget");
        JSONObject known=localStore.lookupToken(token,target(args.getString("uri")).maxVideoShortEdge);if(known!=null){PodMediaLocalStore.requireTracks(known.optInt("audioTracks"),known.optInt("videoTracks"),args.optBoolean("requireAudio"));return new JSONObject().put("ready",true).put("item",known);}
        Job job;
        synchronized(this){if(!current.getAsBoolean())throw new IOException("media_request_cancelled");Entry entry=target(args.getString("uri"));checkNetwork(entry);Job old=jobs.get(entry.token);if(old!=null)old.cancel();
            localStore.root.mkdirs();job=new Job(entry,0,"",0);job.requestCurrent=current;job.stage=2;job.preStore=true;job.localToken=token;job.localKey=key;job.localMetadata=metadata;job.requireAudio=args.optBoolean("requireAudio");
            if(!entry.frameAudioUrl.isEmpty()){String audio=register("local-audio:"+digest(key),entry.frameAudioUrl,entry.headers,entry.persistent);job.localAudio=target(audio);job.localAudio.expiresAt=Math.min(job.localAudio.expiresAt,entry.expiresAt);}
            jobs.put(entry.token,job);localStore.removePartials(token);}
        try{prefetch(job);if(job.failure!=null)throw job.failure;if(!job.current())throw new IOException("complete_cancelled");
            JSONObject value=localStore.lookupToken(token);return new JSONObject().put("ready",value!=null).put("item",value==null?JSONObject.NULL:value);
        }finally{synchronized(this){if(jobs.get(job.entry.token)==job)jobs.remove(job.entry.token);job.cancel();}localStore.removePartials(token);if(!job.done||job.failure!=null)localStore.remove(token);}
    }
    /** Transfer callbacks include HTTP 200 range-skip bytes consumed during open().
     * Sources without callbacks still account each byte returned by read(). */
    private DataSource observedSource(Entry entry){return observedSource(entry,true);}
    private DataSource observedSource(Entry entry,boolean allowRace){
        DataSource source;
        synchronized(this){boolean race=allowRace&&upstream instanceof PodMediaHttp&&!entry.raced&&entry.file.length()==0&&entry.mirrors.size()>1;
            if(race){entry.raced=true;source=((PodMediaHttp)upstream).race(entry.mirrors,url->entry.selectedRemote=url);}
            else source=upstream.createDataSource();}
        return new ObservedSource(source,entry,!allowRace);
    }
    private static List<String> mirrors(JSONObject args)throws Exception {JSONArray values=args.optJSONArray("alternates");List<String> result=new ArrayList<>();if(values!=null)for(int i=0;i<values.length();i++)result.add(values.getString(i));return result;}
    /** Two bounded fetch owners: first-frame stages first, then remaining prefixes, each in window order.
     * Foreground readers never enter this queue. Their bandwidth reserve still gates every read. */
    private synchronized void schedule(){if(closed)return;while(pumps<2){pumps++;workers.execute(()->{
        while(true){Job next=null;synchronized(PodMediaPrefixCache.this){
            for(int stage=0;stage<3&&next==null;stage++){
                for(String token:protectedTokens){Job candidate=jobs.get(token);if(eligible(candidate,stage)){next=candidate;break;}}
                if(next==null)for(Job candidate:jobs.values())if(eligible(candidate,stage)){next=candidate;break;}
            }
            if(next==null||closed){
                pumps--;
                if(!closed)scheduleDeferredWork();
                return;
            }
            next.fetching=true;
        }fetchStep(next);}
    });}}
    private final ScheduledExecutorService wakeups=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"PodMediaWake");t.setDaemon(true);return t;});
    private ScheduledFuture<?> wakeTask;
    private long wakeAt=Long.MAX_VALUE;
    private void scheduleDeferredWork(){
        long now=android.os.SystemClock.uptimeMillis(),delay=Long.MAX_VALUE;
        for(Job job:jobs.values()){
            if(job.active&&!job.done&&!job.fetching)
                delay=Math.min(delay,job.deferredUntil>now?job.deferredUntil-now:250);
        }
        if(delay==Long.MAX_VALUE)return;
        long at=now+delay;
        if(wakeTask!=null&&wakeAt<=at)return;
        if(wakeTask!=null)wakeTask.cancel(false);
        wakeAt=at;
        wakeTask=wakeups.schedule(()->{synchronized(this){if(wakeAt!=at)return;wakeAt=Long.MAX_VALUE;wakeTask=null;schedule();}},delay,TimeUnit.MILLISECONDS);
    }
    private boolean eligible(Job job,int stage){return job!=null&&job.active&&!job.done&&!job.fetching&&job.stage==stage
        &&android.os.SystemClock.uptimeMillis()>=job.deferredUntil&&(job.preStore?preStoreAllowed():!job.background||!HostWorkPriority.DEVICE.mediaActive());}
    void prefetch(Job job){
        synchronized(this){
            schedule();
            // Cancellation can come from a host request without owning this monitor.
            while(job.current()&&!job.done)try{wait(100);}catch(InterruptedException error){
                Thread.currentThread().interrupt();job.cancel();job.failure=error;break;
            }
        }
    }
    private final class ObservedSource implements DataSource {
        final DataSource delegate;final Entry entry;final boolean foreground;final long ordinal,request=ORDINALS.incrementAndGet();long transferred,opened=-1,first=-1,last=-1,rangePosition,requested;boolean logged;
        private final java.util.concurrent.atomic.AtomicBoolean disposed=new java.util.concurrent.atomic.AtomicBoolean();
        ObservedSource(DataSource delegate,Entry entry,boolean foreground){
            this.delegate=delegate;this.entry=entry;this.foreground=foreground;this.ordinal=entry.ordinal;
            delegate.addTransferListener(new TransferListener(){
                public void onTransferInitializing(DataSource source,DataSpec spec,boolean network){}
                public void onTransferStart(DataSource source,DataSpec spec,boolean network){}
                public void onBytesTransferred(DataSource source,DataSpec spec,boolean network,int bytes){if(bytes>0){synchronized(ObservedSource.this){transferred+=bytes;noteByte();}recordNetwork(entry,bytes);}}
                public void onTransferEnd(DataSource source,DataSpec spec,boolean network){}
            });
        }
        int responseCode(){return delegate instanceof androidx.media3.datasource.HttpDataSource?((androidx.media3.datasource.HttpDataSource)delegate).getResponseCode():delegate instanceof PodDouyinRequest?((PodDouyinRequest)delegate).responseCode():PodMediaHttp.responseCode(delegate);}
        public void addTransferListener(TransferListener listener){delegate.addTransferListener(listener);}
        public long open(DataSpec spec)throws IOException{
            opened=android.os.SystemClock.uptimeMillis();rangePosition=spec.position;requested=spec.length;
            synchronized(PodMediaPrefixCache.this){
                checkNetwork(entry);
                entry.rangeRequests+=PodMediaHttp.attempts(delegate,spec);
                if(foreground)foregroundSources.computeIfAbsent(entry,key->new HashSet<>()).add(this);
            }
            try{
                checkRevocation(entry);
                long size=delegate.open(spec);
                checkRevocation(entry);
                return size;
            }catch(IOException error){
                checkRevocation(entry);
                if((httpStatus(error)==403||httpStatus(error)==429)&&foreground)publish(new Job(entry,0,"cooldown",0),false,httpStatus(error));
                throw error;
            }
        }
        public int read(byte[] bytes,int offset,int length)throws IOException{
            checkNetwork(entry);
            long before=transferred;
            int n;
            try{n=delegate.read(bytes,offset,length);}
            catch(IOException error){checkRevocation(entry);throw error;}
            if(!PodMediaHttp.countsTransfers(delegate)&&n>transferred-before){
                int extra=(int)(n-(transferred-before));transferred+=extra;noteByte();recordNetwork(entry,extra);
            }
            checkRevocation(entry);
            return n;
        }
        public Uri getUri(){return delegate.getUri();}
        public Map<String,List<String>> getResponseHeaders(){return delegate.getResponseHeaders();}
        synchronized void noteByte(){long now=android.os.SystemClock.uptimeMillis();if(first<0)first=now;last=now;}
        public void close()throws IOException{
            if(!disposed.compareAndSet(false,true))return;
            if(foreground)synchronized(PodMediaPrefixCache.this){
                Set<ObservedSource> active=foregroundSources.get(entry);
                if(active!=null){active.remove(this);if(active.isEmpty())foregroundSources.remove(entry);}
            }
            int status=Math.max(0,responseCode());try{delegate.close();}finally{if(!logged&&opened>=0){logged=true;android.util.Log.i("PodMediaCache","range_transfer entry="+ordinal+" request="+request+" position="+rangePosition+" requested_bytes="+Math.max(0,requested)+" network_bytes="+transferred+" ttfb_ms="+(first<0?0:Math.max(0,first-opened))+" read_ms="+(first<0?0:Math.max(0,last-first))+" status="+status);}}}
    }
    private void fetchStep(Job job){
        if(job.localToken!=null){try{localStore.step(job);}catch(BackgroundDeferred paused){job.deferredUntil=android.os.SystemClock.uptimeMillis()+100;}catch(Exception error){job.failure=error;job.done=true;if(error instanceof PodVideoShortEdge.Rejected){synchronized(this){rejectResolution(job.entry);if(job.localAudio!=null)remove(job.localAudio);}}android.util.Log.i("PodMediaCache","complete_store phase=failed reason="+PodMediaLocalStore.failureReason(error)+" status="+httpStatus(error));}finally{synchronized(this){job.fetching=false;notifyAll();}}return;}
        boolean succeeded=false;int status=0;boolean failed=false;
        job.fetching=true;
        try{
            long position; synchronized(this){position=job.entry.file.length();}
            long goal=job.stage==0?Math.min(job.budget,64*1024):Math.max(job.budget,job.entry.sampleEnd);
            int fallbackTail=PodMediaPrefixPreparation.fallbackTailBytes(job);
            if(fallbackTail>0)goal=Math.min(goal,job.budget-fallbackTail);
            boolean pendingFallback=job.preparing&&(position<8||job.entry.length<0);
            if(job.entry.sampleEnd>MAX_ENTRY-job.entry.tail().length())throw new IOException("samples_over_budget");
            goal=Math.min(goal,MAX_ENTRY-job.entry.tail().length());
            int requests=0;
            while(job.current()&&position<goal&&(job.entry.length<0||position<job.entry.length)){
                if(++requests>64)throw new IOException("too_many_media_ranges");
                if(job.background||job.preStore)backgroundTurn(job);
                DataSource source=observedSource(job.entry);job.source=source;
                long start=position;
                try{
                    if(!job.current())break;
                    long remaining=source.open(new DataSpec.Builder().setUri(job.entry.selectedRemote).setPosition(position).setLength(goal-position).setHttpRequestHeaders(job.entry.headers).build());
                    status=((ObservedSource)source).responseCode();
                    long responseEnd=metadata(job.entry,source,position,remaining,false);
                    byte[] buffer=new byte[CHUNK];
                    while(position<goal&&job.current()){
                        if(job.background||job.preStore)backgroundTurn(job);
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
                synchronized(this){frameBytes(job.entry);}
                android.util.Log.i("PodMediaCache","keyframe_prefix entry="+job.entry.ordinal+" planned="+(job.entry.keyframeEnd>=0)+" keyframe_end="+Math.max(0,job.entry.keyframeEnd)+" network_bytes="+job.entry.networkBytes+" prefix_bytes="+job.entry.file.length()+" tail_bytes="+job.entry.tail().length());
                return;
            }
            succeeded=job.current()&&job.entry.file.length()>0&&job.entry.file.length()>=(job.entry.length>0?Math.min(goal,job.entry.length):goal);
            if(succeeded&&job.preparing)PodMediaPrefixPreparation.cacheFallback(this,job);
        }catch(BackgroundDeferred deferred){job.deferredUntil=android.os.SystemClock.uptimeMillis()+100;}
        catch(Exception error){succeeded=false;job.failure=error;if(error instanceof PodVideoShortEdge.Rejected)rejectResolution(job.entry);status=httpStatus(error);if(job.current()||status==403){failed=true;synchronized(this){failures++;}android.util.Log.i("PodMediaCache","prefetchFailure category="+(status>0?"http_"+status:"transport"));}}
        finally{synchronized(this){if(succeeded||failed||!job.current()){job.done=true;if(!job.preparing&&jobs.get(job.entry.token)==job)jobs.remove(job.entry.token);}job.fetching=false;if((succeeded||failed)&&(job.current()||status==403))publish(job,succeeded,status);notifyAll();trim();android.util.Log.i("PodMediaCache","prefetchProgress downloadedBytes="+downloadedBytes+" cacheBytes="+fileBytes());}}
    }
    /** Exact range reads account bytes even if cancellation or a short body prevents publication. */
    byte[] loadRange(Job job,long position,int count)throws Exception {
        return loadRange(job,job.entry,position,count);
    }
    byte[] loadRange(Job job,Entry entry,long position,int count)throws Exception {
        byte[] bytes=new byte[count];int used=0,requests=0;
        while(used<count){
            if(++requests>64)throw new IOException("too_many_media_ranges");
            if(job.background||job.preStore)backgroundTurn(job);else if(!job.current())throw new IOException("preparation_cancelled");DataSource source=observedSource(entry);job.source=source;int before=used;
            try{
                long remaining=source.open(new DataSpec.Builder().setUri(entry.selectedRemote).setPosition(position+used).setLength(count-used).setHttpRequestHeaders(entry.headers).build());
                long responseEnd=metadata(entry,source,position+used,remaining,false);
                while(used<count){
                    if(job.background||job.preStore)backgroundTurn(job);int wanted=Math.min(CHUNK,count-used);int n=source.read(bytes,used,wanted);
                    if(n<0){if(responseEnd<0||position+used<responseEnd)throw new EOFException("short_media_range");break;}
                    if(n>0){if(!throttle(job,n))throw new IOException("preparation_cancelled");if(responseEnd>=0&&n>responseEnd-position-used)throw new IOException("oversized_media_range");used+=n;}
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
    static final class BackgroundDeferred extends IOException {}
    static void backgroundTurn(Job job)throws Exception {
        // Never hold the admission ticket across network IO or decoding.
        if(!job.current())throw new IOException("preparation_cancelled");
        if(job.preStore){if(!job.owner().preStoreAllowed())throw new BackgroundDeferred();return;}
        try(HostWorkPriority.Ticket ticket=HostWorkPriority.DEVICE.reserve(true)){
            if(job.fetching){if(!ticket.tryEnter())throw new BackgroundDeferred();}
            else ticket.enter(job::current);
        }
        if(!job.current())throw new IOException("preparation_cancelled");
    }
    synchronized void appendMetadata(Job job,long position,byte[] bytes)throws IOException {
        if(!job.current())throw new IOException("preparation_cancelled");
        long current=job.entry.file.length();
        // The foreground loader can extend the prefix while this range is in flight.
        // Keep the response's original offset and append only its unclaimed suffix.
        if(position>current)throw new IOException("metadata_prefix_gap");
        int overlap=(int)Math.min(bytes.length,current-position);
        job.entry.captureBytes=MAX_ENTRY;
        if(overlap<bytes.length)append(job.entry,current,bytes,overlap,bytes.length-overlap,job.epoch);
        downloadedBytes+=bytes.length;
    }
    synchronized void fitPreparation(Job job,int budget)throws IOException {
        if(!job.current())throw new IOException("preparation_cancelled");
        long prefix=budget-job.entry.tail().length();
        if(prefix<0)throw new IOException("media_cache_budget_exceeded");
        if(job.entry.file.length()>prefix){
            try(RandomAccessFile file=new RandomAccessFile(job.entry.file,"rw")){file.setLength(prefix);}
            disk.update(job.entry.token,job.entry.file);
        }
    }
    synchronized void saveTail(Job job,byte[] bytes,int budget)throws Exception {
        if(!job.current())throw new IOException("preparation_cancelled");
        File temporary=File.createTempFile("tail-",".tmp",root);
        try{if(job.entry.maxVideoShortEdge>0&&!new File(root,job.entry.token+".limit").isFile())storePolicy(job.entry);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            // Publish only a complete suffix. Both ranges share the caller's byte budget.
            if(job.entry.file.length()>budget-bytes.length)try(RandomAccessFile prefix=new RandomAccessFile(job.entry.file,"rw")){prefix.setLength(budget-bytes.length);}
            java.nio.file.Files.move(temporary.toPath(),job.entry.tail().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            downloadedBytes+=bytes.length;disk.update(job.entry.token,job.entry.file);trim();
        }finally{temporary.delete();}
    }
    private synchronized void publish(Job job,boolean success,int status){
        publishResult(job.itemToken,job.sourceGeneration,success,status,
            success?"success":job.failure instanceof PodVideoShortEdge.Rejected?PodVideoShortEdge.ERROR:
            status==403?"http_403":status>=500?"http_5xx":status>=400?"http_4xx":"transport",job.entry.mirrorIndex());
    }
    private void publishResult(String token,long generation,boolean success,int status,String category,int mirrorIndex){
        if(token==null||!token.matches("[A-Za-z0-9_-]{1,96}")||generation<0||generation>9007199254740991L)return;
        try{JSONObject value=new JSONObject().put("itemToken",token).put("generation",generation).put("result",success?"success":"failure").put("httpStatus",status).put("category",category);
            if(PodVideoShortEdge.ERROR.equals(category))value.put("mirrorIndex",mirrorIndex);
            resultSink.accept(new JSONObject().put("t","video.cache.result").put("value",value));
        }catch(Exception ignored){}
    }
    static int httpStatus(Throwable error){for(Throwable x=error;x!=null;x=x.getCause())if(x instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)x).responseCode;return 0;}
    private boolean throttle(Job j,int bytes)throws InterruptedException {
        while(j.current()){
            // Idle transfers are limited by the transport itself. Only reserve bandwidth for an actual foreground stream.
            synchronized(this){long budget=foregroundBitrate<=0||foregroundNetworkReaders==0?Long.MAX_VALUE:totalBandwidth<=0?0:Math.max(0,totalBandwidth-(foregroundBitrate*13+79)/80);if(j.preStore)budget=Math.min(budget,localStore.rate);long now=System.nanoTime()/1000000L;long elapsed=bandwidthWindow==0?0:Math.max(0,now-bandwidthWindow);bandwidthWindow=now;
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
            java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes\\s+([0-9]+)-([0-9]+)/([0-9]+|\\*)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.trim());
            if(!range.matches())throw new IOException("invalid_media_range");
            try{
                long start=Long.parseLong(range.group(1)),last=Long.parseLong(range.group(2)),length=range.group(3).equals("*")?-1:Long.parseLong(range.group(3));
                if(start!=position||last<start||last==Long.MAX_VALUE||length>=0&&last>=length)throw new IOException("invalid_media_range");
                if(total>=0&&(total!=length||end!=last+1))throw new IOException("conflicting_media_range");
                total=length;end=last+1;
            }catch(NumberFormatException invalid){throw new IOException("invalid_media_range");}
        }
        if(!rangeSeen&&source instanceof ObservedSource&&((ObservedSource)source).responseCode()==206)throw new IOException("missing_media_range");
        // open() may return the one requested byte even when a server ignores
        // Range. Only an HTTP 200 Content-Length denotes the complete object.
        if(!rangeSeen&&source instanceof ObservedSource&&((ObservedSource)source).responseCode()==200){
            for(Map.Entry<String,List<String>> h:source.getResponseHeaders().entrySet())if(h.getKey()!=null&&h.getKey().equalsIgnoreCase("Content-Length"))for(String value:h.getValue())try{
                long length=Long.parseLong(value.trim());if(length<0||total>=0&&total!=length)throw new IOException("invalid_media_range");total=length;
            }catch(NumberFormatException invalid){throw new IOException("invalid_media_range");}
        }
        if(total<0&&remaining>=0&&unbounded)total=position+remaining;
        if(total>=0){if(e.length>=0&&e.length!=total)throw new IOException("media_length_changed");e.length=total;}
        persistLength(e);
        android.util.Log.i("PodMediaCache","upstreamOpen entry="+e.ordinal+" position="+position+" totalBytes="+e.length+" prefixBytes="+e.file.length());
        return end;
    }
    private void persistLength(Entry e){if(e.length>=0&&!closed&&entries.get(e.token)==e&&e.file.isFile())try(DataOutputStream output=new DataOutputStream(new FileOutputStream(new File(root,e.token+".length")))){output.writeLong(e.length);}catch(IOException ignored){}}
    private synchronized void append(Entry e,long position,byte[] buffer,int offset,int n,long epoch)throws IOException {
        if(closed||epoch!=generation||entries.get(e.token)!=e||position!=e.file.length()||position>=Math.min(e.captureBytes,MAX_ENTRY-e.tail().length()))return;
        int count=(int)Math.min(n,Math.min(e.captureBytes,MAX_ENTRY-e.tail().length())-position);if(!root.isDirectory())root.mkdirs();
        if(e.maxVideoShortEdge>0&&(position==0||!new File(root,e.token+".limit").isFile()))storePolicy(e);
        if(!disk.append(e.token,e.file,position,buffer,offset,count))return;
        if(!new File(root,e.token+".length").isFile())persistLength(e);
        trim();frameBytes(e);
    }
    synchronized int read(Entry e,long position,byte[] bytes,int offset,int size)throws IOException {
        if(position<0||entries.get(e.token)!=e)return 0;
        if(position>=e.file.length()){
            File tail=e.tail();long start=e.length-tail.length();
            if(!tail.isFile()||e.length<0||position<start||position>=e.length)return 0;
            try(RandomAccessFile f=new RandomAccessFile(tail,"r")){f.seek(position-start);int n=f.read(bytes,offset,(int)Math.min(size,e.length-position));if(n>0)cacheReadBytes+=n;return Math.max(0,n);}
        }
        int n=disk.read(e.token,e.file,position,bytes,offset,(int)Math.min(size,e.file.length()-position));if(n>0){cacheReadBytes+=n;long now=android.os.SystemClock.elapsedRealtime();if(now>=e.nextTouch){e.file.setLastModified(System.currentTimeMillis());e.nextTouch=now+1000;}}return Math.max(0,n);
    }
    private static final java.util.regex.Pattern PREFIX_NAME=java.util.regex.Pattern.compile("[a-f0-9]{64}\\.prefix");
    private static final java.util.regex.Pattern CACHE_PART=java.util.regex.Pattern.compile("[a-f0-9]{64}\\.(prefix|length|tail|limit|limit\\.tmp)");
    private File[] files(){File[] f=root.listFiles((dir,name)->PREFIX_NAME.matcher(name).matches());return f==null?new File[0]:f;}
    private long fileBytes(){return disk.bytes();}
    private void trimFiles(){
        synchronized(disk){
            File[] fs=files();
            disk.refresh(fs);
            if(!disk.exceeds(maxTotal,MAX_ENTRIES))return;
            Arrays.sort(fs,Comparator.comparingLong(File::lastModified));
            for(int pass=0;pass<2;pass++)for(File file:fs){
                if(!disk.exceeds(maxTotal,MAX_ENTRIES))return;
                String token=file.getName().substring(0,64);
                if(pass==0&&protectedTokens.contains(token))continue;
                if(pass==1&&!protectedTokens.isEmpty()&&token.equals(protectedTokens.iterator().next()))continue;
                deleteParts(token);
            }
        }
    }
    private static long metadataBytes(Entry entry){
        int selectedMax=entry.remote.length();for(String mirror:entry.mirrors)selectedMax=Math.max(selectedMax,mirror.length());
        long bytes=256+2L*(entry.token.length()+entry.remote.length()+selectedMax+entry.frameAudioUrl.length()+(entry.replacementKey==null?0:entry.replacementKey.length()));
        for(String mirror:entry.mirrors)bytes+=32+2L*mirror.length();
        for(Map.Entry<String,String> header:entry.headers.entrySet())bytes+=64+2L*(header.getKey().length()+header.getValue().length());
        return bytes;
    }
    private long metadataBytes(){long bytes=0;for(Entry entry:entries.values())bytes+=metadataBytes(entry);return bytes;}
    private void trim(){if(disk.exceeds(maxTotal,MAX_ENTRIES))trimFiles();}
    private void trimSnapshots(){File[] fs=files();Arrays.sort(fs,Comparator.comparingLong(File::lastModified).reversed());for(int i=6;i<fs.length;i++)deleteParts(fs[i].getName().substring(0,64));}
    /** One closed counter snapshot at a real foreground decoded first frame. */
    synchronized void logPlaybackStats(){
        android.util.Log.i("PodMediaCache","cache_stats mono_ms="+android.os.SystemClock.elapsedRealtime()+" files="+disk.count()+" bytes="+fileBytes()+" downloadedBytes="+downloadedBytes+" cacheReadBytes="+cacheReadBytes+" networkReadBytes="+networkReadBytes+" maxBytes="+maxTotal);
    }
    synchronized void logFirstCardStats(long seq){
        android.util.Log.i("PodMediaCache","cache_first_card seq="+seq+" mono_ms="+android.os.SystemClock.uptimeMillis()+" files="+disk.count()+" bytes="+fileBytes()+" downloadedBytes="+downloadedBytes+" cacheReadBytes="+cacheReadBytes+" networkReadBytes="+networkReadBytes+" maxBytes="+maxTotal);
    }
    synchronized JSONObject stats()throws Exception {return new JSONObject().put("files",disk.count()).put("bytes",fileBytes()).put("maxBytes",maxTotal).put("maxEntries",MAX_ENTRIES).put("activePrefetches",jobs.size()).put("downloadedBytes",downloadedBytes).put("networkReadBytes",networkReadBytes).put("cacheReadBytes",cacheReadBytes).put("failures",failures).put("bandwidthBytesPerSecond",totalBandwidth);}
    synchronized JSONObject memoryStats()throws Exception {
        return new JSONObject().put("entries",entries.size()).put("metadataUtf16BytesUpperBound",metadataBytes())
            .put("metadataMaxBytes",MAX_METADATA_BYTES).put("registrationMaxEntries",MAX_REGISTRATIONS)
            .put("jobs",jobs.size()).put("protectedEntries",protectedTokens.size()).put("activePumps",pumps).put("diskMaxBytes",maxTotal);
    }
    private void clear(){
        trafficStoppedUntil=0;
        if(upstream instanceof PodMediaHttp)((PodMediaHttp)upstream).recover();
        verifiedLocal.clear();
        if(frameListener!=null)frameListener.clear();
        generation++;
        for(Job job:jobs.values())job.cancel();
        jobs.clear();
        for(Entry entry:entries.values())revoke(entry);
        entries.clear();
        protectedTokens.clear();
        synchronized(disk){
            disk.closeHandles();
            File[] parts=root.listFiles((dir,name)->CACHE_PART.matcher(name).matches());
            if(parts!=null)for(File part:parts)part.delete();
            disk.refresh(files());
        }
    }
    @Override public synchronized void close(){closed=true;disk.closeHandles();verifiedLocal.clear();generation++;for(Job j:jobs.values())j.cancel();jobs.clear();protectedTokens.clear();for(Entry entry:entries.values())revoke(entry);entries.clear();wakeups.shutdownNow();workers.shutdownNow();cancellations.shutdown();if(upstream instanceof PodMediaHttp)((PodMediaHttp)upstream).close();notifyAll();}
    DataSource.Factory factory(DataSource.Factory fallback){return factory(fallback,false);}
    DataSource.Factory factory(DataSource.Factory fallback,boolean bypass){return factory(fallback,bypass,null);}
    DataSource.Factory factory(DataSource.Factory fallback,boolean bypass,PodVideoHttpDataSources.PrepareGate gate){
        return ()->new PrefixDataSource(fallback,bypass,gate);
    }
    private final class PrefixDataSource implements DataSource {
        private final DataSource.Factory fallback;private final boolean bypass;private final PodVideoHttpDataSources.PrepareGate gate;private final List<TransferListener> listeners=new ArrayList<>();private DataSource network;private DataSpec spec;private Entry entry;private long position,remaining,epoch;private boolean ended,hitLogged,networkCounted,cacheTransfer;private String localPin;
        PrefixDataSource(DataSource.Factory f,boolean bypass,PodVideoHttpDataSources.PrepareGate gate){fallback=f;this.bypass=bypass;this.gate=gate;}
        private boolean bypassCache(){return bypass&&(gate==null||!gate.cacheOnly());}
        @Override public void addTransferListener(TransferListener l){listeners.add(l);if(network!=null)network.addTransferListener(l);}
        @Override public long open(DataSpec data)throws IOException {
            close();spec=data;position=data.position;remaining=data.length;epoch=generation;ended=false;
            if(SCHEME.equals(data.uri.getScheme())){entry=target(data.uri.toString());cacheTransfer=true;for(TransferListener l:listeners){l.onTransferInitializing(this,data,false);l.onTransferStart(this,data,false);}if(!bypassCache()&&entry.length>=0&&position>=entry.length){ended=true;return 0;}if(bypassCache()||position>=entry.file.length()&&!(entry.tail().isFile()&&entry.length>=0&&position>=entry.length-entry.tail().length()&&position<entry.length))openNetwork();return remaining>=0?remaining:entry.length>=0?Math.max(0,entry.length-position):C.LENGTH_UNSET;}
            if("file".equals(data.uri.getScheme())){File file=new File(data.uri.getPath());if(file.getParentFile().equals(localStore.root)&&file.getName().matches("[a-f0-9]{64}\\.mp4")){localPin=file.getName().substring(0,64);synchronized(PodMediaPrefixCache.this){pinnedLocal.put(localPin,pinnedLocal.getOrDefault(localPin,0)+1);}}}
            network=fallback.createDataSource();for(TransferListener l:listeners)network.addTransferListener(l);return network.open(data);
        }
        private void openNetwork()throws IOException {if(network!=null)return;if(gate!=null)gate.requireUpstream();network=observedSource(entry,false);for(TransferListener l:listeners)network.addTransferListener(l);Map<String,String> h=new HashMap<>(spec.httpRequestHeaders);h.putAll(entry.headers);DataSpec next=spec.buildUpon().setUri(entry.selectedRemote).setPosition(position).setLength(remaining).setHttpRequestHeaders(h).build();beginNetworkReader();try{long length=network.open(next);metadata(entry,network,position,length,remaining==C.LENGTH_UNSET);}catch(IOException|RuntimeException error){try{network.close();}catch(IOException ignored){}network=null;releaseNetworkReader();int status=httpStatus(error);android.util.Log.i("PodMediaCache","upstreamFailure category="+(status>0?"http_"+status:"transport"));throw error;}finally{releaseNetworkReader();}}
        @Override public int read(byte[] b,int offset,int length)throws IOException {
            checkRevocation(entry);if(length==0)return 0;if(ended||remaining==0)return C.RESULT_END_OF_INPUT;if(spec==null)throw new IOException("source_not_open");int wanted=remaining<0?length:(int)Math.min(length,remaining);int n=0;
            if(entry!=null&&network==null){n=PodMediaPrefixCache.this.read(entry,position,b,offset,wanted);if(n>0)for(TransferListener l:listeners)l.onBytesTransferred(this,spec,false,n);if(n>0&&!hitLogged){hitLogged=true;android.util.Log.i("PodMediaCache","prefixHit bytes="+n+" position="+position);}}
            if(n==0){if(entry!=null&&entry.length>=0&&position>=entry.length){ended=true;return C.RESULT_END_OF_INPUT;}if(network==null)openNetwork();if(entry!=null)beginNetworkReader();try{n=network.read(b,offset,wanted);}finally{releaseNetworkReader();}if(n<0){ended=true;releaseNetworkReader();return n;}if(entry!=null&&!bypassCache()){append(entry,position,b,offset,n,epoch);}}
            checkRevocation(entry);position+=n;if(remaining>=0){remaining-=n;if(remaining==0)releaseNetworkReader();}return n;
        }
        // A paused loader can retain an open response without using bandwidth.
        // Reserve only while open/read is performing network IO.
        private void beginNetworkReader(){synchronized(PodMediaPrefixCache.this){if(!networkCounted){networkCounted=true;foregroundNetworkReaders++;}}}
        private void releaseNetworkReader(){synchronized(PodMediaPrefixCache.this){if(networkCounted){networkCounted=false;foregroundNetworkReaders--;}}}
        @Override public Uri getUri(){return spec==null?null:spec.uri;}
        @Override public Map<String,List<String>> getResponseHeaders(){return network==null?Collections.emptyMap():network.getResponseHeaders();}
        @Override public void close()throws IOException {DataSource n=network;network=null;if(cacheTransfer){cacheTransfer=false;for(TransferListener l:listeners)l.onTransferEnd(this,spec,false);}if(localPin!=null){synchronized(PodMediaPrefixCache.this){int pins=pinnedLocal.getOrDefault(localPin,1)-1;if(pins==0)pinnedLocal.remove(localPin);else pinnedLocal.put(localPin,pins);}localPin=null;}spec=null;entry=null;position=0;remaining=C.LENGTH_UNSET;ended=false;hitLogged=false;try{if(n!=null)n.close();}finally{releaseNetworkReader();}}
    }
}
