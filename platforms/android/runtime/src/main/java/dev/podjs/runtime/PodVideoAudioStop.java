package dev.podjs.runtime;

import android.content.Context;
import android.media.AudioTrack;
import android.os.SystemClock;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Item-bound acknowledgement from the playback thread's real AudioTrack.
 * This proves the track stopped accepting playback, not physical speaker silence. */
final class PodVideoAudioStop {
    private volatile AudioTrack track;
    private static final class Pending {
        final PodBenchmark.VideoAction action;
        final AudioTrack track;
        Pending(PodBenchmark.VideoAction action,AudioTrack track){this.action=action;this.track=track;}
    }
    private final AtomicReference<Pending> pending=new AtomicReference<>();

    void arm(JSONObject args,long generation) {
        PodBenchmark.VideoAction action=PodBenchmark.videoAction(args);
        AudioTrack current=track;
        if(action!=null)android.util.Log.i("PodMediaCache","audio_stop_arm seq="+action.seq+" track_present="+(current==null?0:1)+" generation_match="+(action.sourceGeneration==generation?1:0));
        pending.set(action!=null&&action.sourceGeneration==generation&&current!=null?new Pending(action,current):null);
    }
    void cancel(){pending.set(null);}
    void observed(AudioTrack current,String ack) {
        Pending stop=pending.get();
        if(stop==null||current==null||stop.track!=current)return;
        int state=current.getPlayState();
        if(current.getState()!=AudioTrack.STATE_INITIALIZED||state==AudioTrack.PLAYSTATE_PLAYING)return;
        if(state!=AudioTrack.PLAYSTATE_PAUSED&&state!=AudioTrack.PLAYSTATE_STOPPED)return;
        long now=SystemClock.uptimeMillis();
        if(!pending.compareAndSet(stop,null))return;
        try{PodBenchmark.emit(stop.action.event("previous_audio_stopped").put("backend","audiotrack")
            .put("audio_ack",ack).put("audio_track_state",state==AudioTrack.PLAYSTATE_PAUSED?"paused":"stopped"),now);}catch(Exception ignored){}
    }
    DefaultRenderersFactory renderers(Context context) {
        return new DefaultRenderersFactory(context){
            @Override protected AudioSink buildAudioSink(Context c,boolean floating,boolean playbackParams) {
                AudioSink delegate=new DefaultAudioSink.Builder(c).setEnableFloatOutput(floating)
                    .setEnableAudioTrackPlaybackParams(playbackParams)
                    .setAudioTrackProvider((config,attributes,sessionId)->{
                        AudioTrack created=DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,sessionId);
                        track=created;return created;
                    }).build();
                return new ForwardingAudioSink(delegate){
                    @Override public void pause(){super.pause();observed(track,"pause");}
                    @Override public void flush(){AudioTrack previous=track;super.flush();observed(previous,"flush");if(track==previous)track=null;}
                    @Override public void reset(){AudioTrack previous=track;super.reset();observed(previous,"flush");if(track==previous)track=null;}
                };
            }
        };
    }
}
