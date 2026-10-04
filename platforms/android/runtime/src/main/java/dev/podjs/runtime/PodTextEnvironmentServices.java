package dev.podjs.runtime;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import org.json.JSONArray;
import org.json.JSONObject;

/** Current context text dimensions. Called on the host main thread, without caching Resources. */
final class PodTextEnvironmentServices {
    private static final int MAX_SP_VALUES = 128;
    private static final double MAX_SP_VALUE = 8192;

    static JSONObject read(Context context, JSONObject args) throws Exception {
        JSONArray values = new JSONArray();
        if (args.has("spValues")) {
            Object requested = args.opt("spValues");
            if (!(requested instanceof JSONArray)) throw new IllegalArgumentException("spValues must be an array");
            values = (JSONArray) requested;
        }
        if (values.length() > MAX_SP_VALUES) throw new IllegalArgumentException("spValues must contain at most 128 values");
        float[] sizes = new float[values.length()];
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (!(value instanceof Number)) throw new IllegalArgumentException("spValues must contain numbers");
            double size = ((Number) value).doubleValue();
            if (!Double.isFinite(size) || size < 0 || size > MAX_SP_VALUE)
                throw new IllegalArgumentException("spValues must be finite and between 0 and 8192");
            sizes[i] = (float) size;
        }

        Resources resources = context.getResources();
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(resources.getDisplayMetrics());
        float fontScale = resources.getConfiguration().fontScale;
        if (!Float.isFinite(metrics.density) || metrics.density <= 0 || !Float.isFinite(fontScale) || fontScale <= 0)
            throw new IllegalStateException("Current text environment is unavailable");

        JSONArray pixels = new JSONArray();
        for (float size : sizes) {
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics);
            if (!Float.isFinite(px) || px < 0) throw new IllegalStateException("Current SP conversion is unavailable");
            pixels.put((double) px);
        }
        return new JSONObject().put("density", (double) metrics.density)
                .put("fontScale", (double) fontScale).put("spValuesPx", pixels);
    }
}
