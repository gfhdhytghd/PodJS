# Optional TTS synthesis spectrum

Android/Wear OS advertise `media.tts.spectrum` separately from `media.tts`.
Older hosts reject `speech.setSpectrumEnabled()` and `speech.onSpectrum()` with
`HostServiceError("unsupported")`; ordinary speech calls remain available.

```ts
import { speech } from "@podjs/framework/services";
const unsubscribe = speech.onSpectrum(event => {
  // event.type === "reset": clear the visualization (levels is empty).
  // event.type === "frame": five low-to-high frequency levels in [0, 1].
});
await speech.setSpectrumEnabled(true);
// Initialize and speak using the existing speech API.
// When finished observing:
await speech.setSpectrumEnabled(false);
unsubscribe();
```

The native method is `tts.spectrum {enabled: boolean}`, returning `{enabled}`.
The event is `speech.spectrum` with `type`, `source: "synthesis-pcm"`,
`utteranceId` (public guest ID, empty if idle), `sampleRateHz`, and `levels`.
Subscription alone does not enable analysis; unsubscribing alone does not disable it.
Enabling is shared by the service session and idempotent. It is off in each new host.

The source matches WatchRSS `ReadAloudAudioSpectrumAnalyzer.kt` and
`ReadAloudController.kt`: `UtteranceProgressListener.onBeginSynthesis` supplies
format metadata, and `onAudioAvailable` supplies synthesized PCM. No Visualizer,
RECORD_AUDIO permission, microphone, or audio-output capture is used. Engines
that omit PCM callbacks generate no spectrum frames; no artificial animation
is substituted. Synthesis may run ahead of playback, so events are not speaker
playback timestamps or a guaranteed 60 Hz wall-clock stream. Speaker mute and
volume changes do not alter synthesized PCM levels.

Reference parameters retained in the Java port:

- JTransforms 3.2, 512-point real FFT with Hann window and magnitude scale 2/512.
- Five logarithmic bands, 90–4200 Hz; minimum limited to Nyquist * .5, maximum
  limited to Nyquist * .92 and at least minimum * 1.5.
- Inclusive ceil/floor FFT bins, RMS magnitude per band, dB normalization
  from -58 to -16 (minimum magnitude 0.000001), clamped to [0,1].
- Attack .64, release .24; hop `max(sampleRateHz / 60, 32)` samples, after a full
  512-sample window. Rate changes clear the analysis window.
- PCM unsigned 8-bit, signed little-endian 16-bit, little-endian float;
  channel average before FFT. Incomplete trailing PCM frames are ignored as
  in the reference. Malformed nonfinite floats are additionally sanitized to zero.

Silence PCM follows the release envelope toward zero. New utterances, synthesis
begin, stop (including stop-based pause), done, error, and enable/disable changes
clear accumulated analysis; reset events have empty levels. Resume is a new speak.
No separate pause method is added. Close discards pending events. A private unique
engine utterance ID plus generation checks reject late callbacks, including reuse
of a guest's public ID. An analyzed-frame queue caps pending delivery at 96 and
drops the oldest frame, matching the reference stream capacity; raw PCM is never
queued. Analysis occurs on the synthesis callback thread, delivery on the main
handler. This runtime adds no manifest permissions.

`bun run test` includes the TS capability/event tests and Cargo tests.
`PodSpeechSpectrumTest` contains direct-DFT reference comparisons, PCM formats,
channels, cadence, smoothing, resets, stale callbacks, queue bounds, and service
callback coverage. For F2 these Android tests are compiled only, not executed;
actual engine/device timing and UI parity remain unverified.
