
## URI components and HTML text files

`uri.parse(value)` requires `data.uri` on Android/Wear OS. It returns `{valid, scheme, host, rawPath, rawQuery}` using `java.net.URI`; malformed syntax returns `valid: false` with null components. Service/transport errors remain rejected promises. It performs no URL normalization or network request.

`fileChunks.htmlText(path, maxOutputBytes)` requires `data.files.chunks`. Android extracts Jsoup 1.17.2 text from a UTF-8 HTML file into a temporary UTF-8 file and returns `{path,size,sha256}`. Input and requested output limits are at most 32 MiB. The caller removes the output after use; canceled service requests discard unpublished temporary output. URI/HTML functions are generic services; application identity and sync metadata rules remain in application code.

### Committed SQLite statement counts

`sqlite.transactionChanges(statements)` uses the same `data.sqlite` capability and atomic execution as `sqlite.transaction`. It returns one SQLite `changes()` value per statement after successful commit. SQL failure or cancellation rejects without returning counts. SQLite defines these as direct DML counts; trigger side effects are excluded, and non-DML statements may retain the previous count. Use the indexes of the intended DML statements rather than summing schema or query operations. The ordinary transaction API continues to return no value.

`uri.parse(value, syntax?)` accepts `"java"` (default) or `"android"`. The optional
Android branch returns `android.net.Uri` scheme, host, encoded path and query;
it is permissive and reports `valid: true`, so callers must apply their own
scheme/host constraints. The default Java parser continues returning
`valid: false` for syntax errors. Both use the existing `data.uri` capability.

SQLite query parameters preserve their JSON scalar types (null, integer, real,
and text) through typed SQLiteQuery bindings, matching execute/transaction
binding behavior. The older String[] query path incorrectly made numeric
aggregate predicates such as `HAVING MIN(seq)>?` compare against TEXT. Device
regression coverage includes typeof(), aggregate comparison and a real
same-clock reading-state delta through the application repository.

Classic Bluetooth authorization is exposed as `rfcomm.requestPermission()` (a cancellable host operation). On Android 12 and newer it requests `BLUETOOTH_CONNECT` from the current activity; earlier versions return the already granted state. Cancellation removes only that request's callback and leaves the system dialog decision to the user. A second request remains busy until the system result arrives. Both Android watch target profiles and the JNI boot capability list now expose `net.rfcomm` and `net.stream`.

The Android UI runtime delivers lifecycle/IO completions through the framework's realm-scoped service pump while visual frames are suspended. This additive C ABI operation (`pod_runtime_pump_services`) drains Promise jobs but does not invoke the UI frame function or commit a draw/layout tick. It is event-driven and does not create periodic background jobs. Pending operations still end when their owning process/runtime is destroyed; this is not an OS foreground-service or scheduled-work guarantee.

### Foreground asynchronous service completion

Android also advances completed service requests between visual frames. Each host
completion is posted to the main looper, checked against its original guest
lifetime, then delivered through `pod_runtime_pump_service_results`. This additive
C ABI operation runs the existing service pumps and drains Promise jobs while
`pod.takeEvents()` selects only `service.result` records. Other events stay queued
in their original order for the ordinary frame path. The older
`pod_runtime_pump_services` operation retains its full event-delivery behavior for
lifecycle and saved-state processing.

The new turn does not call `frame`, advance the framework clock, process input,
tick layout, or submit a drawing. It also makes completed legacy `net` requests
available to their service pump. Native net completion draining preserves the
existing in-flight and per-request limits; it does not replenish a per-frame
request allowance. Promise continuations may issue another host operation, which
is dispatched before returning to the looper. Its eventual completion enters a
separate posted turn: the host never waits or loops until a chain finishes.
Foreground side effects keep their normal foreground policy, including haptics.

After boot and the initial lifecycle assignment, one service turn starts commands
already emitted by the guest before the first scheduled visual frame. The frame
schedule is not delayed. A completed asynchronous chain can therefore update
state before the next frame; whether it finishes in that interval still depends
on real I/O and scheduling. Cancellation continues to suppress Promise delivery,
and a completion from a destroyed or replaced guest cannot enter another guest,
even if its native address is reused. Android builds using the new JNI method
require a matching runtime library with `pod_runtime_pump_service_results`.

### Private file replacement cleanup

`file.write` with `atomicReplace` (the default) and secure-store snapshots allocate a unique temporary file in the target directory. Writing, flushing, descriptor sync, and stream close finish before publication. Cleanup covers failures in every phase and deletes only that call's allocated path, preserving other files. A cleanup error is attached as a suppressed exception when another error is already being reported. Errors before publication leave the existing target unchanged.

The existing fallback to a non-atomic replacement when the filesystem rejects `ATOMIC_MOVE` is preserved; that fallback does not provide an atomicity guarantee for errors during the move itself. JVM tests exercise actual partial-write, descriptor-sync, and move failures, injected flush/close failures, combined errors, and cleanup failure without recursive deletion. The production class and tests compile against Android API 36; device fault injection has not been performed.

### Battery capacity

`battery.percent()` from `@podjs/framework/services` requires `device.battery`.
Android and WearOS return the actual `BatteryManager.BATTERY_PROPERTY_CAPACITY`
integer (0..100), including zero. Missing or out-of-range system values return
`null`; an older or other host without the capability returns `unsupported`.
No synthetic capacity or additional permission is used. This is a point sample,
not an energy measurement. `ActiveBatterySession` can group actual active-playback
segments; applications own endpoint, validation, and reporting policy.
