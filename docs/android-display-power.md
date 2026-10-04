# Android display and optional silence leases

`displayPower.state/keepAwake/onChange` requires `display.power`. State includes interactivity, activity resume state, owners, applied flag, and actual window KEEP_SCREEN_ON. Android screen broadcasts and lifecycle events update subscribers. Guest owner strings are validated and limited to 64 independent owners. A paused activity retains ownership but clears the SDK-owned flag; resume reapplies it. A pre-existing window flag is preserved. Close unregisters the receiver and releases ownership.

`silencePlayback.state/setEnabled/lease` uses the media audio service. It defaults disabled. When enabled with at least one owner, a single real AudioTrack loops 8 kHz mono 16-bit zero PCM. Settings disable, last-owner release and close stop/release it. State queries do not restart playback. This is independent of speech and existing audio focus control.

Static AudioTrack starts in STATE_NO_STATIC_DATA; initialization checks require STATE_INITIALIZED only after a complete PCM write. The watch instrumentation initially failed on checking this before writing; the corrected implementation passed both native power cases. Tests exercise actual window state, pause/resume, pre-existing flags, multiple owners, settings and AudioTrack lifecycle.

Integration evidence lives in the consuming WatchRSS application's `docs/sync-application-foreground.md` and `artifacts/power-receipt.json`. A physical paired-phone ACK completed while its isolated guest activity was paused, with actual display/audio state assertions. Physical screen-off acceptance and survival of OS process termination remain unverified.
