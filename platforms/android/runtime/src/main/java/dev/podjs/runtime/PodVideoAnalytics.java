package dev.podjs.runtime;

import android.os.SystemClock;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import org.json.JSONObject;

/** One prepare generation; only numeric observations leave this object.
 * Transfer callbacks can run off-main. Never retain DataSpec/URI/request headers. */
final class PodVideoAnalytics implements AnalyticsListener, TransferListener {
    final androidx.media3.exoplayer.analytics.PlaybackStatsListener playbackStats = new androidx.media3.exoplayer.analytics.PlaybackStatsListener(false,null);
    long decoderIndex,decoderInstances;
    long startupSeq,cacheEntry,sourceOrdinal,slotOrdinal,admissionMs,deadlineMs,cacheReadBytes;
    java.util.function.LongSupplier cacheNetworkBytes=()->0,cacheRangeRequests=()->0,cacheBytes=()->0;
    private long preparationProgressMs=SystemClock.uptimeMillis();
    synchronized long preparationProgressMs(){return preparationProgressMs;}
    private long prepareSeq, prepareGeneration,requestCount,openMs=-1,requestFirstByteMs=-1,ttfbMs,steadyMs,transferBytes;
    private boolean prewarm;
    synchronized void preparation(long requestedSeq,long generation,boolean prewarming) {
        prepareGeneration=generation;prewarm=prewarming;
        try {JSONObject row=prepareFields("start");prepareSeq=PodBenchmark.known(requestedSeq)?requestedSeq:PodBenchmark.begin("prepare_stage",row);if(prepareSeq==requestedSeq)PodBenchmark.mark("prepare_stage",prepareSeq,row);}catch(Exception ignored){}
    }
    private JSONObject prepareFields(String stage)throws Exception {return new JSONObject().put("prepare_stage",stage).put("decoder_index",decoderIndex).put("decoder_instances",decoderInstances).put("source_ordinal",sourceOrdinal).put("slot_ordinal",slotOrdinal).put("admission_ms",admissionMs).put("deadline_ms",deadlineMs).put("cache_read_bytes",cacheReadBytes).put("source_generation",prepareGeneration).put("is_prewarm",prewarm).put("network_bytes",bytes).put("cache_network_bytes",cacheNetworkBytes.getAsLong()).put("cache_range_requests",cacheRangeRequests.getAsLong()).put("cache_bytes",cacheBytes.getAsLong()).put("cache_entry",cacheEntry).put("range_requests",requestCount).put("ttfb_ms",ttfbMs).put("steady_ms",steadyMs);}
    synchronized void stage(String stage){if(prepareSeq>0)try{PodBenchmark.mark("prepare_stage",prepareSeq,prepareFields(stage));}catch(Exception ignored){}}
    synchronized void stage(String stage,androidx.media3.exoplayer.ExoPlayer player,android.view.TextureView view){if(prepareSeq>0)try{PodBenchmark.mark("prepare_stage",prepareSeq,prepareFields(stage).put("surface_ready",view!=null&&view.isAvailable()).put("playback_state",player==null?1:player.getPlaybackState()).put("buffered_ms",player==null?0:Math.max(0,player.getBufferedPosition()-player.getCurrentPosition())));}catch(Exception ignored){}}

    @Override public void onVideoDecoderInitialized(EventTime time,String decoderName,long initializedTimestampMs,long initializationDurationMs) {
        synchronized(this){preparationProgressMs=SystemClock.uptimeMillis();}
        try{android.media.MediaCodecInfo[] codecs=new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos();for(int i=0;i<codecs.length;i++)if(codecs[i].getName().equals(decoderName)){decoderIndex=i+1;for(String mime:codecs[i].getSupportedTypes())if(mime.equals("video/avc")||mime.equals("video/hevc")){decoderInstances=codecs[i].getCapabilitiesForType(mime).getMaxSupportedInstances();break;}break;}}catch(RuntimeException ignored){}
        stage("decoder_configured");
        if(startupSeq>0)try{PodBenchmark.mark("startup_stage",startupSeq,new JSONObject().put("startup_stage","decoder_configured").put("duration_ms",Math.max(0,initializationDurationMs)));}catch(Exception ignored){}
    }
    @Override public void onVideoInputFormatChanged(EventTime time,androidx.media3.common.Format format,androidx.media3.exoplayer.DecoderReuseEvaluation reuse) {
        stage("format_ready");PodBenchmark.startup(startupSeq,"video_format_ready");
    }
    private final long startedMs = SystemClock.elapsedRealtime();
    private long lastByteMs=-1;
    private int networkTransfers;
    private String contentKind="unknown";
    synchronized String contentKind(){return contentKind;}
    private long firstByteMs = -1, firstFrameMs = -1, bytes, throughput, dropped;
    @Override public synchronized void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
        if(count<=0)return;
        if(!network){cacheReadBytes+=count;return;}
        if (firstByteMs < 0) {firstByteMs = Math.max(0, SystemClock.elapsedRealtime() - startedMs);stage("network_first_byte");}
        long now=SystemClock.uptimeMillis();if(requestFirstByteMs<0){requestFirstByteMs=now;ttfbMs=openMs>=0?Math.max(0,now-openMs):0;}else steadyMs+=Math.max(0,now-preparationProgressMs);
        bytes += count; lastByteMs=SystemClock.elapsedRealtime();preparationProgressMs=SystemClock.uptimeMillis();
    }
    @Override public synchronized void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { if(network&&prepareSeq>0)try{requestCount++;openMs=SystemClock.uptimeMillis();requestFirstByteMs=-1;PodBenchmark.mark("prepare_stage",prepareSeq,prepareFields("network_open").put("position_bytes",Math.max(0,spec.position)));}catch(Exception ignored){} }
    @Override public synchronized void onTransferStart(DataSource source, DataSpec spec, boolean network) { if(network){
        for(java.util.Map.Entry<String,java.util.List<String>> entry:(source==null?java.util.Collections.<String,java.util.List<String>>emptyMap():source.getResponseHeaders()).entrySet())if(entry.getKey()!=null&&entry.getKey().equalsIgnoreCase("Content-Type"))for(String value:entry.getValue()==null?java.util.Collections.<String>emptyList():entry.getValue()){
            if(value==null)continue;
            String mime=value.toLowerCase(java.util.Locale.ROOT).split(";",2)[0].trim();
            contentKind=mime.equals("video/mp4")?"mp4":mime.equals("video/webm")?"webm":mime.contains("mpegurl")?"hls":mime.equals("application/dash+xml")?"dash":mime.equals("application/octet-stream")?"octet":mime.equals("text/html")?"html":"unknown";
        }
        stage("network_start");networkTransfers++;if(lastByteMs<0)lastByteMs=SystemClock.elapsedRealtime();} }
    @Override public synchronized void onTransferEnd(DataSource source, DataSpec spec, boolean network) { if(network){stage("network_end");networkTransfers=Math.max(0,networkTransfers-1);} }
    synchronized boolean networkStalled(){return networkTransfers>0&&lastByteMs>=0&&SystemClock.elapsedRealtime()-lastByteMs>1000;}
    @Override public synchronized void onBandwidthEstimate(EventTime time, int elapsedMs, long transferred, long bitrate) {
        if (elapsedMs > 0 && transferred > 0 && bitrate > 0) throughput = bitrate;
    }
    @Override public synchronized void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { dropped += Math.max(0, count); }
    @Override public synchronized void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
        if(firstFrameMs<0)stage("first_frame");
        if(firstFrameMs<0)PodBenchmark.startup(startupSeq,"decoded_first_frame");
        if (firstFrameMs < 0) firstFrameMs = Math.max(0, SystemClock.elapsedRealtime() - startedMs);
    }
    synchronized JSONObject snapshot() throws Exception {
        androidx.media3.exoplayer.analytics.PlaybackStats stats=playbackStats.getPlaybackStats();
        JSONObject result = new JSONObject().put("bytesLoaded", bytes).put("droppedFrames", dropped)
            .put("rebufferCount", stats==null?0:stats.totalRebufferCount).put("rebufferMs", stats==null?0:stats.getTotalRebufferTimeMs());
        if (firstByteMs >= 0) result.put("firstByteMs", firstByteMs);
        if (firstFrameMs >= 0) result.put("firstFrameMs", firstFrameMs);
        if (throughput > 0) result.put("throughputBps", throughput);
        return result;
    }
    static String errorCode(PlaybackException error) {
        // Bounded cause walk. Never send exception messages containing URLs.
        Throwable current = error;
        for (int i = 0; current != null && i < 16; i++, current = current.getCause()) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
                if (status == 401 || status == 403 || status == 412 || status == 429) return "restricted";
                if (status == 404 || status == 410) return "source_expired";
                return "cdn_error";
            }
        }
        if (PodPlaybackFailure.isNetwork(error)) return "network_error";
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) return "decode_error";
        return "prepare_failed";
    }
}
