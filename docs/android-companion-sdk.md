# Android companion SDK core

Host message service calls can now use `queueMessageWithTtl`: a bounded SQLite
retry-intent ledger commits with the first outbox row and retains the original
expiry, TTL, payload digest and priority until expiry, including after ACK and
process restart. Matching retries do not extend expiry or resurrect an ACKed
message. Changed retries and attempts to adopt an absolute-expiry request fail.
The separate retry ledger holds at most 10,000 unexpired intents; overflow rejects
new TTL requests without evicting existing intents or pending messages. Reuse a
message ID only for the same logical request. A `queued` service response means
the original request was accepted, not proof that it is still pending or delivered.
The outbox/state-service instrumentation set passed 9 tests on OWW242, including
injected intent-insert failure rolling back the first queue row.

The `:companion` Android library exposes `dev.podjs.companion.PodCompanion` for one
logical application and local device. It runs the shared runtime's authenticated state,
message and file implementations, without constructing a UI
runtime. It creates no worker, timer, discovery service or permanent connection.

Current baseline is Android 11 / API 30 and ARMv7/ARM64, matching `:runtime`.
The module currently uses `api(project(":runtime"))`: its small AAR is not a
self-contained SDK distribution. Consumers need the runtime and its transitive
dependencies/native libraries. Runtime media/WorkManager manifest dependencies
are still inherited; extracting a smaller sync-only runtime is pending. No Maven
publication or compatibility guarantee is implied yet.

`PodCompanion` now extends the shared `dev.podjs.runtime.PodSyncClient`; the
bounded `PodForegroundSync` facade similarly uses `PodSyncForeground`. The watch
host can use those owners without depending on the phone SDK. Existing Java
source, including session/listener type references, remains compilable; rebuild
consumers with the updated runtime because inherited nested types moved packages.
This does not claim that the guest service bridge is already connected.

Host service adapters can use `setStateEntry`/`deleteStateEntry` for the exact
entry returned by a durable mutation, and `queueMessage(peer, messageId, ...)`
to preserve a caller-owned retry identity. Do not generate a new ID or change
expiry/content for an uncertain retry. Existing `setState`/`deleteState` and
random-ID `sendMessage` phone methods remain available. The shared-owner and
phone-facade regression class passed all 13 tests on the connected OWW242; its
in-process/loopback peers are not a two-physical-device wireless acceptance run.

`currentStateAcknowledged(peer)` is a read-only checkpoint for a
synchronization completion barrier: it compares the current entries with the
durably acknowledged complete cycle and rejects pending/partial cycles. Reading
it does not prepare work or advance cursors. State subscribers now also wake on
authenticated state ACKs. A true value only covers the current local snapshot;
it cannot prove that an offline peer has no additional changes, and is not by
itself a completed bidirectional `sync.state.synchronize` implementation.

`PodSyncForeground.synchronizeState(timeoutMillis, cancellation)` now provides
the blocking host-IO barrier over its existing authenticated session: it requests
state and waits for that checkpoint, with a 1–120000 ms budget, stopped-session
failure and cancellation checks at most 100 ms apart while waiting. Cancellation
or timeout does not close the shared foreground run. The returned `appliedCursor`
is the locally persisted incoming peer cursor, not a guarantee that the remote
device has no unseen changes. Do not call it on the UI thread. Guest service
routing can now borrow a host-selected foreground run through the internal
adapter: it checks client ownership and exact peer identity and applies a
30-second service wait budget. Real host connection selection/UI remains to be
integrated; the installed owner still has no live foreground run by default.

`releaseSource(id)` rejects active peer references, but also permits cleanup of a
never-offered snapshot. Repeating a completed release is idempotent; it does not
remove remote files or the original source document. This lets hosts clean up a
snapshot when an offer fails after source preparation.

Build and test from `platforms/android`:

```sh
./gradlew :companion:assembleDebug :companion:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r companion/build/outputs/apk/androidTest/debug/companion-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -e class dev.podjs.companion.PodCompanionTest dev.podjs.companion.test/androidx.test.runner.AndroidJUnitRunner
```

## BLE packet stream adapter (not a GATT connection)

`PodBleStream` adapts characteristic values to the existing `PodSyncStream`;
pass `framed()` to `PodCompanion.open` on IO workers. Construct it only after
negotiation and subscription complete, using the actual negotiated ATT MTU
(23–517), not the requested value. Attribute values are capped at
`min(MTU - 3, 512)` bytes. Each contains magic `0x50`, version `1`, a four-byte
big-endian unsigned sequence and nonempty stream bytes. Sequences start at zero
per direction/connection and cannot wrap. The six-byte header leaves 14 stream
bytes per packet at MTU 23; the original four-byte frame prefix is unchanged.

The supplied sender must serialize and wait for each GATT write/indication
completion. Its close hook must unblock an outstanding send. Receive callbacks
copy data into a bounded ring without waiting for capacity. Gaps, altered or old
duplicates, malformed packets and overflow close the link and discard buffered
bytes; only an identical immediately preceding packet is ignored. The default
receive budget is 2 MiB + 4 bytes, configurable up to 4 MiB + 8. This is fail-closed
buffering, not a credit-based flow-control protocol. Business ACK and authentication
remain in the existing session protocol; fragmentation adds no encryption.

OWW242 instrumentation passed five adapter tests and two existing stream tests,
covering MTU 23/64/247/517, ring wrap, the maximum 2 MiB frame, copy isolation,
duplicates, malformed packets, overflow and close interrupting blocked IO.
Run `dev.podjs.runtime.PodBleStreamTest` in the runtime test APK.
The SDK test `bleFragmentsCarryAuthenticatedStateExplicitAckAndFile` additionally
wires two adapters in memory at MTU 23 and 247, performs the real authenticated
handshake, state exchange, explicit message ACK and a byte-equal 65,539-byte file
transfer. SDK/LAN regression: 16 tests passed on OWW242 (22.341 seconds). This
in-memory packet delivery has neither radio latency nor GATT callback behavior.
The adapter alone does not provide GATT setup, discovery, permissions or callbacks.
The central implementation below now supplies one side of that connection;
wireless throughput, reconnect/resume and two-device acceptance remain unverified.
Do not advertise working end-to-end BLE connectivity yet.

### Single-use Android GATT central

`PodBleDiscovery(context).scan(durationMillis)` provides explicit service-UUID
discovery before selecting a peripheral. Call on an IO worker for 100–10,000 ms;
it returns an immutable snapshot of at most 32 `Entry` values containing a
BluetoothDevice and RSSI. Repeated addresses update RSSI without changing order;
new addresses beyond the cap are ignored. Android scan filters and received
advertisement records must both contain the PodJS service UUID. Device names are
not fetched. Results are untrusted routing hints, not verified application peers.

Every completed scan stops its callback; cancel on foreground exit. Cancellation,
missing scanner and platform/permission errors throw, while a completed empty
scan is a legitimate empty result. Closing discards retained results and ignores
late callbacks, without modifying an already-returned snapshot. The instance is
single-use, does not auto-connect/retry or grant pairing, and creates no permanent
worker. Host OS scan/location permissions and permission UI remain the host's
responsibility. The deadline does not force interruption of a blocked Binder call.

OWW242 discovery/controller/stream tests: 22 passed (2.818 seconds). Four new
discovery tests use a fake scanner for deduplication/cap, immutable snapshots,
late results, empty completion, cancellation, scan failure and permission errors.
No over-air scan or remote discovery was claimed by those tests.

`PodBleGattClient(context, selectedBluetoothDevice, timeoutMillis).connect()`
connects with `autoConnect=false` and LE transport, discovers and validates the
PodJS service, requests MTU 517 but uses only the successful actual MTU callback,
then enables indications through CCC. It returns `PodBleStream`; authenticate
`framed()` with the SDK before processing application data. The host must choose
the device and obtain platform Bluetooth permissions; this class neither scans
nor prompts, pairs, advertises or reconnects. No library-wide Bluetooth permission
was added to unrelated applications. A consuming Android host must declare/grant
the permissions required by its OS/target before calling it.

Service UUID: `deef0001-654d-4e33-9a27-1341d8c28fd1`; central-to-server characteristic
`deef0002-654d-4e33-9a27-1341d8c28fd1` requires WRITE; server-to-central characteristic
`deef0003-654d-4e33-9a27-1341d8c28fd1` requires INDICATE and standard CCC `0x2902`.
This is a new internal wire service, not an interoperable published profile.
Writes use WRITE_TYPE_DEFAULT and await each matching completion callback. API 33+
uses value-taking write/callback APIs; API 30–32 uses defensive callback copies
and serialized legacy writes. Missing properties/descriptors, changed MTU after
setup, unexpected callbacks, failed procedures and disconnection fail closed.

The 100–30,000 ms timeout covers callback waits across all setup stages using one
absolute deadline; each subsequent packet write has its own timeout. This is not
a hard interrupt of a blocked Android Binder call, an authentication deadline or
a whole-transfer time limit. Close on foreground exit, including during setup;
closing either the client or returned stream closes the GATT connection and wakes
waiting reads/writes. The client is single-use and never reconnects automatically.

OWW242 `PodBleGattClientTest` plus adapter/stream regressions: 12 tests passed
(1.294 seconds). Five central tests use an injected asynchronous-backend contract
to verify procedure order, actual MTU 23 despite requesting 517, frame delivery,
error/status/MTU rejection, deadlines, cancellation, disconnection and permission
exceptions. They do not execute the Android Bluetooth radio or prove peripheral
compatibility. Discovery/permission UI and authenticated two-device wireless tests
are still pending.

### Single-use Android GATT peripheral

`PodBleGattServer(context, selectedCentral, timeoutMillis).accept()` registers the
same service and starts connectable advertising of its UUID. It accepts only the
host-selected BluetoothDevice, rejects other connections and stops advertising
after a valid indication subscription. Radio identity is only routing, never a
substitute for the SDK's application/peer authentication. The host must already
have selected the central and obtained the platform connect/advertise permissions.

For central addresses that cannot be obtained because of platform privacy,
`PodBleGattServer(context, timeoutMillis)` instead binds the first successfully
connected radio. The selection is immutable for this instance and later radios
are rejected. Use `PodBleAttempt.accept(context)` to immediately authenticate the
fixed, previously approved application peer from the attempt constructor. This
does not import a key, approve the first radio, accept another application peer or
rearm on failure. A wrong candidate can occupy/fail this bounded attempt; retry
must be explicit. Hosts with a known stable address can retain the strict overload.
OWW242 routing/controller/stream regression passed 23 tests (2.857 seconds),
including first-radio immutability and retained strict selection. SDK/LAN passed
21 tests (24.597 seconds), including rejection of mismatched pairing keys on a
candidate stream. These use radio-selection and in-memory transport tests; the
new first-radio mode has not completed a live GATT peer/CCC/authentication run.

The server uses the public two-argument `openGattServer`; transport-specific
overloads in Android sources are hidden APIs and are not used. Writes require the
expected characteristic/CCC, offset zero, no prepared writes and an ATT response.
Only indication enable is accepted for CCC; unsubscribe, duplicate subscription,
MTU changes after subscription and failed/disconnected selected peers close this
single-use connection. Receive data is validated/buffered before write response.
Each outgoing indication waits for `onNotificationSent` before the next packet.
The default MTU is 23 until an actual MTU callback arrives. No application payload,
device name or pairing key is included in advertising.

The accept callback-wait deadline is 100–30,000 ms; advertising also has a platform
30-second cap. Per-packet indication waits have the selected timeout. Closing the
server/stream stops its advertising, closes its GATT server and wakes waiters;
there is no automatic reconnect, background service or permission request. These
deadlines do not forcibly interrupt Android Binder calls or bound authentication.

OWW242 combined server/client/adapter/stream regression: 18 tests passed (2.233
seconds). Six server-controller tests use injected backends for subscription MTU,
confirmed indications, malformed/unsolicited callbacks, deadlines, cancellation,
permission loss and rejection. One wires both controllers and exchanges 65,539
bytes each way at MTU 23. This verifies controller and framing behavior, **not**
actual Android service registration, advertising, GATT callbacks or wireless
compatibility. Radio tests and UI integration remain required; the SDK attempt
below supplies the authentication lifecycle.

#### OWW242 native registration/advertising probe

`PodBleRadioTest` is opt-in because it briefly advertises the PodJS service UUID:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -e class dev.podjs.runtime.PodBleRadioTest -e podjsBleRadio true dev.podjs.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

It requires Bluetooth already enabled and an advertiser; explicit runs fail rather
than silently skip unavailable hardware. It never switches the adapter, pairs,
initiates a connection or imports a key. The test-only manifest declares Bluetooth
permissions; on API 31+ it temporarily adopts only shell connect/advertise identity
and drops it in finally. Production host permission UI is not bypassed by this.
The selected central is a synthetic locally administered test address.

OWW242 native probe plus controller/stream regression: 19 tests passed (6.592
seconds). The probe observed Android advertising success, the native service and
registered test server, then a four-second accept timeout and native server
unregistration. Bluetooth remained enabled. The implementation now explicitly
calls `clearServices()` before `close()` in addition to stopping its advertisement.

This device retains recycled GATT handle-map entries and stale `started` flags;
requiring an empty map or counting those flags gave misleading failures. The final
probe checks the native server's Registered status, not diagnostic-history removal.
Old entries from exploratory runs remain; no adapter reset or other application's
service cleanup was performed. Logs also recorded stop-advertising, clear-services
and unregister calls. These checks do **not** establish over-air visibility/removal,
client discovery, a remote CCC/MTU exchange or authenticated wireless transfer.

#### Two-radio framing probe — currently not passed

`PodBleWirelessTest` and `scripts/test-ble-wireless-central.py` provide an explicit
Android peripheral/Linux central probe: select the host adapter address with
`-e podjsBlePeer ADDRESS`, then run the Python helper with
`uv run --with bleak==3.0.2 scripts/test-ble-wireless-central.py`. It discovers only
the PodJS UUID and requires exactly one result, does not pair or send application
credentials, and attempts 4,099 deterministic bytes each way using the Android
callback's negotiated MTU. Test-wide deadlines bound the operation. The helper
uses the documented [Bleak BlueZ APIs](https://bleak.readthedocs.io/en/latest/backends/linux.html).

The first live attempt on this checkout **failed**: the host's BlueZ 5.87
`bluetoothd` dumped core during discovery (2026-09-08 16:48:22 host journal), systemd
automatically restarted it, and Bleak stop-scan reported `No discovery started`.
The watch's accept then timed out (30.227 seconds), before any CCC/data verification.
No MTU or successful wireless transfer result was obtained. The cause of the daemon
crash has not been established; it must not be attributed to a specific Bleak or
PodJS defect from this observation alone.

Wireless retries on this host were stopped to avoid further disruption. Do not
rerun there until the host Bluetooth failure has been investigated and retry is
authorized. No system packages, Bluetooth settings or service configuration were
changed; no manual service restart was performed. Afterwards the host daemon was
running, adapter powered and not discovering; the test server was unregistered
on the watch and its Bluetooth remained enabled. This is not a two-device pass.
Radio-free helper decoder tests passed (4); controller/discovery/stream regression
also remains separately runnable without the opt-in wireless fixture.

A later read-only diagnostic mapped the recorded offsets using the installed
`bluez 5.87-2` binary (build ID `c763363fd1488407ff68170d15ffc317dfa5a952`):
`0xa9e78` is `device_found_callback + 168`, with the preceding call mapped to
`src/adapter.c:7602` (`btd_adapter_device_found`). The next recorded frames map to
`can_read_data` and `watch_callback`. This narrows the visible stack to device-found
dispatch, before this probe's connect call; it does not identify the failing
object, advertisement or root cause. The core file was inaccessible and was not
read or accessed with elevated privileges. No daemon restart/scan was performed.

The Python probe now watches the read-only D-Bus owner of `org.bluez` every 250 ms
and cancels its operation if that owner changes/disappears or cannot be queried.
It never restarts BlueZ or retries the probe. Cancellation is cooperative with
backend cleanup, not crash prevention. Seven radio-free tests pass (0.008 seconds),
covering framing and daemon-change/query-failure cancellation. This guard has not
been used for a new live wireless run; the earlier no-retry restriction remains.

### Authenticated BLE attempt

`PodBleAttempt(sdk, approvedPeerId, channels, timeoutMillis)` is single-use.
On a host IO worker call `connect(context, selectedPeripheral)` or
`accept(context, selectedCentral)`. The peer must already be approved in the SDK;
BluetoothDevice selection never imports or approves an application pairing key.
Alternatively `accept(context)` uses first-radio routing with the same fixed peer
authentication and deadline. Only the radio filter changes, not the application
identity, credentials, channel grants or session verification.
The returned value is an authenticated `PodCompanion.Session`, suitable for the
same explicit foreground driver as LAN. No channel grant is added implicitly.

One 100–30,000 ms deadline starts before GATT setup and covers the HMAC handshake.
A deadline worker closes the pending controller to wake transport/handshake IO;
the final handoff also checks monotonic elapsed time. Cancellation rejects and
closes even a late returned stream. Successful handoff cancels the deadline and
transfers ownership to the Session, so closing the attempt afterwards does not
disconnect that Session. Close the Session/foreground driver on foreground exit.
The deadline cannot forcibly terminate a stuck platform Binder implementation.
This does not add discovery, permission prompting, initial pairing or retries.

OWW242 `PodBleAttemptTest`, `PodCompanionTest` and `PodLanAttemptTest`: 20 tests
passed (23.783 seconds). Four new attempt tests use injected BLE packet streams
for authenticated MTU-23 handoff that survives attempt close, a silent handshake
deadline, cancellation of an opening transport with a late stream, and unknown
peer rejection. They prove SDK lifecycle integration, not a GATT radio exchange.

## Single RFCOMM connection attempt

`PodRfcommAttempt(sdk, approvedPeerId, channels, timeoutMillis)` exposes
`connect(bondedBluetoothDevice)` and `accept(bluetoothAdapter)` on IO workers.
It uses the secure public RFCOMM socket APIs and service UUID
`deef0004-654d-4e33-9a27-1341d8c28fd1` (`PodJS Sync`), then performs the same SDK
HMAC handshake over `PodSyncStream`. Android system bonding and application
pairing are separate prerequisites; a system bond never imports an app key or
authorizes another app peer. No insecure socket fallback is used.

The connector checks that its selected device is already bonded, including after
connection. The listener snapshots existing bonded addresses before registration
and rejects an accepted device absent from that snapshot or no longer bonded.
The SDK never invokes `createBond`, changes adapter power/discoverability or
cancels other scans. Hosts obtain required Bluetooth permissions and arrange
system bonding explicitly; the Android stack may still display its own security
UI for incoming attempts, which is not controlled by this SDK.

One 100–30,000 ms deadline covers socket setup/accept and application authentication.
Cancellation closes owned sockets/listeners and rejects late returned streams.
Successful Session handoff cancels the attempt timer; closing the attempt then
does not close the Session. Close the Session/foreground driver on foreground
exit. No automatic reconnect, transport upgrade, discovery or background grant
is implied. A stuck platform Binder call is not forcibly interruptible.

The new instrumentation uses injected loopback streams to test authentication
handoff, session ownership, silent-handshake timeout and cancelled late streams.
It does not prove native SDP registration, actual system-bond enforcement or an
RFCOMM radio exchange. Sample UI and two-device RFCOMM acceptance remain pending.
OWW242 RFCOMM/BLE attempt, SDK and LAN regression: 24 tests passed (26.372 seconds).

## Single LAN connection attempt

`PodLanAttempt` connects or listens once for an already approved peer. Resolve an
`InetSocketAddress` on an IO worker, then call `connect(address)`, or `listen(local)`
followed by `accept()`. Listening on port zero returns the selected port. The
100–30,000 ms absolute deadline covers connection, acceptance and authentication;
it starts at `listen`, not at the later `accept`. Closing the attempt cancels
pending network IO. Successful authentication transfers Session ownership to the
caller, so closing the attempt does not close that Session. Close the Session or
its foreground driver when the host leaves foreground.

This is neither discovery nor initial pairing, automatic reconnect, TLS encryption,
or permission to run in the background. It uses the existing authenticated wire
protocol and requires previously approved pairing material. Four instrumentation
tests passed on OWW242: authenticated handoff and state exchange, cancellation
with port reuse, stalled handshake deadline, and connect-handshake cancellation.
These are device-local loopback tests, not wireless two-device acceptance. Run
`PodLanAttemptTest` using the same instrumentation command above.

## Host-driven lifecycle

Construct with an application-lifetime context, app ID and stable local device ID.
Import pairing material through `authorizeAfterUserApproval` only after an actual
local approval flow. This method imports an out-of-band key; it does not implement
initial pairing. The SDK clears its temporary copy, not the caller's key buffer.

`open(peer, connectedStream, initiator, channels)` performs the authenticated
handshake on the caller's IO worker and takes ownership of the stream, including
failure paths. The trusted host supplies the app-authorized channel subset.
Use separate sender/receiver workers for full-size transfers so socket backpressure
does not deadlock a single-threaded send-before-read sequence.

The returned Session provides `sendState`, `sendMessage`, `sendFile(pollConsent)`
and `receive(now, messageHandler)`. Receive returns a channel-qualified outcome;
query persistent SDK state to refresh UI. The message handler must persist
idempotent business work before returning;
returning successfully allows the durable inbox and network acknowledgement to
advance. Throwing retains the message and closes the failed connection.

For application-controlled completion, use `Session.receiveDeferred(now)` instead.
It persists incoming messages and advances only the transport sequence, withholding
the business ACK. State and file channels continue normally while a message awaits
application work. `receivedMessages(now)` returns up to 100 live pending deliveries;
finish/ack those and query again for further batches.

`subscribeMessages(executor, onPending)` supplies a coalesced wake-up notification
for inbox and outbox changes (including enqueue, local completion and received ACK),
including an initial notification for persisted deliveries. Query `receivedMessages`
and `pendingMessages(peer, now)` from that callback. The SDK caps subscriptions at 32 and queues at most one pending
wake-up per subscription. Closing a subscription disables queued callbacks; executor
rejection or observer exceptions do not acknowledge or remove messages. Notifications
are best effort, while the pending inbox is durable. The shared cap is 32 across
all subscription types; callbacks already executing are not forcibly interrupted.

`subscribeState` and `subscribeFiles` use the same executor/coalescing model. Local
state writes/deletes and remote applied state trigger state refreshes. File intents,
received file requests/replies and outgoing state-machine advancement trigger file
refreshes. Read `stateSnapshot`, `incomingFiles` and `outgoingFiles(peer)` rather than
treating a callback as a one-to-one event delta. `incomingFiles` includes accepted
and terminal transfers so reopening a UI does not lose them when the consent list
becomes empty. Its manifests are detached snapshots and queries grant no consent.

Receive failures also invalidate the views: an error may occur after persistence
but before the network reply. A refresh hint therefore never claims that delivery
or an ACK succeeded. No cross-process watcher is installed; a new SDK subscription
initially refreshes persisted state, and the active host drives subsequent work.

After idempotent business work, `Session.ackMessage(delivery, now)` persists an applied
receipt before sending the authenticated ACK. The received payload/expiry/priority
digest is checked against the current inbox, preventing a stale or mutated delivery
snapshot from acknowledging different content. The session must belong to the same
peer. `PodCompanion.ackMessage(delivery, now)` can mark a receipt offline; this does
not pretend an ACK reached the peer, which gets it when retrying the message. Expired
deliveries cannot be newly acknowledged through this API.

State reads/writes and message enqueues work offline. File workflow uses
`snapshotFile` then `offerFile`, while the receiver uses `pendingFileConsent` and
locally approved `acceptFile`. Source snapshots, incoming status, completed files,
cancellation and source release are available without exposing remote host paths.
`sendFile(false)` progresses confirmed work but does not poll for approval;
the host explicitly selects when `true` is appropriate.

`disconnect` and `revoke` close live peer access without deleting pending queues.
Closing the whole SDK first disconnects sockets (unblocking receive/handshake),
then waits for active store operations before closing the stores and reader leases.
Whole-client close inside its own message callback is rejected to avoid a lock
upgrade deadlock; close a Session there or close the client after receive returns.
SDK close is idempotent and subsequent data operations fail.

## Bounded foreground driver

`PodForegroundSync` optionally owns an already authenticated Session for an explicit
100..120,000 ms run. The host must also close it when leaving the foreground. This
is not an Android foreground service, an OS background-work grant, or a discovery
API. It does not reconnect automatically or repeatedly renew its own lifetime.

The driver starts one receive worker, one send worker and a single deadline timer.
Initial work resumes state, messages and files. After enqueuing new data, call
`requestState`, `requestMessages` or `requestFiles(pollConsent)`. Requests coalesce,
the writer rotates across channels and explicit ACK work, and each data channel
has at most one outstanding request. ACK arrival drains the next persisted batch;
file approval status is queried at most once per second during this bounded run
(or when explicitly requested), so a later peer approval resumes transfer without
a second UI action. No connection is created or lifetime extended by this timer.
A flight marker is
set before sending so a fast ACK cannot be overwritten by a late send return.

Messages always use deferred delivery. The application reads its durable pending
inbox, performs idempotent business work and calls the driver's `ackMessage`.
The explicit ACK queue is bounded at 32 and duplicate message IDs coalesce. No
observer or receive callback return silently completes a business message.

Close, failure or deadline stops the session and workers and submits one terminal
notification (`closed`, `failed`, or `deadline`) on the supplied executor. The SDK
client and persistent queues remain available offline. Session close also releases
outgoing snapshot reader leases without discarding progress. A new authenticated
session/driver is required to resume after stopping.

## Evidence and remaining integration

OWW242 instrumentation passes nine SDK tests: bidirectional state, message apply
and ACK, complete 64 KiB+tail file transfer with local approval using only the
facade, close while receive is blocked, callback-close rejection, and offline
state/message reopen, explicit ACK with peer/content binding, other channels while
unacknowledged, coalesced unsubscribe and pending delivery recovery, scoped
state/file refreshes, accepted-file inventory after reopen and pending-inbox refresh
after a failed business callback, automatic three-channel progression with 100
coalesced requests, explicit message ACK, deadline shutdown/worker termination and
retained unacknowledged outbox. The endpoints are in one device's authenticated loopback
test; this is not a phone/watch wireless acceptance result.

Remaining work includes the minimal companion UI, initial pairing/discovery,
bounded OS-owned connection scheduling,
profile-specific quota tightening, sync-only packaging, guest service routing,
and equivalent iOS/HarmonyOS SDK surfaces. Target sync capabilities remain gated.
