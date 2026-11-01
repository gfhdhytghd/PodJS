package dev.podjs.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Foreground MediaSession owner used for opt-in background playback. */
public final class PodVideoPlaybackService extends MediaSessionService {
    public static final String ACTION_STOP = "dev.podjs.runtime.STOP_VIDEO";
    private MediaSession session;
    private ExoPlayer player;
    private PodMediaAudioPolicy audioPolicy;
    PodMediaAudioPolicy audioPolicy() { return audioPolicy; }
    private final android.os.Binder localBinder = new LocalBinder();
    public final class LocalBinder extends android.os.Binder { public PodVideoPlaybackService service() { return PodVideoPlaybackService.this; } }
    public ExoPlayer player() { return player; }
    @Override public void onCreate() {
        super.onCreate();
        if (!PodTakeover.ready(this)) { stopSelf(); return; }
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("pod_video","Video playback",NotificationManager.IMPORTANCE_LOW));
        DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
            .setTargetBufferBytes(PodVideoSessionServices.TARGET_BUFFER_BYTES)
            .setBufferDurationsMs(1_000,4_000,100,250)
            .build();
        long createStarted=android.os.SystemClock.elapsedRealtime();
        player=new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        android.util.Log.i("PodVideoTiming","playerCreated durationMs="+(android.os.SystemClock.elapsedRealtime()-createStarted));
        audioPolicy=new PodMediaAudioPolicy(this, player::setVolume);
        audioPolicy.observeMuteChanges(player::isPlaying, player::getVolume, new PodVideoMuteTelemetry());
        session=new MediaSession.Builder(this,player).build();
        addSession(session);
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
    @Override public int onStartCommand(Intent intent,int flags,int id) { if (!PodTakeover.ready(this)) { stopSelf(); return Service.START_NOT_STICKY; } if(intent!=null&&ACTION_STOP.equals(intent.getAction())) { if(player!=null)player.stop(); stopForeground(true);stopSelf(); return Service.START_NOT_STICKY; }
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,PodVideoPlaybackService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"pod_video").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("WatchRSS").setContentText("正在播放视频").setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止",stop).build()).build();
        startForeground(1907,notification);
        super.onStartCommand(intent,flags,id);
        return Service.START_NOT_STICKY; }
    @Override public void onTaskRemoved(Intent rootIntent) { if(player==null||!player.getPlayWhenReady()) stopSelf(); }
    @Override public void onDestroy() { stopForeground(true);if(audioPolicy!=null)audioPolicy.close();audioPolicy=null;if(session!=null)session.release(); if(player!=null)player.release(); session=null;player=null;super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { if (!PodTakeover.ready(this)) return null; if ("dev.podjs.runtime.BIND_VIDEO".equals(intent.getAction())) return localBinder; return super.onBind(intent); }
}
