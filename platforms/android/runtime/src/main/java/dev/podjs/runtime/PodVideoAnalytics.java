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
    private final long startedMs = SystemClock.elapsedRealtime();
    private long lastByteMs=-1;
    private int networkTransfers;
    private long firstByteMs = -1, firstFrameMs = -1, bytes, throughput, dropped;
    @Override public synchronized void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
        if (!network || count <= 0) return;
        if (firstByteMs < 0) firstByteMs = Math.max(0, SystemClock.elapsedRealtime() - startedMs);
        bytes += count; lastByteMs=SystemClock.elapsedRealtime();
    }
    @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { }
    @Override public synchronized void onTransferStart(DataSource source, DataSpec spec, boolean network) { if(network){networkTransfers++;if(lastByteMs<0)lastByteMs=SystemClock.elapsedRealtime();} }
    @Override public synchronized void onTransferEnd(DataSource source, DataSpec spec, boolean network) { if(network)networkTransfers=Math.max(0,networkTransfers-1); }
    synchronized boolean networkStalled(){return networkTransfers>0&&lastByteMs>=0&&SystemClock.elapsedRealtime()-lastByteMs>=800;}
    @Override public synchronized void onBandwidthEstimate(EventTime time, int elapsedMs, long transferred, long bitrate) {
        if (elapsedMs > 0 && transferred > 0 && bitrate > 0) throughput = bitrate;
    }
    @Override public synchronized void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { dropped += Math.max(0, count); }
    @Override public synchronized void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
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
