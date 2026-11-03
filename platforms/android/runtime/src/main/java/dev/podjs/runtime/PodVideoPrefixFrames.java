package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cache-only, serialized frame decode. Hardware uses a spare shared lease and
 * ImageReader; software is the fallback. No player or AudioTrack is created.
 * Uncached data is EOF, never a hidden network request. */
final class PodVideoPrefixFrames implements PodMediaPrefixCache.FrameListener, AutoCloseable {
    private final PodMediaPrefixCache cache;
    private final PodVideoDecoderBudget decoderBudget;
    private boolean hardwareFailed,decodeCancelled;
    private final Handler main;
    private final PodVideoKeyframeDisk disk;
    private final ExecutorService diskWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(PodVideoKeyframes.MAX_ENTRIES),r->{Thread t=new Thread(r,"PodKeyframeDisk");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Set<PodMediaPrefixCache.Entry> publishing=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final PodVideoKeyframes frames;
    private final java.util.function.Consumer<String> ready;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"PodPrefixFrame");t.setDaemon(true);return t;});
    private final LinkedHashMap<PodMediaPrefixCache.Entry, Long> wanted=new LinkedHashMap<>();
    // Owned by worker. Entries are trimmed to the current window on each pass.
    private final Map<PodMediaPrefixCache.Entry, Long> attempted=new HashMap<>();
    private final Map<PodMediaPrefixCache.Entry, String> bindings=new HashMap<>();
    private volatile boolean closed;
    private boolean scheduled,dirty,hasWindow;
    static final long MAX_PENDING_BYTES=(long)PodVideoKeyframes.MAX_EDGE*PodVideoKeyframes.MAX_EDGE*4;
    private long pendingBytes;
    PodVideoPrefixFrames(PodMediaPrefixCache cache,Handler main,PodVideoKeyframes frames,java.util.function.Consumer<String> ready){
        this(cache,main,frames,ready,null);
    }
    PodVideoPrefixFrames(PodMediaPrefixCache cache,Handler main,PodVideoKeyframes frames,java.util.function.Consumer<String> ready,PodVideoDecoderBudget budget){
        this.cache=cache;this.main=main;this.frames=frames;this.ready=ready;this.decoderBudget=budget;
        disk=new PodVideoKeyframeDisk(new java.io.File(cache.root,"decoded-keyframes"));
        frames.onStored(this::persist);cache.setFrameListener(this);
    }
    private void persist(String binding,PodVideoKeyframes.Frame frame){
        if(closed)return;
        // The pin is acquired on main before handing immutable pixels to IO.
        // No bitmap copy, compression or disk access occurs in this callback.
        frames.pin(frame);
        try{diskWorker.execute(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            long start=SystemClock.uptimeMillis();int status=1;
            try{disk.save(binding,frame.pixels);}catch(Exception unavailable){status=0;}
            finally{main.post(()->frames.unpin(frame));}
            android.util.Log.i("PodMediaCache","frame_disk_save status="+status+" duration_ms="+(SystemClock.uptimeMillis()-start));
        });}catch(java.util.concurrent.RejectedExecutionException busy){frames.unpin(frame);}
    }
    @Override public synchronized void clear(){
        wanted.clear();
        if(!closed)diskWorker.execute(disk::clear);
    }
    @Override public synchronized void window(List<PodMediaPrefixCache.Entry> entries){
        if(closed)return;
        if(!new java.util.ArrayList<>(wanted.keySet()).equals(entries)){
            long at=SystemClock.uptimeMillis();int rank=0;
            for(PodMediaPrefixCache.Entry entry:entries)android.util.Log.i("PodMediaCache","frame_window entry="+entry.ordinal+" rank="+(rank++)+" count="+entries.size()+" mono_ms="+at);
            if(entries.isEmpty())android.util.Log.i("PodMediaCache","frame_window entry=0 rank=0 count=0 mono_ms="+at);
        }
        LinkedHashMap<PodMediaPrefixCache.Entry,Long> next=new LinkedHashMap<>();
        for(PodMediaPrefixCache.Entry entry:entries)next.put(entry,wanted.getOrDefault(entry,SystemClock.uptimeMillis()));
        wanted.clear();wanted.putAll(next);signal();
    }
    @Override public synchronized void bytes(PodMediaPrefixCache.Entry entry){if(wanted.containsKey(entry))signal();}
    private synchronized boolean current(PodMediaPrefixCache.Entry entry){return !closed&&wanted.containsKey(entry);}
    /** A burst of byte writes produces one worker turn, never one main-thread
     * callback per write. File stats, source binding and decode stay off main. */
    private synchronized void signal(){
        if(closed)return;dirty=true;
        if(!scheduled){scheduled=true;worker.execute(this::pump);}
    }
    private void pump(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        for(;;){
            LinkedHashMap<PodMediaPrefixCache.Entry,Long> snapshot;
            synchronized(this){
                if(closed||!dirty){scheduled=false;return;}
                dirty=false;snapshot=new LinkedHashMap<>(wanted);
            }
            attempted.keySet().retainAll(snapshot.keySet());bindings.keySet().retainAll(snapshot.keySet());
            java.util.Set<String> retained=new java.util.LinkedHashSet<>();
            for(PodMediaPrefixCache.Entry entry:snapshot.keySet())try{
                String binding=bindings.get(entry);
                if(binding==null){binding=PodVideoFrameCache.binding(new JSONObject().put("url",PodMediaPrefixCache.SCHEME+"://play/"+entry.token+".mp4"),cache);bindings.put(entry,binding);}
                retained.add(binding);
            }catch(Exception unavailable){}
            if(!snapshot.isEmpty())hasWindow=true;
            if(hasWindow)frames.retainWindow(retained);
            for(Map.Entry<PodMediaPrefixCache.Entry,Long> item:snapshot.entrySet()){
                PodMediaPrefixCache.Entry entry=item.getKey();
                // One staged RGBA frame bounds the main-thread handoff even
                // when a blocked UI cannot consume worker results promptly.
                synchronized(this){if(pendingBytes>0)break;}
                if(!current(entry)||publishing.contains(entry))continue;
                long available=entry.file.length()+entry.tail().length();
                String binding=bindings.get(entry);
                if(binding==null)try{
                    binding=PodVideoFrameCache.binding(new JSONObject().put("url",PodMediaPrefixCache.SCHEME+"://play/"+entry.token+".mp4"),cache);
                    bindings.put(entry,binding);
                }catch(Exception unavailable){continue;}
                if(frames.contains(binding)||!frames.canAccept(binding))continue;
                long requested=item.getValue(),started=SystemClock.uptimeMillis();
                Bitmap result=null;int status=6;
                try{result=disk.load(binding);}catch(RuntimeException unavailable){/* Invalid compressed data falls through to cached source decode. */}
                if(result!=null)android.util.Log.i("PodMediaCache","frame_disk_load entry="+entry.ordinal+" duration_ms="+(SystemClock.uptimeMillis()-started));
                else {
                    if(entry.length<=0||available<8192||attempted.getOrDefault(entry,-1L)==available)continue;
                    attempted.put(entry,available);trace(entry,1,requested,started,0,available);
                    android.os.Trace.beginSection("PodPrefixFrame.decode");
                    try{
                        try{result=decode(entry,false);}catch(PodVideoHardwarePixels.Unavailable hardwareUnavailable){result=decode(entry,true);}
                        status=result==null?3:2;if(decodeCancelled)attempted.remove(entry);
                    }catch(Exception unavailable){status=4;}
                    finally{android.os.Trace.endSection();}
                }
                final Bitmap pixels=result;final int outcome=status;final String key=binding;
                long ended=SystemClock.uptimeMillis();
                final long staged=pixels==null?0:pixels.getAllocationByteCount();
                synchronized(this){pendingBytes+=staged;}
                publishing.add(entry);
                main.post(()->{
                    android.os.Trace.beginSection("PodPrefixFrame.publish");
                    try{
                        if(!current(entry)){if(pixels!=null)pixels.recycle();trace(entry,5,requested,started,ended,available);}
                        else {
                            if(pixels!=null){PodVideoKeyframes.Frame frame=frames.put(key,pixels,outcome!=6,entry.ordinal);if(frame!=null)ready.accept(key);}
                            trace(entry,outcome,requested,started,ended,available);
                        }
                    }finally{
                        publishing.remove(entry);
                        synchronized(PodVideoPrefixFrames.this){pendingBytes-=staged;if(staged>0)signal();}
                        android.os.Trace.endSection();
                    }
                });
            }
        }
    }
    private static void trace(PodMediaPrefixCache.Entry e,int stage,long request,long start,long end,long bytes){
        android.util.Log.i("PodMediaCache","frame_fill entry="+e.ordinal+" stage="+stage+" request_ms="+request+" start_ms="+start+" end_ms="+end+" mono_ms="+SystemClock.uptimeMillis()+" prefix_bytes="+bytes);
    }
    private Bitmap decode(PodMediaPrefixCache.Entry entry,boolean softwareOnly)throws Exception {
        decodeCancelled=false;final long decodeStarted=SystemClock.uptimeMillis();
        final boolean[] hardwareLease={false};
        if(!current(entry))return null;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try(MediaDataSource bytes=new MediaDataSource(){
            public long getSize(){return entry.length;}
            public int readAt(long position,byte[] output,int offset,int count)throws java.io.IOException {
                if(!current(entry)||hardwareLease[0]&&decoderBudget.spareCancelled())throw new java.io.IOException("frame_cancelled");
                if(count==0)return 0;if(position>=entry.length)return -1;
                int n=cache.read(entry,position,output,offset,count);return n>0?n:-1;
            }
            public void close(){}
        }){
            MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;android.media.ImageReader reader=null;boolean hardware=false,decoded=false,backend=false;
            try{
                extractor.setDataSource(bytes);MediaFormat format=null;
                for(int i=0;i<extractor.getTrackCount();i++){
                    MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);
                    if(mime!=null&&mime.startsWith("video/")){format=candidate;extractor.selectTrack(i);break;}
                }
                if(format==null||!current(entry))return null;
                int width=format.getInteger(MediaFormat.KEY_WIDTH),height=format.getInteger(MediaFormat.KEY_HEIGHT);
                if(width<1||height<1||(long)width*height>1920L*1080)return null;
                String mime=format.getString(MediaFormat.KEY_MIME),name=null;
                if(!softwareOnly&&!hardwareFailed&&android.os.Build.VERSION.SDK_INT>=29&&decoderBudget!=null){
                    name=PodVideoHardwarePixels.decoder(mime,width,height);
                    if(name!=null)hardware=decoderBudget.acquireSpare();hardwareLease[0]=hardware;
                }
                if(!hardware)name=softwareDecoder(mime);
                if(name==null)return null;
                if(hardware)reader=android.media.ImageReader.newInstance(width,height,ImageFormat.PRIVATE,2,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
                else format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                android.util.Log.i("PodMediaCache","frame_backend entry="+entry.ordinal+" hardware="+(hardware?1:0)+" live="+(decoderBudget==null?0:decoderBudget.inUse()));
                backend=true;codec=MediaCodec.createByCodecName(name);codec.configure(format,reader==null?null:reader.getSurface(),null,0);codec.start();
                long deadline=SystemClock.uptimeMillis()+800;boolean sample=false,eos=false;
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                while(current(entry)&&!(hardware&&decoderBudget.spareCancelled())&&SystemClock.uptimeMillis()<deadline){
                    if(!eos){int input=codec.dequeueInputBuffer(0);if(input>=0){
                        ByteBuffer buffer=codec.getInputBuffer(input);long expected=sample?-1:extractor.getSampleSize();
                        if(expected>buffer.capacity())return null;
                        int size=sample?-1:extractor.readSampleData(buffer,0);
                        if(size>=0&&size!=expected)return null;
                        if(size<0){codec.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true;}
                        else {if((extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)==0)return null;codec.queueInputBuffer(input,0,size,extractor.getSampleTime(),0);sample=true;}
                    }}
                    int output=codec.dequeueOutputBuffer(info,10_000);
                    if(output>=0){
                        Bitmap pixels=null;
                        if(reader==null){
                            try(Image image=codec.getOutputImage(output)){if(image!=null&&info.size>0)pixels=bitmap(image);}
                            finally{codec.releaseOutputBuffer(output,false);}
                        }else{
                            codec.releaseOutputBuffer(output,true);Image image=null;long imageDeadline=SystemClock.uptimeMillis()+500;
                            while(current(entry)&&!decoderBudget.spareCancelled()&&SystemClock.uptimeMillis()<imageDeadline&&(image=reader.acquireNextImage())==null)Thread.sleep(1);
                            if(image==null){if(!current(entry)||decoderBudget.spareCancelled())return null;throw new java.io.IOException("keyframe_image_deadline");}
                            try{try{pixels=PodVideoHardwarePixels.bitmap(image);}catch(Exception unavailable){hardwareFailed=true;android.util.Log.i("PodMediaCache","frame_hardware_disabled entry="+entry.ordinal+" status=2");throw unavailable;}}finally{image.close();}
                        }
                        if(pixels!=null){
                            int rotation=format.containsKey(MediaFormat.KEY_ROTATION)?format.getInteger(MediaFormat.KEY_ROTATION):0;
                            if(rotation!=0){android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.postRotate(rotation);Bitmap rotated=Bitmap.createBitmap(pixels,0,0,pixels.getWidth(),pixels.getHeight(),matrix,true);if(rotated!=pixels)pixels.recycle();pixels=rotated;}
                            decoded=true;return pixels;
                        }
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return null;
                    }
                }
                return null;
            }catch(Exception error){
                if(hardware){if(!current(entry)||decoderBudget.spareCancelled())return null;throw new PodVideoHardwarePixels.Unavailable(error);}
                throw error;
            }finally{
                try{if(codec!=null){try{codec.stop();}finally{codec.release();}}}
                finally{
                    try{if(reader!=null)reader.close();}finally{
                        boolean cancelled=hardware&&decoderBudget.spareCancelled();decodeCancelled=cancelled;
                        try{if(hardware)decoderBudget.releaseSpare();}finally{extractor.release();}
                        if(backend)android.util.Log.i("PodMediaCache","frame_decode entry="+entry.ordinal+" hardware="+(hardware?1:0)+" success="+(decoded?1:0)+" cancelled="+(cancelled?1:0)+" duration_ms="+(SystemClock.uptimeMillis()-decodeStarted));
                    }
                }
            }
        }
    }
    static String softwareDecoder(String mime){
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder())continue;
            String name=info.getName();boolean software=android.os.Build.VERSION.SDK_INT>=29?info.isSoftwareOnly():name.startsWith("OMX.google.")||name.startsWith("c2.android.");
            if(software)for(String type:info.getSupportedTypes())if(type.equals(mime))return name;
        }
        return null;
    }
    static Bitmap bitmap(Image image){
        if(image.getFormat()!=ImageFormat.YUV_420_888||image.getPlanes().length!=3)return null;
        Image.Plane[] planes=image.getPlanes();
        return bitmap(image.getCropRect(),new ByteBuffer[]{planes[0].getBuffer(),planes[1].getBuffer(),planes[2].getBuffer()},
            new int[]{planes[0].getRowStride(),planes[1].getRowStride(),planes[2].getRowStride()},
            new int[]{planes[0].getPixelStride(),planes[1].getPixelStride(),planes[2].getPixelStride()});
    }
    static Bitmap bitmap(Rect crop,ByteBuffer[] planes,int[] rows,int[] steps){
        int w=crop.width(),h=crop.height();if(w<1||h<1)return null;
        double scale=Math.min(1,PodVideoKeyframes.MAX_EDGE/(double)Math.max(w,h));
        int dw=Math.max(1,(int)Math.round(w*scale)),dh=Math.max(1,(int)Math.round(h*scale));int[] rgba=new int[dw*dh];
        byte[][] data=new byte[3][];for(int i=0;i<3;i++){ByteBuffer source=planes[i].duplicate();data[i]=new byte[source.remaining()];source.get(data[i]);}
        for(int y=0;y<dh;y++)for(int x=0;x<dw;x++){
            int sx=crop.left+x*w/dw,sy=crop.top+y*h/dh;
            int l=plane(data[0],rows[0],steps[0],sx,sy)-16,u=plane(data[1],rows[1],steps[1],sx/2,sy/2)-128,v=plane(data[2],rows[2],steps[2],sx/2,sy/2)-128;
            int c=298*Math.max(0,l);int red=clamp((c+409*v+128)>>8),green=clamp((c-100*u-208*v+128)>>8),blue=clamp((c+516*u+128)>>8);
            rgba[y*dw+x]=0xff000000|(red<<16)|(green<<8)|blue;
        }
        return Bitmap.createBitmap(rgba,dw,dh,Bitmap.Config.ARGB_8888);
    }
    private static int plane(byte[] buffer,int row,int step,int x,int y){return buffer[y*row+x*step]&255;}
    private static int clamp(int value){return Math.max(0,Math.min(255,value));}
    synchronized JSONObject memoryStats()throws Exception {
        return new JSONObject().put("windowEntries",wanted.size()).put("publishing",publishing.size())
            .put("diskQueue",((java.util.concurrent.ThreadPoolExecutor)diskWorker).getQueue().size())
            .put("pendingBytes",pendingBytes).put("pendingMaxBytes",MAX_PENDING_BYTES)
            .put("hardwareDisabled",hardwareFailed).put("diskMaxBytes",PodVideoKeyframeDisk.MAX_BYTES);
    }
    @Override public void close(){closed=true;frames.onStored(null);cache.setFrameListener(null);synchronized(this){wanted.clear();}worker.shutdownNow();diskWorker.shutdown();}
}
