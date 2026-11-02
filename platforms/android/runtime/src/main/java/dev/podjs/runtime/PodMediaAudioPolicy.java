package dev.podjs.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Main-thread output gate. Never changes the system stream or the requested gain.
 * The player owner must close this before releasing its output (including background playback).
 */
final class PodMediaAudioPolicy implements AutoCloseable {
    // AOSP broadcasts complement the public ringer broadcast; OEMs may notify settings instead.
    static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    static final String STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private final Context context;
    private final AudioManager audio;
    private final Consumer<Float> output;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Consumer<Float> unityOutput;
    private Consumer<Boolean> audioEnabled;
    private Runnable volumeChanged=()->{};
    private boolean systemVideo;
    private Integer lastIndex,lastMax;
    private Boolean lastMute;
    private float requestedVolume;
    private float appliedVolume;
    private boolean prewarming, closed;
    private Boolean systemMuted;
    interface MuteObserver {
        void changed(boolean muted, long observedAtUptimeMs);
        default void playerApplied(boolean muted, long observedAtUptimeMs) { }
        default void failed(long observedAtUptimeMs) { }
    }
    private BooleanSupplier playing = () -> false;
    private Supplier<Float> playerVolume = () -> null;
    private MuteObserver muteObserver = (muted, at) -> {};

    /** Observations describe system requests only, never actual mixer/PCM output. */
    void observeMuteChanges(BooleanSupplier playing, Supplier<Float> playerVolume, MuteObserver observer) {
        this.playing = playing;
        this.playerVolume = playerVolume;
        this.muteObserver = observer;
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private final ContentObserver settings = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    PodMediaAudioPolicy(Context context, Consumer<Float> output) {
        this.context = context.getApplicationContext();
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.output = output;
        // Silence before subscribing or preparing. A sticky broadcast cannot expose a prewarm.
        output.accept(0f);
        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(VOLUME_CHANGED);
        filter.addAction(STREAM_MUTE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else this.context.registerReceiver(receiver, filter);
        try {
            this.context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, settings);
            this.context.getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, settings);
        } catch (RuntimeException error) {
            this.context.getContentResolver().unregisterContentObserver(settings);
            this.context.unregisterReceiver(receiver);
            throw error;
        }
    }

    /** Video always uses unity gain. Speculative slots disable their audio track. */
    PodMediaAudioPolicy(Context context, androidx.media3.exoplayer.ExoPlayer player) {
        this(context, ignored -> {});
        systemVideo=true;unityOutput=player::setVolume;
        audioEnabled=enabled->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,!enabled).build());
        player.setVolume(1f);
    }
    void observeVolume(Runnable changed) { volumeChanged=changed;refresh(); }

    void setVolume(float requestedVolume, boolean prewarming) {
        if (closed) return;
        this.requestedVolume = requestedVolume;
        this.prewarming = prewarming;
        // Read synchronously at activation too: a system notification may still be queued.
        refresh();
    }

    /** Retirement/cancel hold without overwriting the user's requested gain. */
    void setHeldMuted(boolean held) {
        if (closed) return;
        prewarming = held;
        refresh();
    }

    void refresh() {
        final long observedAt = SystemClock.uptimeMillis();
        if (closed) return;
        if(systemVideo) {
            int index=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
            boolean changed=lastIndex==null||lastIndex!=index||lastMax!=max||lastMute!=muted;
            boolean muteChanged=lastMute!=null&&lastMute!=muted;
            lastIndex=index;lastMax=max;lastMute=muted;
            unityOutput.accept(1f);audioEnabled.accept(!prewarming);
            if(muteChanged&&!prewarming&&playing.getAsBoolean())try{muteObserver.changed(muted,observedAt);}catch(RuntimeException ignored){}
            // System readback is not a player-gain mute receipt or audible PCM.
            if(changed)volumeChanged.run();
            return;
        }
        boolean silent = audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
                || audio.isStreamMute(AudioManager.STREAM_MUSIC)
                || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        boolean changed = systemMuted != null && systemMuted != silent;
        systemMuted = silent;
        boolean observe = changed && !prewarming && playing.getAsBoolean();
        if (observe) {
            // Instrumentation failure must not prevent the audio gate from following the system.
            try { muteObserver.changed(silent, observedAt); } catch (RuntimeException ignored) { }
        }
        float effective = prewarming || silent ? 0f : requestedVolume;
        if (Float.compare(appliedVolume, effective) != 0) {
            try { output.accept(effective); appliedVolume = effective; }
            catch (RuntimeException failure) {
                if (observe) { try { muteObserver.failed(SystemClock.uptimeMillis()); } catch (RuntimeException ignored) { } }
                throw failure;
            }
        }
        if (observe) {
            try {
                // Read Media3's current gain after mutation; this is player state, not audible PCM.
                Float actual = playerVolume.get();
                long at = SystemClock.uptimeMillis();
                if (actual != null && Float.isFinite(actual) && Float.compare(actual, effective) == 0)
                    muteObserver.playerApplied(actual == 0f, at);
                else muteObserver.failed(at);
            } catch (RuntimeException failure) {
                try { muteObserver.failed(SystemClock.uptimeMillis()); } catch (RuntimeException ignored) { }
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        context.unregisterReceiver(receiver);
        context.getContentResolver().unregisterContentObserver(settings);
    }
}
