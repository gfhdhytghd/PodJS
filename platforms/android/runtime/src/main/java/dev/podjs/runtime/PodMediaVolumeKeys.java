package dev.podjs.runtime;

import android.content.Context;
import android.view.KeyEvent;
import org.json.JSONObject;

/** Route the media mute key to the same STREAM_MUSIC contract as the video UI. */
public final class PodMediaVolumeKeys {
    private PodMediaVolumeKeys() { }
    public static boolean dispatch(Context context, KeyEvent event) {
        if(event.getKeyCode()!=KeyEvent.KEYCODE_VOLUME_MUTE)return false;
        if(event.getAction()==KeyEvent.ACTION_UP)return true;
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
        if(event.getRepeatCount()!=0||event.isCanceled())return true;
        try {
            boolean muted=PodVideoSystemAudio.snapshot(context,new JSONObject()).getBoolean("muted");
            PodVideoSystemAudio.update(context,new JSONObject().put("muted",!muted));
            return true;
        } catch(Exception error) { return false; }
    }
}
