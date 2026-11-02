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

/** Main-thread STREAM_MUSIC observer. Never owns a user volume or mute setting.
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
    private boolean prewarming, closed;
    interface MuteObserver {
        void changed(boolean muted, long observedAtUptimeMs);
        default void playerApplied(boolean muted, long observedAtUptimeMs) { }
        default void failed(long observedAtUptimeMs) { }
    }
    private BooleanSupplier playing = () -> false;
    private MuteObserver muteObserver = (muted, at) -> {};

    /** Observations describe system requests only, never actual mixer/PCM output. */
    void observeMuteChanges(BooleanSupplier playing, Supplier<Float> playerVolume, MuteObserver observer) {
        this.playing = playing;
        this.muteObserver = observer;
    }
    // OEM volume broadcasts can arrive late. Only the active policy checks
    // system truth; speculative/retired slots never run this fallback.
    private final Runnable systemCheck = new Runnable() {
        @Override public void run() {
            if(closed||prewarming)return;
            int index=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC)||index==0;
            if(lastIndex==null||lastIndex!=index||lastMute==null||lastMute!=muted)refresh();
            if(!closed&&!prewarming)main.postDelayed(this,50);
        }
    };
    private void scheduleSystemCheck() {
        main.removeCallbacks(systemCheck);
        if(!closed&&!prewarming)main.postDelayed(systemCheck,50);
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
        // Preparation marks speculative slots held before any media can start.
        output.accept(1f);
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
    void observeVolume(Runnable changed) { volumeChanged=changed;refresh();scheduleSystemCheck(); }

    void setVolume(float requestedVolume, boolean prewarming) {
        if (closed) return;
        // Historical prepare gain is ignored. Only the speculative lifecycle hold remains.
        this.prewarming = prewarming;
        // Read synchronously at activation too: a system notification may still be queued.
        refresh();
        scheduleSystemCheck();
    }

    /** Retirement/prewarm hold; never changes the system mute or index. */
    void setHeldMuted(boolean held) {
        if (closed) return;
        prewarming = held;
        refresh();
        scheduleSystemCheck();
    }

    void refresh() {
        final long observedAt = SystemClock.uptimeMillis();
        if (closed) return;
        int index=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC)||index==0;
        boolean changed=lastIndex==null||lastIndex!=index||lastMax!=max||lastMute!=muted;
        boolean muteChanged=lastMute!=null&&lastMute!=muted;
        lastIndex=index;lastMax=max;lastMute=muted;
        if(systemVideo) { unityOutput.accept(1f);audioEnabled.accept(!prewarming); }
        else output.accept(prewarming?0f:1f); // Test output, with the same lifecycle-only exception.
        if(muteChanged&&!prewarming&&playing.getAsBoolean())try{muteObserver.changed(muted,observedAt);}catch(RuntimeException ignored){}
        // System readback is never a player-gain mute receipt or audible PCM.
        if(changed)volumeChanged.run();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(systemCheck);
        context.unregisterReceiver(receiver);
        context.getContentResolver().unregisterContentObserver(settings);
    }
}
