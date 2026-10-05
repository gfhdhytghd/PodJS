package dev.podjs.runtime;

import android.media.AudioFormat;
import java.util.Arrays;
import java.util.function.Consumer;
import org.jtransforms.fft.DoubleFFT_1D;

/** Java port of WatchRSS ReadAloudAudioSpectrumAnalyzer. Synthesis PCM, not microphone/output capture. */
final class PodSpeechSpectrumAnalyzer {
    static final int BANDS = 5, FFT_SIZE = 512;
    private final DoubleFFT_1D fft = new DoubleFFT_1D(FFT_SIZE);
    private final float[] ring = new float[FFT_SIZE], smoothed = new float[BANDS];
    private final double[] buffer = new double[FFT_SIZE], window = new double[FFT_SIZE];
    private int writeIndex, buffered, sinceAnalysis, sampleRate, hop = FFT_SIZE;

    PodSpeechSpectrumAnalyzer() {
        for (int i = 0; i < FFT_SIZE; i++) window[i] = .5 - .5 * Math.cos(Math.PI * 2 * i / (FFT_SIZE - 1));
    }
    void clear() { clearWindow(); sampleRate = 0; hop = FFT_SIZE; }
    private void clearWindow() {
        Arrays.fill(ring, 0); Arrays.fill(buffer, 0); Arrays.fill(smoothed, 0);
        writeIndex = buffered = sinceAnalysis = 0;
    }
    int analyze(byte[] audio, int rate, int format, int channelCount, Consumer<float[]> onFrame) {
        if (rate <= 0 || audio == null || audio.length == 0) return 0;
        if (rate != sampleRate) {
            if (sampleRate != 0) clearWindow();
            sampleRate = rate; hop = Math.max(rate / 60, 32);
        }
        int channels = Math.max(channelCount, 1);
        int bytes = format == AudioFormat.ENCODING_PCM_16BIT ? 2 : format == AudioFormat.ENCODING_PCM_8BIT ? 1 : format == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 0;
        if (bytes == 0 || channels > audio.length / bytes) return 0;
        int frameSize = channels * bytes, emitted = 0;
        // Like the reference, incomplete PCM frames at the end of a callback are ignored.
        for (int frame = 0; frame < audio.length / frameSize; frame++) {
            float mono = 0;
            for (int ch = 0; ch < channels; ch++) {
                int p = frame * frameSize + ch * bytes;
                if (bytes == 1) mono += ((audio[p] & 255) - 128) / 128f;
                else if (bytes == 2) mono += (short)((audio[p + 1] << 8) | (audio[p] & 255)) / 32767f;
                else {
                    float sample = Float.intBitsToFloat((audio[p] & 255) | ((audio[p + 1] & 255) << 8) | ((audio[p + 2] & 255) << 16) | (audio[p + 3] << 24));
                    mono += Float.isFinite(sample) ? clamp(sample, -1, 1) : 0;
                }
            }
            ring[writeIndex] = clamp(mono / channels, -1, 1);
            writeIndex = (writeIndex + 1) % FFT_SIZE;
            buffered = Math.min(buffered + 1, FFT_SIZE); sinceAnalysis++;
            if (buffered >= FFT_SIZE && sinceAnalysis >= hop) {
                onFrame.accept(levels()); sinceAnalysis = 0; emitted++;
            }
        }
        return emitted;
    }
    private float[] levels() {
        for (int i = 0; i < FFT_SIZE; i++) buffer[i] = ring[(writeIndex + i) % FFT_SIZE] * window[i];
        fft.realForward(buffer);
        float nyquist = sampleRate / 2f;
        float min = Math.min(90f, nyquist * .5f);
        float max = Math.max(Math.min(4200f, nyquist * .92f), min * 1.5f);
        float[] output = new float[BANDS];
        for (int band = 0; band < BANDS; band++) {
            int start = Math.max(1, Math.min(FFT_SIZE / 2, (int)Math.ceil(edge(band, min, max) * FFT_SIZE / sampleRate)));
            int end = Math.max(start, Math.min(FFT_SIZE / 2, (int)Math.floor(edge(band + 1, min, max) * FFT_SIZE / sampleRate)));
            double sum = 0;
            for (int bin = start; bin <= end; bin++) {
                double magnitude = (bin == FFT_SIZE / 2 ? Math.abs(buffer[1]) : Math.hypot(buffer[bin * 2], buffer[bin * 2 + 1])) * (2.0 / FFT_SIZE);
                sum += magnitude * magnitude;
            }
            double rms = Math.sqrt(sum / (end - start + 1));
            float target = rms <= 0 ? 0 : clamp((float)((20 * Math.log10(Math.max(rms, .000001)) + 58) / 42), 0, 1);
            float current = smoothed[band];
            smoothed[band] = clamp(current + (target - current) * (target >= current ? .64f : .24f), 0, 1);
            output[band] = smoothed[band];
        }
        return output;
    }
    private static float edge(int band, float min, float max) {
        return (float)Math.exp(Math.log(min) + (Math.log(max) - Math.log(min)) * (band / (double)BANDS));
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(value, max)); }
}
