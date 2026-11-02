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
    /** Cache complete moov independently of the sample prefix, including tail-moov MP4s. */
    static void cacheMetadata(PodMediaPrefixCache cache,PodMediaPrefixCache.Job job)throws Exception {
        PodMediaPrefixCache.Entry e=job.entry;
        if(e.length<8||e.file.length()<8)return;
        byte[] first=new byte[8];try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(e.file,"r")){file.readFully(first);}if(integer(first,4)!=FTYP)return;
        long position=0;
        for(int boxes=0;boxes<32&&position<=e.length-8;boxes++){
            int n=(int)Math.min(16,e.length-position);byte[] header=new byte[n];
            if(cache.read(e,position,header,0,n)<n)header=cache.loadRange(job,position,n);
            long size=Integer.toUnsignedLong(integer(header,0));int type=integer(header,4),minimum=8;
            if(size==1){if(n<16)throw new IOException("invalid_mp4_box");size=((long)integer(header,8)<<32)|Integer.toUnsignedLong(integer(header,12));minimum=16;}
            if(size==0)size=e.length-position;
            if(size<minimum||size>e.length-position)throw new IOException("invalid_mp4_box");
            if(type==MOOV){
                long end=position+size;
                android.util.Log.i("PodMediaCache","mp4_metadata entry="+e.ordinal+" moov_position="+position+" moov_bytes="+size+" prefixBytes="+e.file.length()+" totalBytes="+e.length);
                if(size>512*1024)throw new IOException("metadata_over_budget");
                byte[] moov=new byte[(int)size];
                if(end<=e.file.length()){try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(e.file,"r")){input.seek(position);input.readFully(moov);}}
                else if(position<=e.file.length()){
                    if(end>PodMediaPrefixCache.MAX_ENTRY)throw new IOException("metadata_over_budget");
                    cache.appendMetadata(job,cache.loadRange(job,e.file.length(),(int)(end-e.file.length())));
                    try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(e.file,"r")){input.seek(position);input.readFully(moov);}
                }else{
                    long start=Math.max(0,position-PodMediaPrefixCache.CHUNK),count=e.length-start;
                    if(count>PodMediaPrefixCache.MAX_ENTRY-e.file.length())throw new IOException("metadata_over_budget");
                    if(e.tail().length()<count)cache.saveTail(job,cache.loadRange(job,start,(int)count),PodMediaPrefixCache.MAX_ENTRY);
                    try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(e.tail(),"r")){input.seek(position-(e.length-e.tail().length()));input.readFully(moov);}
                }
                long samples=PodMediaPrefixSamples.prefix(moov,e.length,1500);
                android.util.Log.i("PodMediaCache","mp4_samples entry="+e.ordinal+" sample_end="+Math.max(0,samples)+" sample_ms=1500 planned="+(samples>=0));
                if(samples>e.file.length()){
                    if(samples>PodMediaPrefixCache.MAX_ENTRY-e.tail().length())throw new IOException("samples_over_budget");
                    cache.appendMetadata(job,cache.loadRange(job,e.file.length(),(int)(samples-e.file.length())));
                }
                return;
            }
            position+=size;
        }
    }
    private static int integer(byte[] b,int p){return (b[p]&255)<<24|(b[p+1]&255)<<16|(b[p+2]&255)<<8|(b[p+3]&255);}
}
