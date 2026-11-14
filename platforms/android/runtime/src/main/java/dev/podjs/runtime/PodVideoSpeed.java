package dev.podjs.runtime;

import org.json.JSONObject;

/** Playback speed argument shared by the legacy video service and video sessions. */
final class PodVideoSpeed {
    static final float MIN = .5f, MAX = 2f;
    private PodVideoSpeed() {}
    static float parse(JSONObject args) {
        Object value = args.opt("speed");
        if (!(value instanceof Number)) throw new IllegalArgumentException("speed_must_be_number");
        double speed = ((Number) value).doubleValue();
        if (!Double.isFinite(speed) || speed < MIN || speed > MAX) throw new IllegalArgumentException("speed_out_of_range");
        return (float) speed;
    }
}
