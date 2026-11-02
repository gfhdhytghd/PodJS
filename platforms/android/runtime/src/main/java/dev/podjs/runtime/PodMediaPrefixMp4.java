package dev.podjs.runtime;

import java.io.IOException;

/** Locate top-level MP4 metadata without walking the uncached mdat payload. */
final class PodMediaPrefixMp4 {
    private static final int MOOV=0x6d6f6f76,FTYP=0x66747970;
    static int tailBytes(PodMediaPrefixCache cache,PodMediaPrefixCache.Job job,int budget)throws Exception {
        PodMediaPrefixCache.Entry entry=job.entry;
        if(entry.length<=entry.file.length()&&entry.length<=budget)return 0;
        long prefixLimit=Math.min(entry.file.length(),job.budget);
        int fallback=(int)Math.min(PodMediaPrefixPreparation.TAIL_BYTES,entry.length-prefixLimit);
        long position=0;
        for(int boxes=0;boxes<32&&position<=entry.length-8;boxes++){
            PodMediaPrefixCache.backgroundTurn(job);
            byte[] header=new byte[(int)Math.min(16,entry.length-position)];
            int used=cache.read(entry,position,header,0,header.length);
            // Only MP4 input uses this planner. Other containers keep bounded suffix preparation.
            if(position==0&&(used<8||integer(header,4)!=FTYP))return fallback;
            if(used<header.length)header=cache.loadRange(job,position,header.length);
            long size=Integer.toUnsignedLong(integer(header,0));int type=integer(header,4),minimum=8;
            if(size==1){if(header.length<16)throw new IOException("invalid_mp4_box");size=((long)integer(header,8)<<32)|Integer.toUnsignedLong(integer(header,12));minimum=16;}
            if(size==0)size=entry.length-position;
            if(size<minimum||size>entry.length-position)throw new IOException("invalid_mp4_box");
            if(type==MOOV){
                if(position+size<=prefixLimit)return fallback;
                // Include a read-ahead guard before metadata. Keep at least 512 KiB for samples.
                long count=entry.length-Math.max(0,position-PodMediaPrefixCache.CHUNK);
                count=Math.max(fallback,count);
                return count>budget-512*1024?-1:(int)count;
            }
            position+=size;
        }
        return fallback;
    }
    private static int integer(byte[] b,int p){return (b[p]&255)<<24|(b[p+1]&255)<<16|(b[p+2]&255)<<8|(b[p+3]&255);}
}
