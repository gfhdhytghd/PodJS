package dev.podjs.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/** Codec-advertised capacity plus a persistent correction for devices that lie. */
final class PodVideoDecoderCapabilities {
    static final class Profile {
        final String mime;
        final int width;
        final int height;

        Profile(String mime, int width, int height) {
            this.mime = mime == null ? "" : mime.toLowerCase(Locale.US);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        boolean known() { return mime.startsWith("video/"); }
    }

    static final class Result {
        final Profile profile;
        final String decoderName;
        final int advertisedInstances;
        final int limit;
        final boolean sizeSupported;
        final boolean runtimeDowngraded;

        Result(Profile profile, String decoderName, int advertisedInstances, int limit,
               boolean sizeSupported, boolean runtimeDowngraded) {
            this.profile = profile;
            this.decoderName = decoderName;
            this.advertisedInstances = advertisedInstances;
            this.limit = limit;
            this.sizeSupported = sizeSupported;
            this.runtimeDowngraded = runtimeDowngraded;
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("decoderMime", profile == null ? JSONObject.NULL : profile.mime)
                    .put("decoderWidth", profile == null ? 0 : profile.width)
                    .put("decoderHeight", profile == null ? 0 : profile.height)
                    .put("decoderName", decoderName.isEmpty() ? JSONObject.NULL : decoderName)
                    .put("decoderAdvertisedInstances", advertisedInstances)
                    .put("decoderSizeSupported", sizeSupported)
                    .put("decoderRuntimeDowngraded", runtimeDowngraded);
        }
    }

    private static final String PREFS = "podjs-video-decoder-capacity-v1";
    private static final String FINGERPRINT = "device-fingerprint";
    private static final String LIMIT_PREFIX = "mime-limit:";
    private final SharedPreferences preferences;
    private boolean cacheMatchesDevice;

    PodVideoDecoderCapabilities(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cacheMatchesDevice = Build.FINGERPRINT.equals(preferences.getString(FINGERPRINT, ""));
    }

    int cachedDeviceLimit(int fallback) {
        if (!cacheMatchesDevice) return fallback;
        int result = fallback;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(LIMIT_PREFIX) && entry.getValue() instanceof Integer) {
                result = Math.min(result, Math.max(1, (Integer) entry.getValue()));
            }
        }
        return result;
    }

    Result inspect(Profile profile) {
        if (profile == null || !profile.known()) {
            int limit = cachedDeviceLimit(PodVideoSessionServices.MAX_SESSIONS);
            return new Result(profile, "", limit, limit, false, limit < PodVideoSessionServices.MAX_SESSIONS);
        }
        int cached = cachedMimeLimit(profile.mime);
        String selected = "";
        int advertised = 1;
        boolean sizeSupported = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            MediaCodecInfo.CodecCapabilities capabilities;
            try { capabilities = info.getCapabilitiesForType(profile.mime); }
            catch (IllegalArgumentException ignored) { continue; }
            boolean supported = true;
            MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            if (video != null && profile.width > 0 && profile.height > 0) {
                try { supported = video.isSizeSupported(profile.width, profile.height); }
                catch (IllegalArgumentException ignored) { supported = false; }
            }
            if (!supported) continue;
            selected = info.getName();
            advertised = Math.max(1, capabilities.getMaxSupportedInstances());
            sizeSupported = true;
            break;
        }
        int limit = Math.min(PodVideoSessionServices.MAX_SESSIONS, advertised);
        if (selected.isEmpty()) limit = 1;
        if (cached > 0) limit = Math.min(limit, cached);
        return new Result(profile, selected, advertised, limit, sizeSupported, cached == 1);
    }

    void recordSingleInstance(Profile profile) {
        if (profile == null || !profile.known()) return;
        SharedPreferences.Editor edit = preferences.edit();
        if (!cacheMatchesDevice) {
            edit.clear().putString(FINGERPRINT, Build.FINGERPRINT);
            cacheMatchesDevice = true;
        }
        edit.putInt(LIMIT_PREFIX + profile.mime, 1).apply();
    }

    private int cachedMimeLimit(String mime) {
        if (!cacheMatchesDevice) return 0;
        return preferences.getInt(LIMIT_PREFIX + mime, 0);
    }
}
