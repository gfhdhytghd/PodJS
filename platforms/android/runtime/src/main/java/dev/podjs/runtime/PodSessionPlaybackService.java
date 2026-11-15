package dev.podjs.runtime;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.*;

/** Foreground ownership for an existing pager player. Never creates or releases a player. */
public final class PodSessionPlaybackService extends MediaSessionService {
    private static Object owner;
    private static ExoPlayer current;
    private static Runnable stop;
    private MediaSession session;
    private ExoPlayer attached;
    final MediaSession.Callback commands=new MediaSession.Callback(){
        @Override public void onPlayerInteractionFinished(MediaSession value,MediaSession.ControllerInfo controller,androidx.media3.common.Player.Commands commands){
            if((commands.contains(androidx.media3.common.Player.COMMAND_PLAY_PAUSE)||commands.contains(androidx.media3.common.Player.COMMAND_STOP))&&value.getPlayer()==current&&!current.getPlayWhenReady()){
                Runnable callback=stop;if(callback!=null)callback.run();
            }
        }
    };
    private static final String STOP="dev.podjs.runtime.STOP_SESSION_VIDEO";
    static void checkPermission(Context context){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("notification_permission_denied");
    }
    static void attach(Context context,Object token,ExoPlayer player,Runnable onStop){
        if(owner==token&&current==player)return;checkPermission(context);
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK);
        owner=token;current=player;stop=onStop;
        try{context.startForegroundService(new Intent(context,PodSessionPlaybackService.class));}
        catch(RuntimeException error){owner=null;current=null;stop=null;throw error;}
    }
    static void detach(Context context,Object token){
        if(owner!=token)return;owner=null;current=null;stop=null;
        context.stopService(new Intent(context,PodSessionPlaybackService.class));
    }
    @Override public void onCreate(){
        super.onCreate();NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("pod_session_video","Video playback",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){Runnable callback=stop;if(callback!=null)callback.run();stopSelf();return START_NOT_STICKY;}
        if(current==null){stopSelf();return START_NOT_STICKY;}
        if(attached!=current){if(session!=null){removeSession(session);session.release();}attached=current;session=new MediaSession.Builder(this,attached).setCallback(commands).build();addSession(session);}
        PendingIntent pause=PendingIntent.getService(this,0,new Intent(this,PodSessionPlaybackService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"pod_session_video").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("WatchRSS").setContentText("正在播放视频").setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"暂停",pause).build()).build();
        startForeground(1908,notification);super.onStartCommand(intent,flags,id);return START_NOT_STICKY;
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){if(current==attached&&current!=null){Runnable callback=stop;if(callback!=null)callback.run();}stopForeground(true);if(session!=null){removeSession(session);session.release();}session=null;attached=null;super.onDestroy();}
}
