package dev.podjs.runtime;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TreeSet;

/** Optional real PCM-silence playback, shared by independent guest lease owners. */
final class PodSilenceServices implements AutoCloseable {
    private final TreeSet<String> owners=new TreeSet<>();
    private AudioTrack track;
    private boolean enabled,closed;
    JSONObject execute(String method,JSONObject args)throws Exception{
        if(closed)throw new IllegalStateException("Silence service closed");
        if("audio.silenceEnabled".equals(method))enabled=PodScreenServices.flag(args,"enabled");
        else if("audio.silenceLease".equals(method)){
            String owner=PodScreenServices.owner(args);boolean active=PodScreenServices.flag(args,"active");
            if(active){if(!owners.contains(owner)&&owners.size()>=64)throw new IllegalStateException("Too many audio owners");owners.add(owner);}else owners.remove(owner);
        }else if("audio.silenceState".equals(method))return state();else throw new IllegalArgumentException("Unsupported silence method");
        update();return state();
    }
    JSONObject state()throws Exception{return new JSONObject().put("enabled",enabled).put("owners",new JSONArray(owners)).put("playing",track!=null&&track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING);}
    private void update(){
        if(!enabled||owners.isEmpty()){stop();return;}if(track!=null)return;
        int minimum=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<=0)throw new IllegalStateException("Silence buffer unavailable");
        int frames=Math.max(8000,minimum/2);byte[] silence=new byte[frames*2];AudioTrack candidate=null;
        try{
            candidate=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),silence.length,AudioTrack.MODE_STATIC,AudioManager.AUDIO_SESSION_ID_GENERATE);
            // MODE_STATIC is STATE_NO_STATIC_DATA until the first successful write.
            if(candidate.getState()==AudioTrack.STATE_UNINITIALIZED)throw new IllegalStateException("Silence initial state="+candidate.getState());
            int written=candidate.write(silence,0,silence.length);if(written!=silence.length)throw new IllegalStateException("Silence write="+written+" expected="+silence.length);
            if(candidate.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Silence state after write="+candidate.getState());
            int loop=candidate.setLoopPoints(0,frames,-1);if(loop!=AudioTrack.SUCCESS)throw new IllegalStateException("Silence loop="+loop);
            candidate.play();track=candidate;candidate=null;
        }finally{if(candidate!=null)candidate.release();}
    }
    private void stop(){AudioTrack previous=track;track=null;if(previous!=null){try{previous.stop();}catch(RuntimeException ignored){}try{previous.release();}catch(RuntimeException ignored){}}}
    @Override public void close(){if(closed)return;closed=true;enabled=false;owners.clear();stop();}
}
