package dev.podjs.runtime;

/** Callback observations, not panel presentation fences or decoder drop counts. */
final class PodVideoFrameStats {
    long updates, maxGapNs, gapsOver100Ms, gapsOver250Ms, gapsOver500Ms;
    long bufferingNs, bufferingCount;
    private long lastNs, bufferingStartedNs;
    private boolean playing;
    void playing(boolean value,long nowNs) {
        if(value!=playing){playing=value;lastNs=value?nowNs:0;}
    }
    void updated(long nowNs) {
        if(!playing)return;
        updates++;
        if(lastNs>0&&nowNs>=lastNs){long gap=nowNs-lastNs;maxGapNs=Math.max(maxGapNs,gap);
            if(gap>100_000_000L)gapsOver100Ms++;
            if(gap>250_000_000L)gapsOver250Ms++;
            if(gap>500_000_000L)gapsOver500Ms++;
        }
        lastNs=nowNs;
    }
    void buffering(boolean value,long nowNs) {
        if(value&&bufferingStartedNs==0){bufferingStartedNs=nowNs;bufferingCount++;}
        else if(!value&&bufferingStartedNs!=0){bufferingNs+=Math.max(0,nowNs-bufferingStartedNs);bufferingStartedNs=0;}
    }
    String summary(long nowNs){return "textureUpdates="+updates+" maxTextureCallbackGapMs="+(maxGapNs/1_000_000.0)
        +" gapsOver100Ms="+gapsOver100Ms+" gapsOver250Ms="+gapsOver250Ms+" gapsOver500Ms="+gapsOver500Ms
        +" rebufferCount="+bufferingCount+" rebufferMs="+((bufferingNs+(bufferingStartedNs==0?0:Math.max(0,nowNs-bufferingStartedNs)))/1_000_000.0);}
}
