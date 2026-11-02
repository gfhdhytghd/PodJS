package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import org.json.JSONObject;
import java.io.*;

/** Bounded persistent preparation. Decoding is cache-only: a gap fails readiness,
 * rather than secretly downloading on the first-frame validation path. */
final class PodMediaPrefixPreparation {
    static final int TAIL_BYTES=512*1024;
    static JSONObject prepare(PodMediaPrefixCache cache,JSONObject args)throws Exception {
        int budget=args.getInt("bytes");
        if(budget<512*1024||budget>PodMediaPrefixCache.MAX_ENTRY)throw new IllegalArgumentException("invalid_prefix_budget");
        String key=PodVideoFrameCache.key(new JSONObject().put("key",args.getString("frameKey")));
        String uri=args.getString("uri");
        PodMediaPrefixCache.Job job=cache.beginPreparation(uri,Math.max(512*1024,budget-TAIL_BYTES));
        try {
            cache.prefetch(job);
            PodMediaPrefixCache.backgroundTurn(job);
            PodMediaPrefixCache.Entry entry=job.entry;
            // The last bounded range includes MP4 metadata when moov follows mdat.
            if(entry.length>entry.file.length()&&entry.length>0){
                int count=(int)Math.min(TAIL_BYTES,Math.min(entry.length-entry.file.length(),budget-entry.file.length()));
                if(count>0&&entry.tail().length()!=count){
                    byte[] bytes=new byte[count];int used=0;
                    DataSource source=cache.upstream.createDataSource();job.source=source;
                    try{
                        PodMediaPrefixCache.backgroundTurn(job);
                        source.open(new DataSpec.Builder().setUri(entry.remote).setPosition(entry.length-count).setLength(count).setHttpRequestHeaders(entry.headers).build());
                        while(used<count){PodMediaPrefixCache.backgroundTurn(job);int n=source.read(bytes,used,Math.min(PodMediaPrefixCache.CHUNK,count-used));if(n<0)throw new EOFException("short_tail");if(n>0)used+=n;}
                        cache.saveTail(job,bytes);
                    }finally{source.close();job.source=null;}
                }
            }
            JSONObject result=new JSONObject().put("ready",false).put("prefixBytes",entry.file.length()).put("tailBytes",entry.tail().length());
            if(entry.length<=0||entry.file.length()<Math.min(entry.length,job.budget))return result;
            PodMediaPrefixCache.backgroundTurn(job);
            Bitmap frame=decode(cache,entry);
            if(frame==null)return result;
            try {
                PodMediaPrefixCache.backgroundTurn(job);
                String binding=PodVideoFrameCache.binding(new JSONObject().put("url",uri),cache);
                JSONObject saved;
                synchronized(cache){
                    if(!job.current())throw new IOException("preparation_cancelled");
                    saved=new PodVideoFrameCache(cache.frameRoot).save(key,binding,0,0,frame);
                }
                return result.put("ready",true).put("frame",saved);
            }finally{frame.recycle();}
        }finally{cache.endPreparation(job);}
    }
    private static Bitmap decode(PodMediaPrefixCache cache,PodMediaPrefixCache.Entry entry)throws Exception {
        try(MediaDataSource bytes=new MediaDataSource(){
            public long getSize(){return entry.length;}
            public int readAt(long position,byte[] target,int offset,int size)throws IOException {
                if(position>=entry.length)return -1;
                int n=cache.read(entry,position,target,offset,size);
                if(n<=0)throw new IOException("uncached_media_gap");return n;
            }
            public void close(){}
        }){
            MediaMetadataRetriever decoder=new MediaMetadataRetriever();
            try{decoder.setDataSource(bytes);return decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320);}
            catch(RuntimeException unavailable){return null;}
            finally{decoder.release();}
        }
    }
}
