package dev.podjs.runtime;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Serializes synthesis callbacks; buffers at most 96 analyzed frames, never PCM chunks. */
final class PodSpeechSpectrum {
    private final PodSpeechSpectrumAnalyzer analyzer = new PodSpeechSpectrumAnalyzer();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Consumer<Runnable> schedule;
    private final Consumer<String> events;
    private boolean enabled, closed, scheduled;
    private long generation;
    private String utterance;
    private int rate, format, channels = 1;

    PodSpeechSpectrum(Consumer<Runnable> schedule, Consumer<String> events) {
        this.schedule = schedule; this.events = events;
    }
    synchronized long generation() { return generation; }
    synchronized void setEnabled(boolean value) {
        if (closed || enabled == value) return;
        // Deliver a clear when disabling as well. No stale buffered frames survive toggling.
        pending.clear(); analyzer.clear();
        if (enabled || value) enqueue("reset", new float[0]);
        enabled = value;
    }
    synchronized void start(String id) {
        pending.clear(); analyzer.clear(); generation++;
        utterance = id; rate = format = 0; channels = 1;
        if (enabled) enqueue("reset", new float[0]);
    }
    synchronized void begin(long token, String id, int sampleRate, int audioFormat, int channelCount) {
        if (!active(token, id)) return;
        rate = sampleRate; format = audioFormat; channels = Math.max(1, channelCount);
        pending.clear(); analyzer.clear();
        if (enabled) enqueue("reset", new float[0]);
    }
    synchronized void audio(long token, String id, byte[] audio) {
        if (!enabled || !active(token, id)) return;
        analyzer.analyze(audio, rate, format, channels, levels -> enqueue("frame", levels));
    }
    synchronized void end(long token, String id) {
        if (!active(token, id)) return;
        stop();
    }
    synchronized void stop() {
        pending.clear(); analyzer.clear(); rate = format = 0;
        if (enabled) enqueue("reset", new float[0]);
        utterance = null; generation++;
    }
    synchronized void close() {
        closed = true; enabled = false; generation++; utterance = null;
        pending.clear(); analyzer.clear();
    }
    private boolean active(long token, String id) {
        return !closed && token == generation && id != null && id.equals(utterance);
    }
    private void enqueue(String type, float[] levels) {
        try {
            JSONArray values = new JSONArray();
            for (float value : levels) values.put((double)value);
            String event = new JSONObject().put("t", "speech.spectrum").put("type", type)
                .put("source", "synthesis-pcm").put("utteranceId", utterance == null ? "" : utterance)
                .put("sampleRateHz", Math.max(0, rate)).put("levels", values).toString();
            if (pending.size() == 96) pending.removeFirst();
            pending.addLast(event);
            if (!scheduled) { scheduled = true; schedule.accept(this::drain); }
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    private synchronized void drain() {
        scheduled = false;
        while (!closed && !pending.isEmpty()) {
            try { events.accept(pending.removeFirst()); }
            catch (Exception error) { android.util.Log.e("PodJS", "Speech spectrum delivery failed", error); }
        }
    }
}
