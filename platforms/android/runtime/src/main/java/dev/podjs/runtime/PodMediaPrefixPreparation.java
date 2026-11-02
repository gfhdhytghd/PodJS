package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import org.json.JSONObject;
import java.io.*;

/** Bounded persistent preparation. Decoding is cache-only: a gap fails readiness,
 * rather than secretly downloading on the first-frame validation path. */
final class PodMediaPrefixPreparation {
    static final int TAIL_BYTES=512*1024;
    static JSONObject prepare(PodMediaPrefixCache cache,JSONObject args)throws Exception {
        int budget=args.getInt("bytes");
        if(budget<128*1024||budget>PodMediaPrefixCache.MAX_ENTRY)throw new IllegalArgumentException("invalid_prefix_budget");
        String key=PodVideoFrameCache.key(new JSONObject().put("key",args.getString("frameKey")));
        String uri=args.getString("uri");
        PodMediaPrefixCache.Job job=cache.beginPreparation(uri,budget);
        trace("started","none","none",job.entry);
        try {
            cache.prefetch(job);
            if(job.failure!=null)throw job.failure;
            PodMediaPrefixCache.backgroundTurn(job);
            PodMediaPrefixCache.Entry entry=job.entry;
            int count=(int)entry.tail().length();
            JSONObject result=new JSONObject().put("ready",false).put("prefixBytes",entry.file.length()).put("tailBytes",entry.tail().length());
            long required=Math.min(entry.length,Math.min(job.budget,budget-entry.tail().length()));
            if(entry.length<=0||entry.file.length()<required||entry.file.length()+entry.tail().length()>PodMediaPrefixCache.MAX_ENTRY){trace("complete",entry.length<=0?"length_unknown":"prefix_incomplete","none",entry);return result;}
            PodMediaPrefixCache.backgroundTurn(job);
            Bitmap frame=decode(cache,entry);
            if(frame==null){trace("complete","frame_unavailable","none",entry);return result;}
            try {
                PodMediaPrefixCache.backgroundTurn(job);
                String binding=PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache);
                JSONObject saved;
                synchronized(cache){
                    if(!job.current())throw new IOException("preparation_cancelled");
                    // A concurrent cache eviction must not leave a durable ready marker.
                    if(entry.file.length()<required||entry.tail().length()!=count)return result;
                    saved=new PodVideoFrameCache(cache.frameRoot).save(key,binding,0,0,frame);
                }
                trace("complete","ready","none",entry);return result.put("ready",true).put("frame",saved);
            }finally{frame.recycle();}
        }catch(Exception error){trace("failed",error.getMessage()!=null&&error.getMessage().contains("cancelled")?"cancelled":"prepare_failure",kind(error),job.entry);throw error;}finally{cache.endPreparation(job);}
    }
    /** Called by the fetch owner before releasing a preparation job. */
    static void cacheFallback(PodMediaPrefixCache cache,PodMediaPrefixCache.Job job)throws Exception {
        PodMediaPrefixCache.Entry entry=job.entry;int budget=job.budget;
        // Unknown containers retain the legacy bounded suffix fallback.
        byte[] magic=new byte[8];try(RandomAccessFile input=new RandomAccessFile(entry.file,"r")){if(input.length()>=8)input.readFully(magic);}
        if(!(magic[4]=='f'&&magic[5]=='t'&&magic[6]=='y'&&magic[7]=='p')&&entry.length>entry.file.length()){
            int tail=(int)Math.min(Math.min(TAIL_BYTES,budget/2),entry.length);cache.fitPreparation(job,Math.max(0,budget-tail));
            cache.saveTail(job,cache.loadRange(job,entry.length-tail,tail),budget);
        }
    }
    private static String kind(Throwable error){return error instanceof java.io.IOException?"io":error instanceof IllegalArgumentException?"illegal_argument":error instanceof IllegalStateException?"illegal_state":error instanceof SecurityException?"security":"runtime";}
    private static void trace(String phase,String reason,String kind,PodMediaPrefixCache.Entry entry){
        android.util.Log.i("PodMediaCache","stock_prepare phase="+phase+" reason="+reason+" exception_kind="+kind+" prefixBytes="+entry.file.length()+" tailBytes="+entry.tail().length()+" totalBytes="+Math.max(0,entry.length));
    }
    private static Bitmap decode(PodMediaPrefixCache cache,PodMediaPrefixCache.Entry entry)throws Exception {
        java.util.concurrent.atomic.AtomicBoolean gap=new java.util.concurrent.atomic.AtomicBoolean();
        try(MediaDataSource bytes=new MediaDataSource(){
            public long getSize(){return entry.length;}
            public int readAt(long position,byte[] target,int offset,int size)throws IOException {
                if(size==0)return 0;
                if(position>=entry.length)return -1;
                int n=cache.read(entry,position,target,offset,size);
                if(n<=0){gap.set(true);trace("decode","uncached_gap","io",entry);android.util.Log.i("PodMediaCache","stock_gap position="+position+" requestedBytes="+size+" prefixBytes="+entry.file.length()+" tailStart="+(entry.length-entry.tail().length())+" totalBytes="+entry.length);throw new IOException("uncached_media_gap");}return n;
            }
            public void close(){}
        }){
            MediaMetadataRetriever decoder=new MediaMetadataRetriever();
            try{
                decoder.setDataSource(bytes);Bitmap frame=decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320);
                if(gap.get()&&frame!=null){frame.recycle();return null;}
                return frame;
            }
            catch(RuntimeException unavailable){trace("decode","decoder_failure",kind(unavailable),entry);return null;}
            finally{decoder.release();}
        }
    }
}
