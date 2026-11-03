package dev.podjs.runtime;

import android.app.NotificationManager;
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

/** Main-thread system audio observer. Never owns a user volume or mute setting.
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
    private boolean systemVideo, respectSilentMode;
    // Process lifetime only: shared by foreground, neighbours and replacement players.
    private static boolean crownOverride;
    private static final java.util.Set<PodMediaAudioPolicy> policies = new java.util.HashSet<>();
    private String lastRinger;
    private Boolean lastSilenced;
    void respectSilentMode(boolean enabled) { respectSilentMode=enabled; refresh(); }
    static void crownIntent(int direction, int index) {
        if(direction>0 && index>0)crownOverride=true;
        else if(direction<0 && index==0)crownOverride=false;
        for(PodMediaAudioPolicy policy:new java.util.ArrayList<>(policies))policy.refresh();
    }
    static boolean zenSilencesMedia(int filter,int categories) {
        return filter==NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter==NotificationManager.INTERRUPTION_FILTER_PRIORITY && (categories & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA)==0;
    }
    boolean silenced() {
        if(!respectSilentMode)return false;
        if(audio.isStreamMute(AudioManager.STREAM_MUSIC)||audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0)return true;
        if(crownOverride)return false;
        if(audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return true;
        NotificationManager notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(notifications==null)return false;
        int filter=notifications.getCurrentInterruptionFilter();
        NotificationManager.Policy policy=filter==NotificationManager.INTERRUPTION_FILTER_PRIORITY?notifications.getConsolidatedNotificationPolicy():null;
        return zenSilencesMedia(filter,policy==null?0:policy.priorityCategories);
    }
    String ringerMode() { int mode=audio.getRingerMode();return mode==0?"silent":mode==1?"vibrate":mode==2?"normal":"unknown"; }

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
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
        filter.addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED);
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

    /** Video uses unity gain unless opted in to the silent switch. Speculative slots disable their audio track. */
    PodMediaAudioPolicy(Context context, androidx.media3.exoplayer.ExoPlayer player) {
        this(context, ignored -> {});
        systemVideo=true;unityOutput=player::setVolume;
        audioEnabled=enabled->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,!enabled).build());
        player.setVolume(1f);
        policies.add(this);
    }
    void observeVolume(Runnable changed) { volumeChanged=changed;refresh(); }

    void setVolume(float requestedVolume, boolean prewarming) {
        if (closed) return;
        // Historical private gain is ignored. The silent policy and lifecycle hold are independent.
        this.prewarming = prewarming;
        // Read synchronously at activation too: a system notification may still be queued.
        refresh();
    }

    /** Retirement/prewarm hold; never changes the system mute or index. */
    void setHeldMuted(boolean held) {
        if (closed) return;
        prewarming = held;
        refresh();
    }

    void refresh() {
        final long observedAt = SystemClock.uptimeMillis();
        if (closed) return;
        int index=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC)||index==0;
        boolean silenced=silenced();String ringer=ringerMode();
        boolean policyChanged=lastSilenced==null||lastSilenced!=silenced||!ringer.equals(lastRinger);
        lastSilenced=silenced;lastRinger=ringer;
        boolean changed=policyChanged||lastIndex==null||lastIndex!=index||lastMax!=max||lastMute!=muted;
        boolean muteChanged=lastMute!=null&&lastMute!=muted;
        lastIndex=index;lastMax=max;lastMute=muted;
        if(systemVideo) { unityOutput.accept(silenced?0f:1f);audioEnabled.accept(!prewarming); }
        else output.accept(prewarming||silenced?0f:1f);
        if(muteChanged&&!prewarming&&playing.getAsBoolean())try{muteObserver.changed(muted,observedAt);}catch(RuntimeException ignored){}
        // System readback is never a player-gain mute receipt or audible PCM.
        if(changed)volumeChanged.run();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        policies.remove(this);
        context.unregisterReceiver(receiver);
        context.getContentResolver().unregisterContentObserver(settings);
    }
}
