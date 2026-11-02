package dev.podjs.runtime;

/** Bounded ISO-BMFF sample-table planner. Unknown/fragmented layouts keep the caller's byte estimate. */
final class PodMediaPrefixSamples {
    static long prefix(byte[] moov,long length,int millis){
        try{
            long end=0;boolean found=false;
            for(int trak=8;trak+8<=moov.length;){int next=next(moov,trak,moov.length);if(type(moov,trak,"trak")){
                int mdia=find(moov,trak,"mdia"),mdhd=find(moov,mdia,"mdhd"),minf=find(moov,mdia,"minf"),stbl=find(moov,minf,"stbl");
                int stts=find(moov,stbl,"stts"),stsc=find(moov,stbl,"stsc"),stsz=find(moov,stbl,"stsz"),stco=find(moov,stbl,"stco"),co64=find(moov,stbl,"co64");
                if(mdhd<0||stts<0||stsc<0||stsz<0||(stco<0&&co64<0)){trak=next;continue;}
                long scale=u32(moov,mdhd+((moov[mdhd+8]&255)==1?28:20));if(scale<=0){trak=next;continue;}
                long ticks=(scale*millis+999)/1000,samples=0;int times=(int)u32(moov,stts+12);
                for(int j=0;j<times&&ticks>0;j++){long count=u32(moov,stts+16+j*8),delta=u32(moov,stts+20+j*8);if(delta<=0)return -1;long take=Math.min(count,(ticks+delta-1)/delta);samples+=take;ticks-=take*delta;}
                long fixed=u32(moov,stsz+12),available=u32(moov,stsz+16);samples=Math.min(samples,available);
                int chunks=stco>=0?stco:co64,entries=(int)u32(moov,chunks+12),maps=(int)u32(moov,stsc+12),map=0;long sample=0;
                for(int chunk=1;chunk<=entries&&sample<samples;chunk++){
                    while(map+1<maps&&u32(moov,stsc+16+(map+1)*12)<=chunk)map++;
                    long per=u32(moov,stsc+20+map*12),offset=stco>=0?u32(moov,chunks+16+(chunk-1)*4):u64(moov,chunks+16+(chunk-1)*8);
                    if(per<=0||maps<=0||offset<0||offset>length)return -1;
                    long count=Math.min(per,samples-sample),size=0;
                    if(fixed>0)size=fixed*count;else for(long j=0;j<count;j++)size+=u32(moov,stsz+20+(int)(sample+j)*4);
                    if(size<0||size>length-offset)return -1;end=Math.max(end,offset+size);sample+=count;
                }
                if(sample<samples)return -1;found|=samples>0;
            }trak=next;}
            return found?end:-1;
        }catch(IndexOutOfBoundsException|IllegalArgumentException invalid){return -1;}
    }
    private static int find(byte[] b,int parent,String name){if(parent<0)return -1;int end=next(b,parent,b.length);for(int p=parent+8;p+8<=end;){if(type(b,p,name))return p;p=next(b,p,end);}return -1;}
    private static boolean type(byte[] b,int p,String value){return b[p+4]==value.charAt(0)&&b[p+5]==value.charAt(1)&&b[p+6]==value.charAt(2)&&b[p+7]==value.charAt(3);}
    private static int next(byte[] b,int p,int end){long size=u32(b,p);if(size<8||size>end-p)throw new IllegalArgumentException();return p+(int)size;}
    private static long u32(byte[] b,int p){return (long)(b[p]&255)<<24|(long)(b[p+1]&255)<<16|(long)(b[p+2]&255)<<8|(b[p+3]&255);}
    private static long u64(byte[] b,int p){long high=u32(b,p);if(high>0x7fffffffL)throw new IllegalArgumentException();return high<<32|u32(b,p+4);}
}
