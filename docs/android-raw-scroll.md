# Android raw scroll input

`onRawScrollInput(listener)` in `@podjs/framework/watch` requires the existing `input.rotary` capability and returns an unsubscribe function. It delivers `{vertical, uptimeMs, source}` for Android `ACTION_SCROLL`: `AXIS_VSCROLL` first, with `AXIS_SCROLL` fallback. Positive values mean upward Android scrolling; timestamps retain event uptime and source retains the Android source bitmask. Zero or nonfinite values are rejected.

This event preserves physical units before mouse-wheel scaling and direction conversion. The existing normalized `RelativeAxis` event is still emitted, with its previous 15-degree mapping and mouse scaling unchanged. A control must choose one stream rather than applying both. The raw event is Android-specific; desktop/browser hosts do not currently produce it.

WatchRSS speech volume consumes raw scroll input only while its playback page and volume setting are active. It applies the native reverse direction, 0.01 input scale, and 0.06 volume-range sensitivity using Float arithmetic. It retains fractional virtual volume, processes each guard transition in timestamp order, and coalesces final host writes while awaiting system readback. The ordinary list scrolling route continues to use normalized axes.

Validation includes SDK capability/event/unsubscribe tests, Android mouse and rotary raw-value tests, existing normalized-direction tests, and instrumented WatchRSS input through its production JS/controller/host path using an isolated volume adapter. These tests do not establish actual physical crown feel or real speaker volume acceptance.
