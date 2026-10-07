# Service completion turns

`PodRuntimeView` measures each `service.result` / `browser.auth.status`
completion turn, including `nativePostEvent`, the QuickJS Promise-job drain,
and resulting host-effect dispatch. It measures a turn, not an individual
Promise reaction or network latency. Legacy NET completions and visual frame
turns are outside this trace.

Collection requires **both** the host's debuggable flag (the same flag returned
by `app.buildInfo().debugBuild`) and `log.tag.PodJSService=DEBUG` when constructing
the view. The default is off; opting in does not enable heap scans or force GC.
The device owner can opt in before relaunch and collect the `PodJSService` tag.
Restore the previous property and relaunch afterward. Do not change PodJSMem.

Only turns over 16 ms produce records. The request table holds at most 64
category entries and evicts oldest diagnostic metadata if full. Each host view
emits at most 128 slow records, with `capped=true` on the last one. Counters
continue after the cap. Destruction clears the table and counters.

Records contain a fixed method category, elapsed nanoseconds, completed/slow
turn counts, metadata evictions and emitted-record count. They contain no
request IDs, paths, arguments, URLs, values, cookies, account IDs or errors.
Unrecognized method names become `other`. An unmatched result also becomes
`other`. Diagnostics never authorize a host operation.

The optional pure `pod.decodeUtf8(Uint8Array)` codec compiles byte decoding in
Rust. It respects typed-array view offsets, returns an owned string, and returns
null for invalid UTF-8 so the application's old byte semantics remain available.
It does not move execution to another OS thread. Media callers use bounded
pieces and a real host delay between pieces; they must not substitute a resolved
Promise, because the current service turn drains pending QuickJS jobs.

Host tests: `bash scripts/test-service-result-trace.sh`, `bun run test`,
`:runtime:compileDebugJavaWithJavac`. The `service-bench` Cargo example uses the
runtime's exact rquickjs dependency and its compiled UTF-8 function. No test
invokes ADB or runs on a physical device.
