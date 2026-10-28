# JavaScript stall diagnostics

Android debug hosts enable observational QuickJS stack sampling by default.
Release hosts keep the existing lightweight main/render stall counters; JS
sampling is off unless explicitly enabled. Add application manifest metadata
`dev.podjs.diagnostics.jsStack` with a boolean to force either state. Without a
metadata override, `adb -s SERIAL shell setprop log.tag.PodJSStack DEBUG` enables
sampling on the next host/view creation, including release. Restore the prior
property after the run. An explicit metadata `false` also disables debug capture.

With sampling enabled, the independent watchdog requests one sample when an
outstanding awake heartbeat reaches 300 ms, checked on a 100 ms observer tick.
Without a sampler, the lightweight observer retains its 250 ms tick.
The release stall counter and warning retain their one-second deadline. One
request per outstanding heartbeat avoids repeated captures of the same job.
Only an atomic request crosses threads. At the next QuickJS
interrupt point the executing thread reads the live stack and immediately writes
`PodJSPerf jsStack phase=... delayMs=... record=... frames=...` to the existing
Android perf log. Phase values are idle=1, service=2, jobs=3, frame=4,
lifecycle=5 and input=6. Acknowledging the heartbeat cancels an unconsumed request;
closing the observer serializes native handle destruction with requests.

Each runtime emits at most 16 stack records, each below 2200 bytes and containing
at most eight frames. Each frame contains a bounded ASCII code function name,
code filename basename and line/column. Arguments, locals, source text, directory
paths and URL query/fragment text are excluded. Line 0 means unavailable,
including when the bounded PC-to-line scan cannot reach the interrupted PC.
The reader does not allocate QuickJS objects, call getters or Error hooks, mutate
the pending exception, or terminate the running job.

Sampling is cooperative: native calls without QuickJS interrupt points, blocked
syscalls and deadlocks cannot yield a live JS sample until execution reaches an
interrupt point. No sample does not prove absence of a stall. Capture time is
later than the watchdog request time and may identify a later function in the
same busy interval; correlate it with the original ANR and service timeline.

Use `pod build android-watch --project PATH --diagnostic-js-stack` or the same
flag on `pod package` for an attribution candidate. This opt-in leaves identifiers
and bundle line maps readable; normal builds retain their current minification.
Retain the exact `main.js`, manifest, APK hash and matching native BuildID symbols.
The diagnostic bundle can have a different size/startup cost, so a later default
candidate still needs independent acceptance.

With the same diagnostic switch, service-result entry callbacks exceeding 50ms
emit `PodJSPerf serviceCallback service=... callback=... elapsedNs=...` (64 records
per view). `postHostCompletion.serviceResult` and `.authStatus` measure the entire
synchronous host entry, including jobs drained there. They are not exclusive
business-function CPU times. Header metadata parsing is capped at 1024 characters;
late/unavailable metadata is unknown rather than scanning a second response tree.

Guest `resolve`, `reject` and `status` callbacks have separate `elapsedMs` records,
also above 50ms and bounded to 64. Resolve/reject settlement does not include later
Promise continuations; use the interrupt stack for those. Service labels are
validated against the host's existing allowlist; payloads/errors/SQL are omitted.

Run `bash scripts/test-js-stall-diagnostics.sh` for real live-QuickJS and
service-result C ABI regressions, callback boundaries, disable/cancel/coalescing,
record limits and handle lifetime. Android Robolectric tests independently cover
the paused-main watchdog request/ack/close path and deep-sleep exclusion.
