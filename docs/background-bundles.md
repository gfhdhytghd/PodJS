# Independent background bundles

Declare handlers separately from the UI entry in `pod.config.json`:

```json
{
  "entry": "src/main.tsx",
  "background": {
    "refresh": "src/background/refresh.ts"
  }
}
```

This snippet extends the normal project configuration; `appId`, version, name
and capabilities are still required. Up to 32 handler IDs are accepted. A handler
cannot point at the configured UI entry.

Each background module exports one default handler:

```ts
import { defineBackgroundHandler } from "@podjs/framework/background-handler";

export default defineBackgroundHandler(async context => {
  if (context.isCancelled()) return "failure";
  return "success";
});
```

The context supplies `appId`, `taskId`, `deadlineMs`, `payload` and `isCancelled()`. Return
`success`, `retry` or `failure`, directly or through a Promise. Do not start
unawaited work or assume precise execution times. Authorized async host services
are not integrated yet.

The host-only Android scheduler accepts an optional JSON payload (default `null`).
It serializes a snapshot before enqueueing and persists it alongside the task, so
later mutation of the caller's object cannot change the scheduled data. Payloads
are limited to 64 KiB of UTF-8 JSON and count toward the per-app storage quota.
The independent runtime parses payloads as data, never as JavaScript source.

Android hosts can construct `PodBackgroundPackage` from an independently approved
app identity, authenticated manifest, and package asset reader. It snapshots the
manifest, resolves only declared handler IDs, restricts artifact paths to the
builder's `background/<sha256>.js` format, and checks exact byte length, strict
UTF-8 and SHA-256 before enqueueing. `schedule` accepts a handler ID, not guest
source or an expected hash. Authentication of the package/publisher and guest
service authorization remain the host's responsibility; this helper does not
make untrusted manifests trustworthy or advertise scheduling availability.

`PodBackgroundServices` binds the register/cancel/status wire methods to one
approved package. Task IDs are independent of handler IDs; status resolves the
latest persisted run for that app/task, including after reopening the service.
Caller-supplied app identities cannot change this scope. Retry is exposed as
`scheduled` with result `retry`; periodic requests currently return unsupported.
The asynchronous `PodServices` transport accepts an explicitly supplied approved
route and denies background requests when none is supplied. After successful
native package boot, the production view queues approval of its installed APK
assets before later service requests. It uses the Android package name as app
identity, verifies the target, bounds manifest reads to 128 KiB and fails closed
if approval fails. This trusts the OS-installed APK, not arbitrary downloaded
packages. Android/Wear OS profiles now advertise one-shot `background.scheduled`;
background code still has no async host services. Rebuild packaged manifests
with the matching host because capability lists are validated at boot.

The OWW242 production-View test now launches the fixture Activity and lets its
QuickJS UI guest call the framework `background.register` API. It requires a new
persisted run (not an earlier success), verifies the guest payload marker and
handler success. The separate cold-wakeup probe covers process exit. Wear OS
shares this implementation but still needs its own physical-device acceptance.

Installed-APK regression (OWW242 verified): build the fixture, then run the app
instrumentation test with its dedicated application ID and asset directory:

```sh
bun packages/cli/src/cli.ts build --target=android-watch --project="$PWD/tests/fixtures/background-apk"
cd platforms/android
./gradlew :androidApp:connectedDebugAndroidTest \
  -PpodAppId=dev.podjs.backgroundtest \
  -PpodDistDir="$PWD/../../dist/projects/dev.podjs.backgroundtest/android-watch" \
  -PpodTestSourceDir="$PWD/../../tests/android-background" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.podjs.runtime.InstalledBackgroundTest
```

This checks packaged assets, queued approval, asynchronous register dispatch,
real WorkManager execution without an Activity, persisted success and target
mismatch rejection. It does not start a UI guest or kill the process, so it is
not evidence of guest capability availability or system cold wakeup.

Cold-wakeup probe (separately verified on OWW242): install both built APKs with
`adb install -r`, then invoke the `scheduleColdWakeProbe` instrumentation method
with `-e coldWakeProbe true`. Do not use Gradle connected tests for this phase:
their cleanup may uninstall the app and remove the scheduled job. The probe
queues a 45-second delayed task and logs its run ID under `PodJSColdWake`.
After instrumentation exits, confirm `pidof dev.podjs.backgroundtest` is empty
and JobScheduler still holds the delayed job. Observe natural execution without
`force-stop` or `cmd jobscheduler run`. Then invoke `verifyColdWakeProbe` with
`-e coldWakeRunId <logged UUID>` to assert both persisted success and WorkManager
`SUCCEEDED` for that exact run.

On 2026-09-08, run `0fb06be3-97c6-46e0-9498-e0c7bde89dad` was scheduled by PID
24470 at device time 03:38:18; that process exited, and PID 24516 completed the
same run at 03:39:04. The read-only verification passed. This proves a one-shot
system wake after process exit on this device, not reboot, force-stop recovery,
Doze guarantees, other vendors, or a UI guest request.

Rust embedders can now opt into `background::run_with_services` with a host-owned
method allowlist and asynchronous dispatcher. Only this opt-in path supplies
`context.request(method, args)`. Requests bind app/task identity in Rust, carry the monotonic deadline
and a cancellation signal, and exchange JSON rather than code. Dispatch must
return immediately and perform bounded IO elsewhere. The runner allows 16
in-flight and 32 total requests, limits each request/reply to 64 KiB, waits only
for actual pending host work, and cancels outstanding work on every exit.
Cancellation does not undo writes already committed by a host adapter.

Core tests cover delayed replies, permission rejection, reply limits, invalid
JSON, reentrant serialization, deadline cancellation and unawaited request
cleanup. No filesystem/network permissions are implied by installing this bridge;
concrete Android and other platform service adapters remain to be connected.

The C ABI opts in with `allowed_methods` in the host-only open configuration.
Run `execute` on one worker and consume `pod_background_poll` from another host
thread. Poll returns borrowed UTF-8 JSON with request ID, bound app/task, method,
arguments and remaining budget; copy it before the next poll. Reply once with
`pod_background_reply` using `{id,ok:true,value}` or `{id,ok:false,code}`. Invalid
replies do not consume a pending request; duplicate or late replies are rejected.
Execute completion and cancellation clear the queue. Close only after execute,
poll, reply and cancel callers have all stopped. The default empty allowlist
keeps `context.request` absent.

Android `PodBackgroundRun` now accepts a host-approved `String[] allowedMethods`
in its optional constructor and exposes synchronized `pollRequest()` / `reply()`
methods. Execute `run()` on a separate worker. Poll returns null when no request
is available; reply returns false for duplicate/late/closed responses and rejects
malformed envelopes. JNI transfers request JSON as UTF-8 bytes, preserving both
non-Latin text and supplementary Unicode characters. Closing cancels execution
and defers destruction until it exits. OWW242 tests verify request/reply, Unicode,
method denial and close during pending IO. WorkManager still uses the empty
allowlist; approved persistent permissions and concrete IO dispatch are pending.

The native KV adapter is now available to approved hosts via an absolute
`kv_root` plus a subset of `kv.get`, `kv.set`, `kv.delete`, `kv.keys` grants.
It uses the foreground JSON store format and reloads under an OS lock for each
operation. `get({key})` returns `{exists,value?}`, `set({key,value})` returns null,
`delete({key})` returns a boolean, and `keys({})` returns an array. Handler args
cannot select a root. `BackgroundContext.request` is optional in the type contract.
Unix lock waits check cancellation/deadline, and writes check again before atomic
rename. Disk syscalls themselves are not preemptible; already committed changes
are not rolled back by cancellation. Non-Unix bounded locks are currently
unsupported. OWW242 verified KV persistence across two independent native runs;
Rust tests verify foreground/background sharing and lock-timeout non-mutation.
Projects opt in with a `backgroundServices` array in `pod.config.json`, containing
only unique supported KV method names (default empty). The builder records this
array in the manifest. Android saves the approved method snapshot with each job;
on every run the Worker rechecks the current installed manifest grants and source
hash, requires the app identity to match its Android package, and supplies the
fixed `<filesDir>/podjs` root shared with the foreground runtime. Guest task
arguments cannot override grants or the root. Revoked grants or a removed source
fail closed; package-upgrade revocation has also been verified on OWW242.
The OWW242 production guest fixture now verifies foreground KV input, background
async read/write, and the persisted Unicode output through this WorkManager path.

Upgrade-revocation probe: install version 1 from `background-apk`, run
`scheduleColdWakeProbe` with `coldWakeDelayMs=90000`, then build
`tests/fixtures/background-revoked` and install its APK with Gradle
`podVersionCode=2` / `podVersionName=0.2.0`. The handler hash stays identical while
`backgroundServices` becomes empty. Do not launch the Activity or force the job.
After natural execution, invoke `verifyRevokedProbe` with the original
`revokedRunId`. On 2026-09-08, run `b78f0a50-fccd-482a-9aac-868e43ef1029` was
scheduled at device time 04:10:24, survived the version-2 upgrade, and was denied
at 04:11:55. The verification confirmed persisted `failed`, WorkManager `FAILED`
and `background_permission_denied`. This tests queued-task revocation, not
interrupting an already-running handler during an update.

The existing `pod build` and `pod package` commands build each background handler
into its own IIFE. `pod.manifest.json.background` maps each handler ID to its
relative file, SHA-256 and byte length. Filenames are derived from a hash of the
handler ID and live under `background/`, avoiding platform-specific filename
characters. Bundles are limited to 1 MiB; UI bootstrap and asset packing are not
used for the background entries.

The builder rejects known UI/unrestricted host imports and statically resolved
dynamic imports. This is a build guard, not a security sandbox: the independent
QuickJS runtime enforces execution, memory and capability restrictions. Only the
pure `background-handler` framework entry is currently allowed at build time.

For diagnostic execution of a built artifact:

```sh
cargo run -p podjs-runtime --example background-run -- /absolute/background.js <sha256-from-manifest>
```

The diagnostic runner verifies the hash and runs without a UI. It is not an OS
scheduler. Android's host-only WorkManager adapter can execute verified source,
but installing these artifacts into guest-visible `background.register`, periodic
scheduling and per-manifest service grants remain pending. Building a background
entry does not automatically enable `background.scheduled` for a target.

## Harmony host execution bridge

`NativeBackgroundSchedules` now connects durable registration/cancellation and
reconciliation to the OS adapter. Retry creates a new work ID and generation,
retains the parent for OS cleanup, and persists its earliest start with exponential
backoff (30 seconds initially, capped at one day). OS throttling may delay it
further. Repeated recovery finds the same retry child instead of multiplying jobs;
cancel and newer registration suppress older retry intents. Reconciliation cancels
old work before submitting new work. The extension entry and guest service routes
are still pending, and completed history is retained subject to the 128-record
capacity rather than silently evicted.

`NativeBackgroundExecution` now connects the lease, durable claims and execution.
It opens an inert host-controlled lease holder, recovers abandoned claims only
under that lease, claims the requested task, reloads the current installed package,
and configures the verified handler via `backgroundConfigure`. Configure is
rejected for unleased, cancelled, closed or already-started handles. The inert
holder is never executed by this driver. Native KV root and app identity come
from the OS context, not stored task arguments. Result persistence precedes close;
an uncertain commit remains recoverable by the next lease owner. OS extension
callbacks, retry registration and guest scheduling routes are still pending.

Production execution should use `backgroundOpenLeased(config, filesDir)` rather
than the unleased diagnostic opener. It resolves only after acquiring the
`podjs-execution` process-independent file lock. Keep the returned handle open
while reconciling abandoned claims, claiming a task, executing it, and committing
the result. Then close in `finally`. A busy lock rejects with code `busy`; no
wall-clock timeout can prove the previous process stopped. Closing during
execution cancels but retains the lock until the native worker has returned.
The lease and task-store locks use different directories and can coexist.
`test-harmony-background-napi.sh` exercises this with real QuickJS and separate
processes, including closing during execution and terminating a lock-owning test
process. The Work Scheduler extension and retry coordinator still need to connect
this lifecycle to system wakeups.

`BackgroundJournal` adds the durable task model over CAS, with app binding,
monotonic revision and work IDs, generation tokens, cancellation, execution
claims and claim-matched result commits. Only one execution claim may be active
per app. Replacing a running task marks it cancelling but does not release its
claim; a later native result cannot overwrite the replacement. There is no
wall-clock lease expiry. The execution driver must acquire an independent native
cross-process lease before claiming or declaring an old execution abandoned;
`NativeBackgroundExecution` implements that lifecycle. Retry scheduling and terminal-record reclamation
are also still pending. The native store test script additionally runs this actual
state model in five processes and verifies durable ID allocation and claims.

The host-only `backgroundStoreRead(filesDir)` and
`backgroundStoreCompareExchange(filesDir, expected, desired)` provide atomic
cross-process updates in a separate `podjs-background` private directory.
`expected` is either the exact previous UTF-8 contents or `null` for a missing
file. A stale comparison returns `false`; lock contention rejects with code
`busy`. Every mutation must include a monotonically changing revision in the
task-state format to avoid ABA. IO rejection may occur after rename, so reread
instead of assuming no commit. Keep retries bounded; do not retry permanent
storage errors as contention. Test the real file/process boundary with
`bash scripts/test-harmony-background-store.sh`.

`NativeSystemWorkScheduler` is the host-only system registration adapter. It
requires API 22 and the Work Scheduler system capability to use the documented
[earliestStartTime field](https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/api/js-apis-resourceschedule-workscheduler).
It sets a battery condition covering both low and normal states, because the
system requires at least one condition, and adds an any-network constraint only
when requested. Relative delay is computed immediately before registration.
The durable coordinator must allocate non-reused native IDs and random run IDs
before invoking it, and must serialize concurrent mutations. Its OS enumeration
and cancellation are not an atomic cross-process transaction. The background
extension and durable coordinator are not connected yet.

The native shell exposes host-only `backgroundOpen(config)`,
`backgroundExecute(handle): Promise<string>`, `backgroundCancel(handle)` and
`backgroundClose(handle)`. These are not guest service methods. Config uses the
existing C ABI JSON format and must be assembled by a trusted host from an
approved installed bundle, not copied from a guest request. Always close the
handle in a `finally` block after consuming its result. Closing during execution
cancels immediately but retains native ownership until execution returns.

The bridge runs QuickJS on a native async worker without creating a renderer or
driving UI frames. It does not register an OS task, persist results, authenticate
a bundle, or automatically authorize background services. Harmony scheduling
capability remains disabled until those host layers and device acceptance exist.
The system adapter must account for [Work Scheduler's condition and execution
constraints](https://github.com/openharmony/resourceschedule_work_scheduler),
rather than promise exact-time execution.

Reproduce the native bridge checks on Linux with Node development headers,
Clang and Rust installed:

```sh
bash scripts/test-harmony-background-napi.sh
```

On a Windows **isolated build checkout** with DevEco and an existing
`dist/harmonyos-watch` bundle, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-harmony-background-package.ps1
```

This inserts a temporary handler, checks that invalid paths/hashes/sizes are
rejected, builds an unsigned HAP, and checks the actual archived script and
manifest. It restores the original manifests and removes only its own temporary
files, then rebuilds the original HAP. It does not install or launch an app.
For an exported checkout, supply the verified `PODJS_POCKETJS_REVISION` required
by the Rust build, as for the normal Harmony packaging command.

### Android cloud workers in release builds

Installed APKs may explicitly declare `backgroundServices: ["cloud.host", "kv.get"]`
and a `watchrss-cloud-sync` handler. The host verifies the bundled handler size and
SHA-256 before scheduling, acquiring the process-independent writer lease or
executing the headless worker. `cloud.host` uses the existing enumerated data/crypto/HTTP
method allowlist and bounded cloud profile (5 minutes, 256 MiB, 16 in-flight calls,
1 MiB messages); it does not grant UI or arbitrary host operations. Missing or
revoked grants and modified bundles remain denied.

`debug.cloudHost` remains accepted for older debug APKs, and never authorizes a
release APK. Build type does not disable the explicit production grant. Account
consent, credentials, membership, key approval and transport policy are enforced by
the application coordinator. The periodic worker remains 6-hour/unmetered with
backoff, and cancellation/release remain available for cleanup.
