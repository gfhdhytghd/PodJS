# Cooperative host jobs and stall diagnostics

Android hosts opt in to a **64-job / 2 ms** QuickJS drain budget before guest
boot. Either limit ends the drain at the next complete job boundary. A pending
job always gets at least one step, including after host descheduling; errors
consume budget as well. Hosts without a continuation executor retain the old
drain-to-empty default. The C ABI change is additive, with no config layout or
ABI-version change:

```c
pod_runtime_set_job_budget(runtime, 64, 2000);
// After a frame, lifecycle turn or IO completion:
if (pod_runtime_has_pending_jobs(runtime)) {
    // Schedule another host turn; do not loop inline.
    pod_runtime_pump_jobs(runtime);
}
```

`pod_runtime_pump_jobs` runs the existing FIFO only. It does not reenter the
framework's service pump, tick animation/layout, or publish a new draw snapshot.
`pod.takeEvents` is service-result-only during these nonvisual continuations;
navigation/lifecycle/unknown events retain their relative order for a visual or
explicit lifecycle turn. Promise order, values, exception/catch/finally chains
and exactly-once delivery are retained. Input and lifecycle can now interleave
between formerly unbounded runs of Promise reactions: this is the intended host
fairness change, not a promise that an entire async function is atomic.

Android coalesces completion wakeups in a FIFO mailbox, with a separate
32-callback / 2 ms batch limit. Remaining jobs and effects use a coalesced
continuation after a **1 ms real Looper yield**. New arrivals do not push back an
already posted continuation. No front-of-queue messages, inline retry loop or
`Promise.resolve()` pseudo-yield is used. Tokens bind callbacks to a host
lifetime even if native addresses/request IDs are reused. Destruction removes
queued continuations and callbacks. Ordinary host effects have a 64-effect /
2 ms batch limit, with a round-robin cursor across service effects and legacy
NET. The cursor survives a yield, so neither continuously busy lane can starve
the other; FIFO order within each lane is retained. Explicit Android save-state/screen-off durability barriers
still finish their pending state writes synchronously before returning.

A single JS callback, `JSON.parse`, native method, font layout, synchronous
checkpoint/fsync or GPU control operation is indivisible. The scheduler does
not throw an interrupt into running JS, roll back partial state or relax a
timeout. Large atomic operations still need bounded work at their owning layer.
The runtime's numeric `jobPump` diagnostics include completed jobs, yields,
job errors and maximum single-job microseconds, without arguments or values.

The main-thread watchdog has an independent 250 ms observer and one coalesced
main-loop ping. Above 1 s without acknowledgement it counts a stall before the
blocked turn returns. Its monotonic uptime excludes device deep sleep, so a
suspended watch is not counted as an unresponsive application. The render observer separately watches **outstanding work**
or a renderer control, not idle frame sequence distance. New frame replacements
cannot postpone that deadline. Background idle monitoring is disarmed, while
an executing background service/job/checkpoint remains monitored;
normal static content, absent draw demand and stale lifetime pings do not count
as renderer hangs. Both observers remain in release builds, count one incident
per blocked interval, retain aggregate counters beyond the log cap, and emit at
most 16 fixed-schema incident records per lifetime. `PodWatchdog` records only
lane, enum phase, delay, counters and a shutdown summary. No SQL, URL, labels
from app data, account, body, arguments or keys are collected.

Run the deterministic gates and the host soak:

```sh
bash scripts/test-host-scheduling.sh
bash scripts/test-frame-pipeline.sh
cargo test -p podjs-runtime
bash scripts/soak-job-pump.sh --seconds=600 --seed=470213 --max-turn-ms=50
```

The soak runs production PodRuntime/QuickJS and checkpoint KV with synthetic
network completion facts. It randomizes input/navigation, concurrent JSON
work, offline outcomes, background/resume and realm restart, asserting input
receipts, exact ACKs/checksums, bounded backlog and durable state restoration.
It allocates and removes only its own fresh scratch directory. Its output is
numeric JSONL. It never uses adb, a production server/account or a user DB.
`TMPDIR=/dev/shm/...` isolates scheduling/semantic tests from physical storage;
report that boundary explicitly. Physical-disk failures remain failures.

Negative controls must exit nonzero under the same fixed 50 ms gate:

```sh
bash scripts/soak-job-pump.sh --seconds=2 --seed=470213 --legacy=1
bash scripts/soak-job-pump.sh --seconds=2 --seed=470213 --inject-stall=1
PODJS_TEST_LEGACY_JOB_PUMP=1 cargo test -p podjs-runtime interactive_service_ -- --nocapture
```

The last control selects the legacy configuration only in the test harness;
it does not change the production policy. Android's
`PodRuntimeForegroundServicesTest` also drives real JNI completions without
Choreographer rescue and asserts a real main-thread touch can run in 50 ms
while a long JSON Promise chain remains unfinished. Its new/retained-event,
HTTP/file, cancellation and replaced-host tests must be run on an emulator or
an exclusively owned device. Building this APK does not constitute device
acceptance. Application acceptance additionally needs the real app's workloads
and at least 30 minutes of continuous input plus concurrent IO,
with data/ACK checks and ANR/freeze statistics at a fixed declared threshold.
