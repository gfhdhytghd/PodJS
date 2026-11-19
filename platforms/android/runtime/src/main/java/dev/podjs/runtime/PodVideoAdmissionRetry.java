package dev.podjs.runtime;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/** Service-owned admission recovery for one legacy source. No foreground host,
 * view, callback or credential is retained across background handoff. Main only. */
final class PodVideoAdmissionRetry implements AutoCloseable {
    private final ExoPlayer player;
    private final PodVideoHttpDataSources.PrepareGate prepareGate;
    private final boolean cacheOnlyOnDenial;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean closed,deferred,suspended,playWhenReady,initializing=true;
    private MediaItem source;
    private Runnable pending;
    private long revision;
    private final Player.Listener listener=new Player.Listener(){
        @Override public void onPlayerError(PlaybackException error){
            if(closed)return;
            if(PodVideoCodecGate.isAdmissionDenied(error))defer();else cancel();
        }
        @Override public void onPlayWhenReadyChanged(boolean ready,int reason){
            if(closed||initializing)return;
            if(ready)resume();else suspend();
        }
        @Override public void onMediaItemTransition(MediaItem item,int reason){
            if(deferred&&!java.util.Objects.equals(source,item))close();
        }
        @Override public void onPlaybackStateChanged(int state){
            if(!closed&&state==Player.STATE_READY)prepareGate.ready();
            // External MediaSession stop must not resurrect an ended source.
            if(!closed&&!initializing&&(state==Player.STATE_ENDED||state==Player.STATE_IDLE&&player.getPlayerError()==null)){suspend();cancel();}
        }
    };
    PodVideoAdmissionRetry(ExoPlayer player,PodVideoHttpDataSources.PrepareGate prepareGate,boolean cacheOnlyOnDenial){
        this.player=player;this.prepareGate=prepareGate;this.cacheOnlyOnDenial=cacheOnlyOnDenial;
        player.addListener(listener);
    }
    void initialized(){initializing=false;playWhenReady=player.getPlayWhenReady();schedule();}
    boolean active(){return !closed;}
    boolean deferred(){return deferred;}
    boolean suspended(){return suspended;}
    void defer(){
        if(closed||deferred)return;
        if(cacheOnlyOnDenial)prepareGate.restrict();
        deferred=true;source=player.getCurrentMediaItem();playWhenReady=player.getPlayWhenReady();
        PodVideoCodecGate.PROCESS.requestReclaim(player,PodVideoCodecGate.PREWARM);
        schedule();
    }
    void suspend(){suspended=true;removePending();}
    void resume(){if(closed)return;suspended=false;playWhenReady=true;schedule();}
    private void schedule(){
        if(closed||!deferred||suspended||pending!=null)return;
        final long attempt=++revision;
        pending=()->{
            if(attempt!=revision)return;
            pending=null;
            if(closed||!deferred||suspended)return;
            if(!java.util.Objects.equals(source,player.getCurrentMediaItem())){close();return;}
            if(PodVideoCodecGate.PROCESS.live()>=PodVideoCodecGate.MAXIMUM){schedule();return;}
            // The gate still guards the real factory if another purpose wins
            // the free permit between this check and Media3's codec thread.
            deferred=false;
            player.prepare();
            player.setPlayWhenReady(playWhenReady);
        };
        main.postDelayed(pending,100);
    }
    private void removePending(){revision++;if(pending!=null){main.removeCallbacks(pending);pending=null;}}
    private void cancel(){removePending();deferred=false;}
    @Override public void close(){if(closed)return;closed=true;cancel();player.removeListener(listener);}
}
