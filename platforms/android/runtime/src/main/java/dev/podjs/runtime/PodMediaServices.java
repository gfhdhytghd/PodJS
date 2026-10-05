package dev.podjs.runtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;

/**
 * Asynchronous, single-session native audio and speech services.
 *
 * <p>Methods are {@code audio.start} ({@code url}, optional {@code loop}),
 * {@code audio.pause}, {@code audio.resume}, {@code audio.stop}, and
 * {@code audio.state}. URLs may be http(s), or a relative path below the
 * private {@code filesDir/podjs/files} directory. TTS methods are
 * {@code tts.init} (optional {@code language}, BCP-47), {@code tts.speak}
 * ({@code text}, optional {@code language}), {@code tts.stop}, and
 * {@code tts.state}. Successful results contain {@code ok:true}; audio state
 * also contains {@code state}, {@code positionMs}, and {@code durationMs},
 * while TTS state contains {@code state} and {@code initialized}.</p>
 */
public final class PodMediaServices implements AutoCloseable {
    public interface Callback {
        void complete(JSONObject value);
        void fail(String code, String message);
    }

    private static final String AUDIO_IDLE = "idle";
    private static final String AUDIO_PREPARING = "preparing";
    private static final String AUDIO_PLAYING = "playing";
    private static final String AUDIO_PAUSED = "paused";
    private static final String AUDIO_STOPPED = "stopped";
    private static final String AUDIO_ERROR = "error";
    private static final String TTS_UNINITIALIZED = "uninitialized";
    private static final String TTS_READY = "ready";
    private static final String TTS_SPEAKING = "speaking";

    private final PodSilenceServices silence=new PodSilenceServices();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    interface VolumeAccess {
        int min(); int max(); int current(); boolean fixed(); void set(int index);
    }
    private final VolumeAccess volumeAccess;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final File mediaRoot;
    private final ArrayList<Callback> ttsInitCallbacks = new ArrayList<>();
    private MediaPlayer player;
    private Callback pendingAudioCallback;
    private TextToSpeech tts;
    private String audioState = AUDIO_IDLE;
    private boolean ttsInitialized;
    private boolean ttsSpeaking;
    private String activeUtterance;
    private String activeEngineUtterance;
    private long ttsStartedAt;
    private final java.util.function.Consumer<String> ttsEvents;
    private final PodSpeechSpectrum spectrum;
    private String ttsError;
    private String ttsLanguage, ttsVoice, ttsEngine;
    private long ttsGeneration;
    private volatile boolean closed;

    public PodMediaServices(Context context) { this(context, event -> {}); }

    public PodMediaServices(Context context, java.util.function.Consumer<String> events) {
        this(context, events, null);
    }

    PodMediaServices(Context context, java.util.function.Consumer<String> events, VolumeAccess volumeAccess) {
        this.ttsEvents = events;
        this.spectrum = new PodSpeechSpectrum(task -> main.post(task), events);
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        File root = PodTakeover.filesRoot(this.context);
        try {
            if (!root.exists() && !root.mkdirs()) throw new IOException("mkdir_failed");
            mediaRoot = root.getCanonicalFile();
        } catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.volumeAccess = volumeAccess != null ? volumeAccess : new VolumeAccess() {
            public int min() { return android.os.Build.VERSION.SDK_INT >= 28 ? audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0; }
            public int max() { return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC); }
            public int current() { return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); }
            public boolean fixed() { return audioManager.isVolumeFixed(); }
            public void set(int index) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0); }
        };
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange).build();
    }

    /** Cancels the outstanding operation for a media kind, if any. */
    public void cancel(String method) {
        main.post(() -> {
            if ("audio.start".equals(method) && pendingAudioCallback != null) {
                Callback callback = pendingAudioCallback; pendingAudioCallback = null;
                callback.fail("cancelled", "Audio preparation cancelled"); releasePlayer(); audioState = AUDIO_STOPPED; abandonFocus();
            } else if ("tts.init".equals(method) && !ttsInitCallbacks.isEmpty()) {
                ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
                for (Callback callback : callbacks) callback.fail("cancelled", "Text to speech initialization cancelled");
            }
        });
    }

    public void dispatch(String method, JSONObject args, Callback callback) {
        if (method == null || args == null || callback == null) throw new IllegalArgumentException("method_args_callback_required");
        main.post(() -> {
            if (closed) { callback.fail("service_closed", "Media service is closed"); return; }
            try {
                switch (method) {
                    case "audio.start": audioStart(args, callback); break;
                    case "audio.pause": audioPause(callback); break;
                    case "audio.resume": audioResume(callback); break;
                    case "audio.setSpeed": audioSetSpeed(args, callback); break;
                    case "audio.stop": audioStop(callback); break;
                    case "audio.volumeState": callback.complete(volumeState()); break;
                    case "audio.setVolumeIndex": setVolumeIndex(args, callback); break;
                    case "audio.silenceState": case "audio.silenceEnabled": case "audio.silenceLease": callback.complete(silence.execute(method,args)); break;
                    case "audio.state": callback.complete(audioState()); break;
                    case "tts.init": ttsInit(args, callback); break;
                    case "tts.speak": ttsSpeak(args, callback); break;
                    case "tts.stop": ttsStop(callback); break;
                    case "tts.state": callback.complete(ttsState()); break;
                    case "tts.spectrum":
                        if (!(args.opt("enabled") instanceof Boolean)) throw new IllegalArgumentException("spectrum_enabled_required");
                        spectrum.setEnabled(args.getBoolean("enabled"));
                        callback.complete(new JSONObject().put("enabled", args.getBoolean("enabled"))); break;
                    default: callback.fail("unsupported_method", "Unsupported media method: " + method);
                }
            } catch (Exception e) { callback.fail(errorCode(e), message(e)); }
        });
    }

    private JSONObject volumeState() throws Exception {
        return new JSONObject().put("ok", true).put("min", volumeAccess.min()).put("max", volumeAccess.max())
                .put("current", volumeAccess.current()).put("fixed", volumeAccess.fixed());
    }

    private void setVolumeIndex(JSONObject args, Callback callback) throws Exception {
        Object raw = args.opt("index");
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_volume_index");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.floor(value) || value < volumeAccess.min() || value > volumeAccess.max())
            throw new IllegalArgumentException("invalid_volume_index");
        if (!volumeAccess.fixed() && (int)value != volumeAccess.current()) volumeAccess.set((int)value);
        // System policy may clamp the request: always return the actual index.
        callback.complete(volumeState());
    }

    private void audioStart(JSONObject args, Callback callback) throws Exception {
        String source = args.optString("url", null);
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("missing_url");
        if (pendingAudioCallback != null) { pendingAudioCallback.fail("cancelled", "Audio preparation replaced"); pendingAudioCallback = null; }
        releasePlayer();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setLooping(args.optBoolean("loop", false));
        final boolean[] callbackSent = {false};
        player.setOnCompletionListener(mp -> { audioState = AUDIO_STOPPED; abandonFocus(); });
        player.setOnErrorListener((mp, what, extra) -> {
            audioState = AUDIO_ERROR; abandonFocus();
            if (!callbackSent[0]) { callbackSent[0] = true; pendingAudioCallback = null; callback.fail("media_error", "MediaPlayer error " + what + "/" + extra); }
            return true;
        });
        player.setOnPreparedListener(mp -> {
            if (closed || player != mp) return;
            audioState = AUDIO_PLAYING;
            if (!requestFocus()) { audioState = AUDIO_ERROR; pendingAudioCallback = null; callbackSent[0] = true; releasePlayer(); callback.fail("audio_focus_denied", "Audio focus was denied"); return; }
            mp.start();
            if (!callbackSent[0]) {
                callbackSent[0] = true;
                pendingAudioCallback = null;
                try { complete(callback, new JSONObject().put("ok", true).put("state", audioState)); }
                catch (Exception e) { callback.fail("media_error", message(e)); }
            }
        });
        audioState = AUDIO_PREPARING;
        if (source.startsWith("http://") || source.startsWith("https://")) player.setDataSource(source);
        else player.setDataSource(context, Uri.fromFile(safeFile(source)));
        player.prepareAsync();
        pendingAudioCallback = callback;
    }

    private void audioPause(Callback callback) throws Exception {
        requirePlayer(); if (player.isPlaying()) { player.pause(); audioState = AUDIO_PAUSED; }
        callback.complete(audioState());
    }
    private void audioResume(Callback callback) throws Exception {
        requirePlayer(); if (!requestFocus()) { audioState = AUDIO_ERROR; callback.fail("audio_focus_denied", "Audio focus was denied"); return; }
        player.start(); audioState = AUDIO_PLAYING; callback.complete(audioState());
    }
    private void audioSetSpeed(JSONObject args, Callback callback) throws Exception {
        Object raw = args.opt("speed");
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_audio_speed");
        float speed = ((Number)raw).floatValue();
        if (!Float.isFinite(speed) || speed < .5f || speed > 2f) throw new IllegalArgumentException("invalid_audio_speed");
        requirePlayer();
        player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        callback.complete(audioState());
    }
    private void audioStop(Callback callback) throws Exception {
        if (pendingAudioCallback != null) { pendingAudioCallback.fail("cancelled", "Audio preparation stopped"); pendingAudioCallback = null; }
        if (player != null) { if (!AUDIO_PREPARING.equals(audioState)) try { player.stop(); } catch (IllegalStateException ignored) {} releasePlayer(); }
        audioState = AUDIO_STOPPED; abandonFocus(); callback.complete(audioState());
    }
    private void requirePlayer() { if (player == null || AUDIO_PREPARING.equals(audioState)) throw new IllegalStateException("audio_not_ready"); }
    private JSONObject audioState() {
        try { return new JSONObject().put("ok", true).put("state", audioState)
                .put("positionMs", player == null ? 0 : safePosition()).put("durationMs", player == null ? 0 : safeDuration()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private int safePosition() { try { return player.getCurrentPosition(); } catch (IllegalStateException e) { return 0; } }
    private int safeDuration() { try { return player.getDuration(); } catch (IllegalStateException e) { return 0; } }

    private void ttsInit(JSONObject args, Callback callback) {
        if (tts != null) { if (!ttsInitialized) ttsInitCallbacks.add(callback); else callback.complete(ttsState()); return; }
        ttsInitCallbacks.add(callback);
        String language = args.optString("language", "");
        JSONArray preferred = args.optJSONArray("preferredOfflineLanguages");
        if(args.has("preferredOfflineLanguages") && (preferred==null||preferred.length()==0)) {finishTtsInit("invalid_argument","At least one preferred language is required");return;}
        if(preferred!=null)for(int index=0;index<preferred.length();index++)if(!(preferred.opt(index) instanceof String)||preferred.optString(index).trim().isEmpty()){finishTtsInit("invalid_argument","Invalid preferred language");return;}
        try {ttsEngine = preferred == null ? null : preferredTtsEngine();}
        catch(Exception error){finishTtsInit("tts_unavailable",message(error));return;}
        ttsLanguage=null;ttsVoice=null;ttsError=null;
        final long generation=++ttsGeneration;
        tts = new TextToSpeech(context, status -> main.post(() -> {
            if (closed || tts == null || generation!=ttsGeneration) return;
            try {
            if (status != TextToSpeech.SUCCESS) { tts.shutdown(); tts = null; finishTtsInit("tts_unavailable", "Text to speech initialization failed"); return; }
            Locale locale = language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(language);
            int result;
            if(preferred!=null) {
                try{locale=selectLocalVoice(tts,preferred);result=TextToSpeech.LANG_AVAILABLE;}
                catch(Exception error){tts.shutdown();tts=null;finishTtsInit("tts_language_unavailable",message(error));return;}
            } else result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.shutdown(); tts = null;
                finishTtsInit("tts_language_unavailable", "Text to speech language is unavailable"); return;
            }
            ttsLanguage=locale.toLanguageTag();Voice selected=tts.getVoice();ttsVoice=selected==null?null:selected.getName();
            ttsInitialized = true; ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
            for (Callback pending : callbacks) pending.complete(ttsState());
            } catch(Exception error){if(tts!=null){tts.shutdown();tts=null;}ttsInitialized=false;finishTtsInit("tts_unavailable",message(error));}
        }),ttsEngine);
        main.postDelayed(()->{if(!closed&&generation==ttsGeneration&&tts!=null&&!ttsInitialized){tts.shutdown();tts=null;finishTtsInit("tts_unavailable","Text to speech initialization timed out");}},10_000);
        tts.setOnUtteranceProgressListener(ttsListener());
    }
    private UtteranceProgressListener ttsListener() {
        final long spectrumGeneration = spectrum.generation();
        final String engineId = activeEngineUtterance, clientId = activeUtterance;
        return new UtteranceProgressListener() {
            private boolean matches(String id) { return engineId != null && engineId.equals(id); }
            private boolean current() { return engineId != null && engineId.equals(activeEngineUtterance) && isActiveSpeech(clientId); }
            public void onBeginSynthesis(String id, int rate, int format, int channels) {
                if (matches(id)) spectrum.begin(spectrumGeneration, clientId, rate, format, channels);
            }
            public void onAudioAvailable(String id, byte[] audio) {
                if (matches(id)) spectrum.audio(spectrumGeneration, clientId, audio);
            }
            public void onStart(String id) {
                if (matches(id)) main.post(() -> { if (current()) { ttsStartedAt = android.os.SystemClock.elapsedRealtime(); ttsSpeaking = true; emitTtsEvent("start", clientId, 0, 0); } });
            }
            public void onRangeStart(String id, int start, int end, int frame) {
                if (matches(id)) main.post(() -> { if (current()) emitTtsEvent("range", clientId, start, end); });
            }
            private void finish(String type, String id) {
                if (!matches(id)) return;
                spectrum.end(spectrumGeneration, clientId);
                main.post(() -> {
                    if (!current()) return;
                    emitTtsEvent(type, clientId, 0, 0); ttsSpeaking = false;
                    activeUtterance = null; activeEngineUtterance = null;
                    if ("error".equals(type)) ttsError = "tts_speak_failed";
                });
            }
            public void onDone(String id) { finish("done", id); }
            public void onError(String id) { finish("error", id); }
            public void onStop(String id, boolean interrupted) { finish("stop", id); }
        };
    }
    private boolean isActiveSpeech(String id) { return !closed && id != null && id.equals(activeUtterance); }
    private void emitTtsEvent(String type, String id, int start, int end) {
        try {
            JSONObject event = new JSONObject().put("t", "speech.progress").put("type", type).put("utteranceId", id)
                .put("elapsedMs", ttsStartedAt > 0 ? Math.max(0, android.os.SystemClock.elapsedRealtime() - ttsStartedAt) : 0);
            if ("range".equals(type)) event.put("start", start).put("end", end);
            ttsEvents.accept(event.toString());
        } catch (Exception error) { android.util.Log.e("PodJS", "Speech event delivery failed", error); }
    }

    private String preferredTtsEngine() {
        java.util.ArrayList<String> packages=new java.util.ArrayList<>();
        for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentServices(new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),android.content.pm.PackageManager.MATCH_ALL))
            if(info.serviceInfo!=null&&!packages.contains(info.serviceInfo.packageName))packages.add(info.serviceInfo.packageName);
        String preferred=android.provider.Settings.Secure.getString(context.getContentResolver(),"tts_default_synth");
        return packages.contains(preferred)?preferred:packages.isEmpty()?null:packages.get(0);
    }
    static Locale selectLocalVoice(TextToSpeech engine,JSONArray preferred) throws Exception {
        java.util.ArrayList<Locale> locales=new java.util.ArrayList<>();
        for(int i=0;i<preferred.length();i++)locales.add(Locale.forLanguageTag(preferred.getString(i)));
        java.util.Set<Voice> voices=engine.getVoices();Voice candidate=null;int priority=Integer.MAX_VALUE;
        if(voices!=null)for(Voice voice:voices){
            if(voice.isNetworkConnectionRequired())continue;
            for(int i=0;i<locales.size();i++)if(voice.getLocale().getLanguage().equals(locales.get(i).getLanguage())){
                if(i<priority){priority=i;candidate=voice;}break;
            }
        }
        if(candidate!=null&&engine.setVoice(candidate)!=TextToSpeech.ERROR)return candidate.getLocale();
        for(Locale locale:locales)if(engine.isLanguageAvailable(locale)>=TextToSpeech.LANG_AVAILABLE){
            int result=engine.setLanguage(locale);
            if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED)throw new IllegalStateException("设备未安装可用的本地 TTS 语音");
            return locale;
        }
        throw new IllegalStateException("设备没有可用的本地 TTS 语音");
    }
    private void finishTtsInit(String code, String message) {
        ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
        for (Callback pending : callbacks) pending.fail(code, message);
    }
    private void ttsSpeak(JSONObject args, Callback callback) throws Exception {
        if (!ttsInitialized || tts == null) throw new IllegalStateException("tts_not_initialized");
        String text = args.optString("text", null); if (text == null || text.isEmpty()) throw new IllegalArgumentException("missing_text");
        String language = args.optString("language", "");
        if (!language.isEmpty()) {
            int languageResult = tts.setLanguage(Locale.forLanguageTag(language));
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED)
                throw new IllegalArgumentException("tts_language_unavailable");
        }
        double rate = args.optDouble("rate", 1.0);
        if (!Double.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("invalid_speech_rate");
        if (tts.setSpeechRate((float) rate) != TextToSpeech.SUCCESS) throw new IllegalStateException("tts_rate_failed");
        ttsError = null;
        String requestedId = args.optString("utteranceId", "");
        if (requestedId.length() > 128) throw new IllegalArgumentException("invalid_utterance_id");
        activeUtterance = requestedId.isEmpty() ? UUID.randomUUID().toString() : requestedId;
        ttsStartedAt = 0;
        // Android routes late engine callbacks through the current listener. A private unique ID
        // prevents an old utterance from matching even when the guest reuses its public ID.
        activeEngineUtterance = UUID.randomUUID().toString();
        spectrum.start(activeUtterance);
        tts.setOnUtteranceProgressListener(ttsListener());
        int result;
        try { result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeEngineUtterance); }
        catch (RuntimeException error) { spectrum.stop(); activeEngineUtterance = null; activeUtterance = null; ttsSpeaking = false; throw error; }
        if (result != TextToSpeech.SUCCESS) { spectrum.stop(); activeEngineUtterance = null; activeUtterance = null; ttsSpeaking = false; throw new IllegalStateException("tts_speak_failed"); }
        ttsSpeaking = true; callback.complete(ttsState());
    }
    private void ttsStop(Callback callback) throws Exception { spectrum.stop(); ttsError = null; activeEngineUtterance = null; activeUtterance = null; if (tts != null) tts.stop(); ttsSpeaking = false; callback.complete(ttsState()); }
    private JSONObject ttsState() {
        try { return new JSONObject().put("ok", true).put("initialized", ttsInitialized)
                .put("language",ttsLanguage).put("voice",ttsVoice).put("engine",ttsEngine).put("utteranceId",activeUtterance)
                .put("error", ttsError).put("state", ttsError != null ? "error" : ttsSpeaking ? TTS_SPEAKING : ttsInitialized ? TTS_READY : TTS_UNINITIALIZED); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private File safeFile(String path) throws IOException {
        if (path.startsWith("/") || path.indexOf('\0') >= 0 || path.isEmpty()) throw new IllegalArgumentException("invalid_path");
        File f = new File(mediaRoot, path).getCanonicalFile();
        if (!f.getPath().startsWith(mediaRoot.getPath() + File.separator) || !f.isFile()) throw new SecurityException("invalid_media_path");
        return f;
    }
    private void complete(Callback c, JSONObject value) { if (!closed) c.complete(value); }
    private boolean requestFocus() { return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
    private void abandonFocus() { audioManager.abandonAudioFocusRequest(focusRequest); }
    private void releasePlayer() { if (player != null) { try { player.reset(); } catch (Exception ignored) {} player.release(); player = null; } }
    private void onAudioFocusChange(int change) { main.post(() -> { if (player == null) return; if (change == AudioManager.AUDIOFOCUS_LOSS) { if (player.isPlaying()) player.pause(); audioState = AUDIO_PAUSED; abandonFocus(); } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (player.isPlaying()) player.pause(); audioState = AUDIO_PAUSED; } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { player.setVolume(.2f, .2f); } else if (change == AudioManager.AUDIOFOCUS_GAIN) player.setVolume(1f, 1f); }); }
    @Override public void close() { closed = true; spectrum.close(); main.post(() -> { silence.close(); if (pendingAudioCallback != null) { pendingAudioCallback.fail("service_closed", "Media service is closed"); pendingAudioCallback = null; } finishTtsInit("service_closed", "Media service is closed"); releasePlayer(); abandonFocus(); if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } ttsInitialized = false; ttsSpeaking = false; audioState = AUDIO_IDLE; }); }
    private static String errorCode(Exception e) { return e instanceof SecurityException ? "permission_denied" : e instanceof IllegalArgumentException ? "invalid_argument" : "media_error"; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
