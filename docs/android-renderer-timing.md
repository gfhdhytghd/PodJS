# Android renderer CPU timing

The Android host has an optional renderer trace for bounded diagnostics. It is
disabled by default and is not a guest service, application property or renderer
mode. Enabling it does not change texture allocation, pixel conversion, image
layouts, fences, frame scheduling or fallback selection.

The package-private `PodRuntimeView.beginRendererTiming(capacity)` and
`endRendererTiming()` hooks require the main thread and a live host. Capacity must
be 1–4096 stage records. Beginning allocates the native fixed buffer once and
rejects an existing recording. The View retains the host lifetime token, so
ending cannot read a destroyed host or a replacement at a reused native address.
Destroying the renderer releases any remaining trace.

End detaches and stops the native trace before allocating the returned Java
`long[]`. Both native and Java allocation failures therefore leave recording
disabled and release the former trace. Call end once in cleanup and retain its
result for later serialization; an allocation failure is an incomplete sample,
not an instruction to resume recording. During the measured interval no getter,
Java observer or extra native frame is needed. There is no per-event JSON or log
output. The instrumentation test owns formatting after recording has stopped.

The trace uses explicit `clock_gettime(CLOCK_MONOTONIC)` nanoseconds. AOSP's
`System.nanoTime()` uses that clock as well ([libcore implementation](https://android.googlesource.com/platform/libcore/+/a09e0fb8d276c7f56d28472cce972274ad27c822%5E%21/)).
Device tests should bracket both begin and end with Java `System.nanoTime()` and
verify that the native start/stop markers lie within those brackets before
joining Java and native timestamps. This tests the installed implementation; it
does not assume an epoch relationship to wall time or `elapsedRealtimeNanos()`.

The returned array has this seven-value header, followed by `count` rows of
15 values each:

| Header index | Value |
| --- | --- |
| 0 | Schema version, currently 1 |
| 1 | Fields per row, currently 15 |
| 2 | Allocated record capacity |
| 3 | Stored row count |
| 4 | Dropped rows after capacity was exhausted |
| 5 | Native recording start, monotonic ns |
| 6 | Native recording stop, monotonic ns |

| Row index | Value |
| --- | --- |
| 0 | Record sequence, reserved when the scope begins |
| 1 | Host frame sequence; zero denotes work outside a native frame |
| 2 | Existing renderer frame serial at scope entry; zero is valid |
| 3 | Stage code |
| 4, 5 | Scope begin and end, monotonic ns |
| 6 | Outcome: 1 for success, -1 for failure |
| 7 | Actual result code, or `Long.MIN_VALUE` for an unfinished failure scope |
| 8, 9 | Texture identity and revision when applicable |
| 10, 11 | Texture width and height when applicable |
| 12, 13 | Stage bytes and item count when applicable |
| 14 | Stage flags |

Records remain in begin/reservation order. A parent row can end after later rows;
sorting by its end timestamp or adding parent and child durations gives incorrect
results. Host frame sequence is the join key for the JNI frame, guest call,
snapshot and renderer work. The renderer increments its existing frame serial
after decode, so scopes within one host frame can contain different renderer
serials. A trace with dropped rows is incomplete and must not be reported as
complete stage accounting. Unused metadata values are zero. Texture identities
and revisions retain their 64-bit bit patterns, including values displayed as
negative Java longs; they are not resource pointer addresses.

| Code | Stage |
| --- | --- |
| 1 | Complete `nativeFrame` host call |
| 2 | `pod_runtime_frame`, including guest work and its existing UI tick |
| 3 | Draw-list snapshot |
| 9 | Complete GPU draw-list attempt |
| 10 | GPU draw-list decode and CPU image conversion |
| 11 | Complete image upload/cache pass |
| 12 | Fence wait before image upload/cache work |
| 13 | Texture resource creation |
| 14 | Staging-buffer pixel copy |
| 15 | Upload command allocation/recording |
| 16 | Upload queue submission |
| 17 | Upload fence wait |
| 18 | Frame fence wait |
| 19 | Swapchain image acquisition |
| 20 | Scene command recording |
| 21 | Frame queue submission |
| 22 | `vkQueuePresentKHR` call |
| 23 | Texture resource destruction |
| 24 | Existing texture cache hit |
| 25 | Successfully committed image upload |
| 26 | Staging-buffer capacity check/growth |
| 27 | CPU compatibility-frame submission |

Texture flags are bit 0 linear sampling, bit 1 coverage, bit 2 an existing
resource, and bit 3 the white primitive texture. For host-frame rows, bit 16 means
a GPU draw-list submission succeeded and bit 17 means the CPU compatibility path
submitted. Host-frame outcome/result describe `pod_runtime_frame`; successful
guest work alone does not mean a new scene was submitted. Inspect nested stage
outcomes and submission flags. Unchanged frames can correctly have neither flag.

Count resource creation/destruction, cache hits and committed upload bytes from
their own stages. An upload attempt is not a committed upload, and a cache hit is
not an allocation reuse optimization: this diagnostic patch preserves the
existing recreation of resources for changed texture revisions. A GPU draw-list
failure followed by a successful CPU-frame row is an observed fallback, not a
successful GPU draw.

All durations are CPU wall time, including blocking and scheduler waits. Fence
return can establish completion of the commands associated with that fence but
does not isolate GPU execution time. Swapchain acquisition and present calls may
block; their durations must not be attributed to texture allocation. A successful
present call is not proof of physical display time. No timestamp replaces a
presentation fence or panel measurement.

Failure scopes retain their end timestamps and outcome without changing the
original error return, fence recovery, resource cleanup or compatibility path.
Diagnostic scopes are no-ops while disabled. Enabled probes still have overhead;
compare matched configurations and report the trace state, clock checks,
overflow, APK/JNI identity and pixel checks with any performance result.
