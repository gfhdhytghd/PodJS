package dev.podjs.runtime;

import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.util.Map;

/** Shared native playback factory. App-supplied headers also apply to DASH audio. */
final class PodVideoHttpDataSources {
    static DefaultHttpDataSource.Factory create(Map<String, String> headers, TransferListener listener) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory().setTransferListener(listener);
        if (!headers.isEmpty()) factory.setDefaultRequestProperties(headers);
        return factory;
    }
    private PodVideoHttpDataSources() {}
}
