package dev.podjs.runtime;

import java.util.function.LongSupplier;

/** Decoder YUV buffers and HWUI textures consume native/graphics memory, not
 * the managed heap. Admission uses system headroom and a composition reserve. */
final class PodVideoMemoryBudget {
    static final long MIB=1024L*1024L;
    static final long RESERVE_BYTES=96*MIB;
    static final long RECOVERY_QUIET_MS=30_000L;
    private final LongSupplier now;
    private final int maximum;
    private int trimLimit;
    private long lastTrimAt;
    private boolean complete;

    PodVideoMemoryBudget() { this(3); }
    PodVideoMemoryBudget(int maximum) { this(()->System.nanoTime()/1_000_000L,maximum); }
    PodVideoMemoryBudget(LongSupplier now) { this(now,3); }
    PodVideoMemoryBudget(LongSupplier now,int maximum) { this.now=now;this.maximum=maximum;this.trimLimit=maximum; }

    void trim(int level) {
        // UI_HIDDEN (20) is visibility, not an allocation-pressure signal.
        if(level==20)return;
        if(level>=80)complete=true; // TRIM_MEMORY_COMPLETE requires service rebuild.
        if(complete){trimLimit=1;return;}
        // A lower pressure report supersedes the earlier report immediately.
        trimLimit=level>=15?1:level>=5?2:maximum;
        lastTrimAt=now.getAsLong();
    }

    static long decoderBytes(int width,int height) {
        // Unknown dimensions must never count as a tiny decoder. Reserve 12 YUV
        // frames plus three RGBA textures and the bounded compressed buffer.
        long pixels=width>0&&height>0?(long)width*height:1920L*1080;
        pixels=Math.min(pixels,8192L*8192);
        return 12*MIB+pixels*30;
    }

    int limit(long systemHeadroom,boolean lowMemory,long decoderBytes,int inUse) {
        return limit(systemHeadroom,lowMemory,decoderBytes,inUse,0);
    }
    int limit(long systemHeadroom,boolean lowMemory,long decoderBytes,int inUse,int preparing) {
        if(complete||lowMemory||systemHeadroom<=RESERVE_BYTES)return 1;
        long perSlot=Math.max(1,decoderBytes);
        long spare=systemHeadroom-RESERVE_BYTES;
        // availMem excludes resident decoders, but pending prepares may not have
        // allocated their buffers yet. Reserve those leases before new admission.
        int leases=Math.max(0,Math.min(maximum,inUse));
        int pending=Math.max(0,Math.min(leases,preparing));
        int memoryLimit=(int)Math.min(maximum,leases+Math.min(maximum,spare/perSlot)-pending);
        long at=now.getAsLong();
        if(trimLimit<maximum&&memoryLimit==maximum&&at>=lastTrimAt&&at-lastTrimAt>=RECOVERY_QUIET_MS)trimLimit=maximum;
        return Math.max(1,Math.min(trimLimit,memoryLimit));
    }
}
