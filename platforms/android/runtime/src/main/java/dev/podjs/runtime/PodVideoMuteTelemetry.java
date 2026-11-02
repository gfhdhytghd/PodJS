package dev.podjs.runtime;

import org.json.JSONObject;

/** Closed system-request/player-state evidence. Never emits mixer_match. */
final class PodVideoMuteTelemetry implements PodMediaAudioPolicy.MuteObserver {
    private long seq;
    private boolean requestedMute, pending;

    @Override public void changed(boolean muted, long at) {
        requestedMute = muted;
        try {
            seq = PodBenchmark.begin("mute_changed", fields(muted, false), at);
            pending = seq > 0;
        } catch (Exception ignored) { pending = false; }
    }

    @Override public void playerApplied(boolean muted, long at) {
        if (!pending) return;
        pending = false;
        emit("player_audio_applied", at, muted, true);
    }

    @Override public void failed(long at) {
        if (!pending) return;
        pending = false;
        emit("error", at, requestedMute, true);
    }

    private JSONObject fields(boolean muted, boolean player) throws Exception {
        JSONObject fields = new JSONObject().put("kind", requestedMute ? "on" : "off").put("muted", muted);
        if (player) fields.put("backend", "player");
        return fields;
    }

    private void emit(String code, long at, boolean muted, boolean player) {
        try {
            JSONObject value=fields(muted,player).put("code",code).put("seq",seq);
            if(code.equals("error"))value.put("failure_code","host_error").put("failure_stage","volume").put("exception_kind","unknown");
            PodBenchmark.emit(value,at);
        } catch (Exception ignored) { /* Telemetry must not interrupt playback. */ }
    }
}
