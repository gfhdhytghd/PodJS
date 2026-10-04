# Android speech services

`media.tts` provides `tts.init`, `tts.speak`, `tts.state` and `tts.stop` through the asynchronous host service bridge.

```ts
const ready = await speech.initializeLocal(["zh-CN", "zh", "en"]);
// Empty language preserves the voice selected by initialization.
await speech.speak("你好", "", 1.15).result;
// speak acknowledges enqueueing. Poll state until ready before enqueueing the next segment.
await speech.stop();
```

`initialize(language)` retains explicit-language behavior. `initializeLocal(preferredLanguages)` sends `preferredOfflineLanguages` to `tts.init`. The host enumerates visible TTS services, chooses the installed system default when present, then prefers offline voices by language order. If voice selection fails, it tries supported languages in the requested order. An empty or invalid list is rejected. Neither operation changes the system default engine or installs voice data.

An already initialized engine is reused. New initialization has a scheduled 10-second timeout reporting `tts_unavailable`; missing voices report `tts_language_unavailable`. Late initialization callbacks are scoped to their attempt. Ready state can include `engine`, `language`, and `voice`. A ready result establishes initialization only, not successful synthesis or audible output.

`tts.speak` reports enqueueing, not completion. `tts.state` is speaking until the matching Android utterance callback completes; error state is not treated as completion. `tts.stop` invalidates the active utterance before stopping the engine, so late completion cannot finish a replacement utterance. Applications must coordinate their own asynchronous initialization, polling and queue cancellation.

Validation: framework service tests cover capabilities, preferred-language forwarding and retaining the chosen voice; `PodSpeechVoiceTest` checks offline priority, network-only rejection, supported-language fallback and absence of voices without synthesizing. WatchRSS additionally exercises its production UI/controller using a clearly labelled scripted speech adapter, plus a separate actual-engine readiness probe that never calls speak. Utterance progress and shared media-volume access are described below; speech-page rotary controls and full background playback policy still need application-level work.


## Utterance progress

`speech.speak(text, language, rate, utteranceId?)` accepts an optional caller identity (at most 128 characters); state replies include the active identity. `speech.onProgress(listener)` returns an unsubscribe function. Events contain `type`, `utteranceId`, `elapsedMs`, and for `range`, UTF-16 `start`/`end`. Types are start/range/done/error/stop. The host uses monotonic Android elapsed time for elapsedMs and ignores callbacks for inactive utterances and closed services. Subscribe before requesting speech if an engine may call back before the request reply. Consumers must still match the identity and cancel their own session listeners. `range` is provider-dependent; its absence is not an error. The framework does not invent ranges.

WatchRSS applies its own native-compatible fallback model, source mapping and scrolling policy. Instrumentation uses a scripted TextToSpeech subclass invoking the production listener; it proves bridge and rendering behavior, not audible synthesis or range quality from an installed voice.

## Shared system media volume

`mediaVolume.state()` and `mediaVolume.setIndex(index)` require `media.audio`. They access Android `STREAM_MUSIC` independently of whether a media player or speech engine exists. Responses contain physical `min`, `max`, `current` indices and `fixed`; they are not normalized percentages. The setter rejects noninteger/out-of-range values, skips fixed streams and unchanged indices, requests no system volume UI, and returns actual system readback after setting. A system policy can clamp the requested index. This changes the shared stream, not the gain of one utterance.

This API does not choose a startup limit or impose audio-focus behavior. WatchRSS applies its configured startup limit once when creating a speech queue and skips a pending read after cancellation. Its native-compatible policy never increases a quiet stream, treats 0 as mute, and keeps positive percentages at least one physical step above the minimum. A volume-service failure does not block speech startup. Host instrumentation tests writes through an injected volume adapter and separately verifies real system readback without writing.
