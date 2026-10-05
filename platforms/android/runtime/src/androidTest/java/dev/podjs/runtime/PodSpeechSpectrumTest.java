package dev.podjs.runtime;

import android.media.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSpeechSpectrumTest {
    private static byte[] pcm(int count, int rate, int format, int channels, double hz, double amplitude) {
        int bytes = format == AudioFormat.ENCODING_PCM_8BIT ? 1 : format == AudioFormat.ENCODING_PCM_16BIT ? 2 : 4;
        ByteBuffer buffer = ByteBuffer.allocate(count * bytes * channels).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            double sample = amplitude * Math.sin(2 * Math.PI * hz * i / rate);
            for (int ch = 0; ch < channels; ch++) {
                if (bytes == 1) buffer.put((byte)Math.round(sample * 128 + 128));
                else if (bytes == 2) buffer.putShort((short)Math.round(sample * 32767));
                else buffer.putFloat((float)sample);
            }
        }
        return buffer.array();
    }
    /** Independent direct DFT oracle for a first PCM16 frame, avoiding the production FFT implementation. */
    private static float[] reference(byte[] pcm, int rate) {
        ByteBuffer input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        double[] samples = new double[512], magnitudes = new double[257];
        for (int i = 0; i < 512; i++) samples[i] = (input.getShort() / 32767f) * (.5 - .5 * Math.cos(2 * Math.PI * i / 511));
        for (int bin = 1; bin <= 256; bin++) {
            double re = 0, im = 0;
            for (int i = 0; i < 512; i++) { double angle = 2 * Math.PI * i * bin / 512; re += samples[i] * Math.cos(angle); im -= samples[i] * Math.sin(angle); }
            magnitudes[bin] = Math.hypot(re, im) * 2 / 512;
        }
        float min = Math.min(90f, rate / 2f * .5f), max = Math.max(Math.min(4200f, rate / 2f * .92f), min * 1.5f);
        float[] levels = new float[5];
        for (int band = 0; band < 5; band++) {
            float lo = (float)Math.exp(Math.log(min) + (Math.log(max) - Math.log(min)) * band / 5);
            float hi = (float)Math.exp(Math.log(min) + (Math.log(max) - Math.log(min)) * (band + 1) / 5);
            int start = Math.max(1, Math.min(256, (int)Math.ceil(lo * 512 / rate)));
            int end = Math.max(start, Math.min(256, (int)Math.floor(hi * 512 / rate)));
            double sum = 0; for (int bin = start; bin <= end; bin++) sum += magnitudes[bin] * magnitudes[bin];
            double rms = Math.sqrt(sum / (end - start + 1));
            levels[band] = Math.max(0, Math.min(1, (float)((20 * Math.log10(Math.max(rms, .000001)) + 58) / 42))) * .64f;
        }
        return levels;
    }
    @Test public void nativeParametersMatchIndependentDftAcrossBandsAndRates() {
        for (int rate : new int[]{8000, 16000, 24000}) for (int hz : new int[]{150, 350, 750, 1500, 3000}) {
            byte[] data = pcm(512, rate, AudioFormat.ENCODING_PCM_16BIT, 1, hz, .25);
            List<float[]> frames = new ArrayList<>();
            assertEquals(1, new PodSpeechSpectrumAnalyzer().analyze(data, rate, AudioFormat.ENCODING_PCM_16BIT, 1, frames::add));
            assertArrayEquals(reference(data, rate), frames.get(0), .00001f);
        }
    }
    @Test public void formatsChannelsCadenceAndReleaseMatchReferenceSemantics() {
        for (int format : new int[]{AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT}) {
            for (int channels : new int[]{1, 2}) {
                PodSpeechSpectrumAnalyzer analyzer = new PodSpeechSpectrumAnalyzer(); List<float[]> frames = new ArrayList<>();
                assertEquals(59, analyzer.analyze(pcm(24000, 24000, format, channels, 750, .25), 24000, format, channels, frames::add));
                float[] before = frames.get(frames.size() - 1);
                frames.clear(); analyzer.analyze(pcm(1200, 24000, format, channels, 0, 0), 24000, format, channels, frames::add);
                // At the second silence hop the entire 512-sample window is silent: release = .24.
                for (int band = 0; band < 5; band++) assertEquals(frames.get(1)[band] * .76f, frames.get(2)[band], .000001f);
                assertTrue(before[2] > frames.get(2)[2]);
                analyzer.clear(); frames.clear();
                assertEquals(1, analyzer.analyze(pcm(512, 24000, format, channels, 0, 0), 24000, format, channels, frames::add));
                assertArrayEquals(new float[5], frames.get(0), 0);
            }
        }
    }
    @Test public void chunkBoundariesRateChangesUnsupportedFormatsAndNonFiniteInput() {
        PodSpeechSpectrumAnalyzer analyzer = new PodSpeechSpectrumAnalyzer(); List<float[]> frames = new ArrayList<>();
        assertEquals(0, analyzer.analyze(new byte[1022], 24000, AudioFormat.ENCODING_PCM_16BIT, 1, frames::add));
        assertEquals(1, analyzer.analyze(new byte[2], 24000, AudioFormat.ENCODING_PCM_16BIT, 1, frames::add));
        assertEquals(0, analyzer.analyze(new byte[800], 16000, AudioFormat.ENCODING_PCM_16BIT, 1, frames::add));
        assertEquals(0, analyzer.analyze(new byte[4096], 16000, AudioFormat.ENCODING_INVALID, 1, frames::add));
        assertEquals(0, analyzer.analyze(new byte[4096], 0, AudioFormat.ENCODING_PCM_16BIT, 1, frames::add));
        analyzer.clear(); frames.clear();
        ByteBuffer invalid = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 512; i++) invalid.putFloat(i % 2 == 0 ? Float.NaN : Float.POSITIVE_INFINITY);
        analyzer.analyze(invalid.array(), 24000, AudioFormat.ENCODING_PCM_FLOAT, 1, frames::add);
        assertArrayEquals(new float[5], frames.get(0), 0);
    }
    @Test public void optInGenerationClearingBoundedQueueAndClose() throws Exception {
        List<Runnable> tasks = new ArrayList<>(); List<String> events = new ArrayList<>();
        PodSpeechSpectrum spectrum = new PodSpeechSpectrum(tasks::add, events::add);
        byte[] tone = pcm(24000, 24000, AudioFormat.ENCODING_PCM_16BIT, 1, 750, .25);
        spectrum.start("same-id"); long old = spectrum.generation();
        spectrum.begin(old, "same-id", 24000, AudioFormat.ENCODING_PCM_16BIT, 1);
        spectrum.audio(old, "same-id", tone); assertTrue(tasks.isEmpty());
        spectrum.setEnabled(true); spectrum.audio(old, "same-id", tone);
        assertEquals(1, tasks.size()); tasks.remove(0).run();
        assertEquals(60, events.size()); assertEquals("reset", new JSONObject(events.get(0)).getString("type"));
        events.clear(); spectrum.audio(old, "same-id", tone); spectrum.stop(); tasks.remove(0).run();
        assertEquals(1, events.size()); assertEquals(0, new JSONObject(events.get(0)).getJSONArray("levels").length());
        spectrum.audio(old, "same-id", tone); assertTrue(tasks.isEmpty());
        spectrum.start("same-id"); long current = spectrum.generation();
        spectrum.begin(current, "same-id", 24000, AudioFormat.ENCODING_PCM_16BIT, 1);
        spectrum.end(old, "same-id"); spectrum.audio(old, "same-id", tone);
        for (int i = 0; i < 4; i++) spectrum.audio(current, "same-id", tone);
        events.clear(); assertEquals(1, tasks.size()); tasks.remove(0).run(); assertEquals(96, events.size());
        events.clear(); spectrum.setEnabled(false); tasks.remove(0).run(); assertEquals(1, events.size());
        spectrum.audio(current, "same-id", tone); assertTrue(tasks.isEmpty());
        spectrum.setEnabled(true); spectrum.audio(current, "same-id", tone); spectrum.close();
        events.clear(); tasks.remove(0).run(); assertTrue(events.isEmpty());
    }
    @Test public void serviceListenerUsesPcmAndResetsOnDoneErrorStopAndExplicitStop() throws Exception {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        PodMediaServices service = new PodMediaServices(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext(), event -> {
            if (event.contains("speech.spectrum")) events.add(event);
        });
        try {
            java.lang.reflect.Field field = PodMediaServices.class.getDeclaredField("spectrum"); field.setAccessible(true);
            PodSpeechSpectrum spectrum = (PodSpeechSpectrum)field.get(service);
            java.lang.reflect.Method method = PodMediaServices.class.getDeclaredMethod("ttsListener"); method.setAccessible(true);
            spectrum.setEnabled(true);
            for (String terminal : new String[]{"done", "error", "stop", "explicit"}) {
                spectrum.start("public-id");
                java.lang.reflect.Field engineId = PodMediaServices.class.getDeclaredField("activeEngineUtterance"); engineId.setAccessible(true); engineId.set(service, terminal);
                java.lang.reflect.Field clientId = PodMediaServices.class.getDeclaredField("activeUtterance"); clientId.setAccessible(true); clientId.set(service, "public-id");
                // No real engine or physical audio is started.
                android.speech.tts.UtteranceProgressListener listener = (android.speech.tts.UtteranceProgressListener)method.invoke(service);
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                int beforeStale = events.size();
                listener.onBeginSynthesis("previous-engine-id", 24000, AudioFormat.ENCODING_PCM_16BIT, 1);
                listener.onAudioAvailable("previous-engine-id", pcm(512, 24000, AudioFormat.ENCODING_PCM_16BIT, 1, 750, .25));
                listener.onDone("previous-engine-id");
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(beforeStale, events.size());
                listener.onBeginSynthesis(terminal, 24000, AudioFormat.ENCODING_PCM_16BIT, 1);
                listener.onAudioAvailable(terminal, pcm(512, 24000, AudioFormat.ENCODING_PCM_16BIT, 1, 750, .25));
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals("frame", new JSONObject(events.get(events.size() - 1)).getString("type"));
                assertEquals("public-id", new JSONObject(events.get(events.size() - 1)).getString("utteranceId"));
                if (terminal.equals("done")) listener.onDone(terminal);
                else if (terminal.equals("error")) listener.onError(terminal);
                else if (terminal.equals("stop")) listener.onStop(terminal, true);
                else {
                    java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
                    service.dispatch("tts.stop", new JSONObject(), new PodMediaServices.Callback() {
                        public void complete(JSONObject value) { finished.countDown(); }
                        public void fail(String code, String message) { throw new AssertionError(code); }
                    });
                    assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals("reset", new JSONObject(events.get(events.size() - 1)).getString("type"));
                int count = events.size();
                listener.onAudioAvailable(terminal, pcm(512, 24000, AudioFormat.ENCODING_PCM_16BIT, 1, 750, .25));
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(count, events.size());
            }
        } finally { service.close(); }
    }

}
