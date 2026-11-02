package dev.podjs.runtime;

import android.content.Context;
import android.media.AudioManager;
import org.json.JSONObject;

/** STREAM_MUSIC on its currently routed output. No persisted or player-local volume. */
final class PodVideoSystemAudio {
    static AudioManager audio(Context context) { return (AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }
    static JSONObject snapshot(Context context, JSONObject state) throws Exception {
        AudioManager audio=audio(context);
        int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),index=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return state.put("volume",index/(double)Math.max(1,max)).put("volumeIndex",index).put("volumeMax",max)
                .put("muted",audio.isStreamMute(AudioManager.STREAM_MUSIC)||index==0);
    }
    static void update(Context context, JSONObject args) throws Exception {
        AudioManager audio=audio(context);
        if(args.has("muted")) {
            if(!(args.get("muted") instanceof Boolean)||args.has("volume"))throw new IllegalArgumentException("invalid_volume");
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,args.getBoolean("muted")?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
        } else {
            double value=args.optDouble("volume",-1);
            if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("invalid_volume");
            int target=Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*(float)value);
            if(!audio.isVolumeFixed()&&target!=audio.getStreamVolume(AudioManager.STREAM_MUSIC))audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0);
        }
    }
}
