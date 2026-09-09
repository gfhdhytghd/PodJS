# PodJS Apple companion foundation

`SyncStateServices` implements guest get/set/delete with the framework's entry
shape, preserving stored null versus absence and deleted-entry metadata. The
state-only queue/pump constructors require the mounted state capability at the
public runtime boundary. Service envelope admission is now 128 KiB to carry
valid state values; file argument limits remain 4 KiB and native state limits
remain authoritative. Swift/Linux 93 tests pass, including a 16,000-character
queued value, real journal reopen, tombstones, forged device fields and early
cancellation. State events, authenticated synchronize waiters and mixed-service
routing are not connected yet; synchronize returns unsupported, not a local
cursor presented as peer progress.

The public runtime-pump initializer now requires the mounted runtime's native
`companion.sync.file` capability. `pod_runtime_has_capability` is false before
successful validation/mounting; mounted authority is immutable until destruction.
The incoming-service initializer also rejects different app identities between
file owner and guest storage. Both initializers now throw. All 55 Rust library
tests and 92 Swift/Linux tests pass, including native authority stages, a real
ungranted runtime rejection and cross-app storage rejection. The host still owns
trusted runtime/app/root association and installation policy; this query cannot
prove that association or authorize a guest-supplied root.

Swift/Linux 92 tests now include the complete incoming guest route against real
QuickJS: file event triggers guest consent, authenticated host chunks complete
the copy, completion triggers guest save, and actual runtime `files/result`
bytes plus retained source and both success replies are checked. Frames use a
separate descriptor of the same guest IO gate. This is host-injected transport
ingress, not an OS phone/watch connection or installed capability test. Pump
pre/post reply flushes now share one 64-reply budget.

`SyncGuestRuntimePump` now borrows a real runtime and connects effect polling,
asynchronous service replies and incoming file events. It copies polled bytes,
retains a blocked request, and acknowledges replies/events only after native
enqueue. Each call is bounded to 64 items per channel. The host must use its
single runtime executor outside the guest IO gate, drive guest frames, and close
the pump before runtime destruction. FIFO backpressure can delay queued cancel
effects; lifecycle shutdown calls close directly. Swift/Linux 91 tests pass,
including actual QuickJS request emission, full native event queue retention,
drain/retry and exactly one observed reply. The C test helper boots the real
runtime, not a simulated poll/post transport. Installed watchOS host integration,
capability authorization and Apple SDK/device validation remain outstanding.

Shared runtime external `pod_runtime_post_event` admission is bounded: 1 MiB per
JSON object, 256 queued events and 4 MiB total queued bytes. Full queues return
-2 without enqueueing, allowing exact retry after guest drain. Internal input/
lifecycle producers are not covered by this external admission rule. All 54 Rust
runtime library tests and 90 Swift/Linux tests pass, including count/byte limits
and FIFO retry. Platform runtime binaries must be rebuilt to obtain this behavior.

`SyncGuestServiceQueue(incomingFiles:)` dispatches version-one service envelopes
on a serial worker, with 64 total running/queued/reply slots. Cancellation drops
reply eligibility and signals the operation token, but cancelled queued work
keeps its slot until drained. Duplicate live IDs do not replace work; reply
tokens protect ID reuse. Hosts retain a polled effect on `submit == false` and
acknowledge reply bytes only after enqueue. Results/errors are bounded and never
include underlying private error descriptions. Swift/Linux 90 tests pass,
including bounded cancel churn, reply backpressure, duplicates and ID reuse.
This queue does not call runtime poll/post, enable permissions, or connect the
watchOS host; those ownership/integration steps remain required.

Incoming file events use `setEventsActive`, `nextEvent` and `acknowledgeEvent`.
The host posts the returned JSON outside adapter locks, acknowledging only after
its guest queue accepts it. Backpressure retains exact bytes; subscription reset
invalidates outstanding tokens and restarts snapshots. Delivery scans at most 64
IDs per call, deduplicates acknowledged states and grants consent exposure only
on acknowledgement. Nil can be a quiet page, not an ended subscription.
Swift/Linux 88 tests pass, including retry, stale acknowledgement, reactivation
and progress change. The watch host event queue is not yet connected.

`SyncIncomingFileServices` now routes incoming status/accept/cancel/save argument
JSON to the leased file owner. Guest-safe snapshots omit native paths and peers,
count verified received chunks and report damaged completed copies as failed.
Mutations require prior status exposure in this adapter lifetime; guest peer
arguments and outgoing methods are rejected. Swift/Linux 87 tests pass, including
consent, progress, retained remote-cancelled completion, real saved bytes, fresh
lifetime exposure reset and corrupt-source rejection. The host must authorize
the installed app before constructing the adapter and dispatch outside the
runtime IO gate. Request/reply scheduling, host event-queue wiring, outgoing guest services and
watchOS integration are still absent; this does not advertise a new capability.

Host import and guest publication accept an optional thread-safe
`SyncCancellation`. Native scanning/copying checks between bounded blocks and
quota/recovery entries; cancellation before publication cleans private staging.
Cancellation is cooperative, not OS IO preemption. Once the destination link or
outgoing registration commits, a late cancellation does not undo the result.
Swift/Linux 86 tests and all 53 Rust runtime library tests pass, including a
deterministic mid-copy cancellation and cancellation after registration commit.
Imports recheck cancellation after owner acquisition and before retained-ID
allocation; empty-file imports with a live token also complete registration.
System picker authorization, guest service routing and Apple SDK/device testing
remain outstanding.

`incomingFiles.saveCompleteLocal(..., storage: SyncGuestFiles)` now publishes a
completed incoming copy into an approved runtime's existing `files/` tree.
Under the shared gate, descriptor-relative traversal rejects escape/symlinks,
scans the same 16 MiB persistent-files quota used by the runtime, hashes a bounded
copy and atomically links it without overwriting. Identical destinations are
idempotent; private staging is cleaned after failure or interrupted publication.
The received copy remains intact, including after remote transfer cancellation.
Swift/Linux 83 tests pass, covering these paths plus busy gates/full quotas.
The host must supply the app/root from trusted metadata and ensure every writer
uses the gate. Guest service routing, Apple SDK/device
link/filesystem behavior and the complete watch sync adapter remain unverified.

Shared Unix guest IO gating is now available through `pod_guest_io_*`, using
`<runtime data root>/sync-save/owner.lock`. Independent descriptors contend even
within one process; unsafe directories/lock hard links are rejected. watchOS
host source now holds the gate during runtime initialization/evaluation and
guest frames, deferring busy frames without consuming input. Swift/Linux 80
tests and all 52 Rust runtime tests pass, including gate interop; these do not
compile the watchOS host branch. Apple SDK linking requires a rebuilt runtime
slice. This is a prerequisite for quota-safe guest file publication, not an
implemented `syncFiles.save` endpoint; every other writer must use the same gate.

Apple file admission now intersects host-approved declared limits with target
profile limits. `SyncFileLimits` permits only ceilings at or below 16 MiB per
file, 32 MiB per app and 128 retained IDs. Schema 7 persists the effective limits;
reopen must supply the same limits, and older journals use protocol defaults.
There is no automatic existing-namespace limit migration. Damaged incoming
completion renews and persists its two-copy reservation before repair writes,
including under tight profiles. Swift/Linux 79 tests pass, covering intersection,
cross-direction admission, retained-ID limits, incompatible reopen rejection and
repair refusal without mutation. Manifest/profile authority still belongs to
the embedding host; this does not implement guest installation policy.

`client.importOutgoing(sourceURL:peer:transferID:mime:)` now coordinates source
freeze and peer registration using schema 6's durable registering intent.
Recovery reads the transfer journal: matching committed registration retains
the source; absence removes the unpublished source; unavailable or conflicting
metadata fails without guessing. Client reopen drains these interrupted imports.
Swift/Linux 77 tests pass, including failure before/after registry commit, actual
journal reopen in both states and the existing authenticated resumed sender
using the combined import API. An uncertain error can still mean the task was
committed: inspect retained history before retrying. System file authorization,
cancellation integration and Apple device verification remain unfinished.

`client.stepOutgoing(peer:)` now advances registered outgoing transfers through
offer/status/missing/chunk/finish/cancel, queueing at most one request per call.
The host uses the authenticated pump for actual writes and paces consent checks.
Pending requests retain their IDs/bytes; observations are persisted before exact
consumption, and source validation failure retains the receipt. After a crash
between consume and enqueue, an idempotent offer re-establishes peer state.
Swift/Linux 75 tests pass, including lost offer reply, real client reopen, fresh
session replay and multi-chunk completion with exact output bytes, plus cancel
before initial offer. Do not mix manual producers/consumers into its peer queue.
This is not a network scheduler, system background adapter or device acceptance.

`SyncCompanionClient.registerOutgoing(peer:transferID:)` now verifies a completed
immutable source under its shared lock before binding it in `outgoingTransfers`.
This independent 4 MiB/128-record CAS journal retains peer/manifest identity,
cancellation intent and acknowledged progress. Host-only durable queue
observations are persisted before the caller consumes their exact receipts;
chunk observations also validate bytes against the registered manifest. Complete
and cancelled remain distinct from local intent, and terminal identities cannot
be reassigned. Swift/Linux 73 tests pass, including real client reopen and failed
progress writes. Automatic request selection/consumption and import+registration
coordination are still pending; explicit source removal is not task cancellation.

`outgoingFiles.importFile(sourceURL:transferID:mime:)` now freezes a host-selected
regular file using a retained native descriptor and two bounded 64 KiB passes.
The first computes the manifest; the second rechecks each chunk while staging
under the shared admission lock. Source replacement cannot redirect that
descriptor; changed bytes/size fail, including an empty source that grew.
Schema 5 adds importing intent: interrupted/unpublished imports are removed on
recovery, while an uncertain commit that is already complete is retained. IDs
remain reserved after cleanup. Swift/Linux 71 tests and all 51 Rust runtime
library tests pass. The first C-header compile failure was fixed by including
the boolean type header. File-picker/security-scoped authorization, cancellation,
peer transfer registration and the automatic sender are not installed here;
Apple SDK/device acceptance remains open.

`SyncOutgoingFiles`, owned by `SyncCompanionClient`, now stages immutable source
artifacts through the same incoming file owner, physical receiver, journal and
exclusive lease. Both directions share 128 retained IDs and a 32 MiB admission
limit; live sources conservatively retain two-copy reservation even after
completion. Removed IDs cannot be reused in either direction. Journal schema 4
reads schemas 1–3 and persists preparing/completing/removing intents before IO.
The 68-test Swift/Linux suite covers bidirectional quota denial, ID conflicts,
completed-source immutability, real reopen/read and interrupted intent recovery.
This is still host staging from a supplied manifest/chunks, not an OS source
importer, peer-bound outgoing transfer journal or automatic file sender.

`SyncFileReceiver.readCompleteChunk` supplies host-only bounded source reads for
the upcoming immutable outgoing snapshot workflow. Native code reads and hashes
the entire artifact through one descriptor, validates the selected chunk and
returns at most 64 KiB. Unix opens reject symlinks and non-regular/multiply-linked
artifacts; the existing trusted private parent and exclusive lease remain
required. Reads never repair or delete a damaged file and are not remote wire
commands. Ten Rust file tests and 65 Swift/Linux tests pass. Rehashing the whole
file for every chunk has an IO cost not yet measured on Apple devices; this is
not yet the outgoing snapshot store, shared quota ledger or transfer driver.

`SyncFilePump` now connects the durable request queue and consent-aware incoming
receiver to an exclusively file-authorized authenticated session. It persists
operation replies before transport commit, binds replies to exact original
requests, and reuses signed frames for retries. Unknown or already-consumed
replies fail closed; hosts must retain observations until transport drains and
never reuse request IDs. Tests cover consent changes between retries, subsequent
status requests, old request rejection, identity mismatch and reply storage
failure. The standalone pump is
integrated into the mixed-channel client when file is explicitly authorized.
The client then owns all four routes and closes them together on detach/failure.
The updated suite passes 64 tests, including interleaved state/message/file
delivery and consent/chunk/finish with real output bytes. A failed file reply
commit closes all routes while retaining the pending request and usable client
storage; constructor grant failures do not acquire the session. It does not add OS
transport, outgoing snapshots, pairing, guest save or Apple SDK acceptance.

This Swift package currently exposes a **host-only authenticated session** backed
by the same Rust C ABI used by the other hosts. It is not yet the full planned
iOS companion SDK or example application. The low-level file receiver is wrapped
by consent-aware incoming services; the outgoing source/transfer workflow and
safe guest publication are still incomplete.

`SyncSession` accepts an already approved pairing key, host-selected app/device
identities, fresh OS-random challenges and manifest-authorized channels. It
serializes native calls and close, copies borrowed responses, and exposes proof,
authentication, frame send/verify and explicit receive commit. Verify does not
commit application state or advance the receive cursor. Retain exact outgoing
frames for retransmission. Authentication is not encryption: an encrypted
transport is still required. Do not expose key/configuration input to guest code
or log it. Swift-managed copies are not a secure key vault.

The embedding Apple application must supply an ABI 2 `libpodjs_runtime` slice for
its SDK/platform. The Swift package's iOS/watchOS deployment declarations do not
prove those SDKs compile or that existing watch XCFramework binaries contain the
new symbols. No runtime binary is bundled here yet.

From the repository root, `bash scripts/test-apple-companion.sh` builds the native
Rust static library and runs Swift 6 tests in a pinned Linux Swift container.
Tests exercise actual FFI calls, authentication failure, channel grants, strict
frame parsing/tampering, explicit commit/duplicate delivery and concurrent close.
They do **not** constitute Apple SDK, WatchConnectivity or device acceptance.

`SyncFileReceiver` wraps the Rust receiver's durable manifests, 64 KiB chunks,
missing-block revalidation and atomic completion. Its lifetime advisory OS lock
prevents another cooperating receiver from recovering or writing the same root.
The lock file rejects symlinks, multiple links, foreign ownership and unsafe
permissions. Supply a root under an existing OS-private trusted parent; this is
not a sandbox for arbitrary user-selected paths. Synchronous calls belong on an
IO worker. Native quotas are per receiver root; aggregate app/peer quota policy
still needs the high-level SDK coordinator.

The host must enforce peer authentication and acceptance before allocation.
`finish` returns only a host-private artifact. `removeHostCopy` explicitly deletes
even a completed artifact and must not be used as the guest cancel semantics.
Durable consent, transfer identity tombstones, safe guest save and automatic
authenticated request routing remain unfinished. File tests cover real Rust disk
IO, reopen/resume, wrong hashes, lease exclusion and safe lock-file refusal.

`FileSyncSnapshotStore` supplies a bounded 4 MiB binary snapshot/CAS primitive
for future state and queue coordinators. It distinguishes absent from empty,
holds a lifetime OS lease, writes/fsyncs a temporary file, atomically renames and
fsyncs the directory, including the parent when opening a newly created root.
Only its known safe staging file is recovered. Unsafe regular-file metadata,
symlinks and special files fail closed; a FIFO cannot block opening a snapshot.
CAS errors can represent uncertain commits: re-read and reconcile. This is not
an 8 MiB message queue or a key store. Apple
filesystem/device behavior remains to be verified.

`SyncState` now provides durable get/set/delete, LWW merge and authenticated
incoming version-one batches. The shared Rust transform preserves distinct
Unicode JSON object keys and compares finite numbers with JavaScript semantics.
The state and exact latest peer receipt are committed in one CAS before receipt
return; a failed CAS does not acknowledge delivery. JSON null is distinct from a
missing key. This is host-only: authenticate the frame, peer and state grant
before calling ingress. `prepare` durably freezes a paginated sending cycle and
returns the exact pending ID/payload again after retry or reopening. Only a
matching authenticated ACK advances its cursor; edits during the cycle remain
for the next cycle. Each batch is bounded to 512 entries and 256 KiB, and each
store remains bounded to 4 MiB including frozen cycles (large/multiple-peer
cycles can therefore fail quota without returning a sendable batch). New writes
use schema 2 and can read schema 1; old implementations cannot read schema 2.
There is not yet a subscription dispatcher or synchronize coordinator.
Twenty-two Swift/Linux tests
pass against the real native library, including persistence, replay, merge
conflicts and failed CAS; this is not Apple SDK or device acceptance.

`SyncStatePump` connects an exclusively owned state/ACK session to this journal.
It binds app/local/peer identity, strictly verifies original frame bytes, commits
application state before session delivery, and sends the common binary kind-3
ACK. Pending sends and the latest duplicate reply retain their signed frames.
Errors close the session; the host must reconnect with fresh challenges and must
write returned frames in order over a reliable encrypted transport, closing on
write failure. This synchronous dispatcher does not own network IO or OS lifecycle.

`SyncStateRun` adds exclusive ordered writes, one in-flight state batch, explicit
step/receive entry points and a monotonic 100 ms–120 s foreground deadline.
`SyncState.acknowledgement` is a read-only durable local ACK barrier; it does not
claim absence of undisclosed remote edits. `step` reports that barrier and never
treats a successful write alone as synchronization. Host callbacks must be
bounded/non-reentrant and execute on an IO worker; close and deadline enforcement
cannot interrupt a blocking synchronous write. Call step periodically and after
local edits, feed complete incoming frames, and close on background/EOF. There is
no internal timer, transport discovery, automatic reconnect or OS lifecycle hook.
The full Linux Swift/native suite now passes 29 tests, including bidirectional
convergence, barrier invalidation, write failure and monotonic deadline.

State subscriptions return raw committed snapshot bytes after successful CAS,
outside the state lock. Sender-only journal updates and duplicate/losing incoming
revisions do not emit changes. Callbacks are synchronous and may read or remove
their subscription; keep them brief and enqueue run wakeups rather than calling
the run inline during receive. A callback already captured by a concurrent write
may still run after unsubscribe, and concurrent/reentrant writes do not promise
callback ordering. Subscribers should use the supplied snapshot as an event or
re-read state for a current view. The suite with these subscription tests passes
30 tests; OS/UI dispatch remains the embedding application's responsibility.

`SyncMessageEnvelope` and `SyncMessageAcknowledgement` implement the existing
Android/Harmony binary payload and kind-1/kind-2 ACK formats, with opaque bytes,
256 KiB payload limits and safe-integer expiry. They are codecs, not a durable
message service or permission/TTL enforcement. The journal can explicitly use a
separate message namespace with `maximumBytes: 18 * 1024 * 1024` to accommodate an
encoded 8 MiB queue and metadata; state defaults remain 4 MiB. A smaller-bound
reopen rejects reading/overwriting larger data and preserves it. All 34 Linux
Swift/native tests and C11 warning-as-error checks pass.

`SyncMessageOutbox` now supplies the durable TTL sender: first expiry and retry
identity commit with the queue entry, matching authenticated ACK removes only
that entry, and a retried service request cannot recreate it before expiry.
It validates content/envelope digests with the shared native SHA-256 helper,
preserves FIFO within priority, caps live messages at 1000 and 8 MiB including
1 KiB per row, and retains up to 10000 expiry-bounded retry identities. Queue
reads do not mark delivery. Store errors may be uncertain commits: retry with
the same ID/content/TTL and reconcile; do not silently generate a new ID.
The initial outbox suite passes 37 Swift/native tests including real file reopen
and ACK write failure. A receiver journal/effect handoff and network message
routing are still missing; this is not a full message service.

`SyncMessageInbox` now durably retains pending effects and applied receipts in a
separate namespace. Exact live replay preserves status; changed peer/ID content
is rejected. Expired messages allocate no effect. `markApplied` requires the
original digest token and persists completion before returning. A host business
effect must itself be idempotent by authenticated peer/message ID: a crash after
the effect but before this receipt can replay the work. Pending recovery supports
priority/FIFO pagination, and applied receipts remain until expiry. The inbox
has the same 1000-record/8 MiB cost bound and uses up to 18 MiB encoded snapshots.
The full Swift/native suite passes 41 tests, including actual pending/applied
file reopen and receipt storage failures. No guest effect adapter or network
message pump is connected to OS IO yet.

`SyncMessagePump` connects an exclusively owned message/ACK session to the inbox
and outbox. Original frames are authenticated before decoding. Durable pending
delivery advances only the transport sequence and returns no application ACK;
explicit `acknowledge` persists applied status after host work, then signs the
ACK. Already applied/expired replay can reply immediately. Current outgoing
messages and the latest matching ACK reuse exact signed frame bytes, and any
error closes the session. The host must own ordered reliable encrypted writes
and reconnect on write failure; no guest effect callbacks or OS discovery are
installed. The full Swift/native suite passes 44 tests, including real authenticated
deferred delivery, lost ACK replay, expired no-effect handling and applied-store
failure without ACK.

`SyncCompanionPump` privately composes state and message dispatch on one session
with exactly state/message/ACK grants. Original authenticated ACK kind selects
the journal; all child operations are serialized and any route failure closes
the entire connection. Separate public pumps still require their narrow grants.
The host must queue subscriber wakeups (not synchronously reenter the pump) and
write frames in call order. The suite now passes 46 tests, including simultaneous
bidirectional state/message frames and ACK-kind isolation on the shared sequence.
File-channel dispatch and OS connection scheduling remain unimplemented.

`SyncCompanionClient` owns a per-installed-app directory, persistent app/device
identity, an exclusive parent lease and separate state/outbox/inbox stores. It
creates no connection and exposes at most one attached mixed pump; detach closes
the session, and close releases child stores before the parent lease. A foreign
device identity cannot repurpose the same app directory. Provide an existing
trusted private parent and host-installed identities. Host IO remains separately
owned and must be stopped on lifecycle transitions. The full suite passes 48
tests, including real app isolation, all-journal reopen, duplicate owners and
borrowed-pump invalidation. Secure pairing, consent-aware files, OS transports,
the iOS example and actual Apple SDK/device verification remain required.

`SyncIncomingFiles` now owns a consent journal and native receiver under one
lifetime lease. Offers reserve a globally unique incoming ID across peers but
allocate no transfer bytes; only `acceptLocal` persists acceptance before reserve.
Accepting/completing/cancelling intents recover idempotently. Chunk/missing/finish
require consent, and completion validates native whole-file SHA before returning
a private artifact. Guest-style cancel preserves completed files, while explicit
`removeLocal` can delete them. It reserves assembly space under the 32 MiB native
bound and retains at most 128 identities. `listLocal` is journal history, not a
fresh integrity check; finish/missing revalidate native bytes. This is not yet
bound to the client, wire routing, outgoing identity registry or safe guest save.
The suite passes 50 Swift/native tests and 7 Rust file tests. Interrupted intent
tests use real journal reopen; they are not device power-loss acceptance.

The client now owns `incomingFiles` as a separate leased child and closes it
before releasing the parent. File offers recover with the same app/device
journals. The full suite passes 51 Swift/native tests and all 46 Rust runtime
library tests, including assembly-space quota rejection without consent and
explicit local cleanup after whole-file hash failure. A failed whole-file hash
can leave a completing intent: host removal is available, while ordinary cancel
conservatively refuses to destroy a possibly completed artifact. File wire
routing, outgoing snapshots/identity coordination and guest save remain open.

File request/reply codecs now use the shared Rust validator through additive C
symbols. Unknown and duplicate fields (including nested manifest/reply fields),
noncanonical Base64, out-of-range chunks and method-incompatible reply phases
are rejected. `SyncFileRequest` retains exact original bytes; reply SHA-256 binds
those bytes rather than canonical JSON. No remote accept/path/save method exists.
The full suite passes 54 Swift/native tests. This validates wire payloads only;
peer/request-ID binding, durable request replay and file-channel dispatch are
still required. In particular the wire cancel reply requires cancelled, whereas
guest-style local cancellation protects completed artifacts; the network adapter
must define this distinction explicitly instead of fabricating a success reply.

`SyncFileRequests`, owned by the client in its own 18 MiB namespace, now persists
exact outgoing request bytes, digest, host request ID and reply. It permits one
pending request per peer, caps records at 128 with an 8 MiB cost bound, and never
evicts unconsumed observations. Reusing a retained ID with identical peer/bytes
reconciles uncertain enqueue commits. Completed observations are consumed only
by exact request/reply identity; host IDs must not be reused after consumption.
Unknown replies are currently rejected, and changed duplicate replies cannot
replace the first observation. This queue is not a transfer identity registry
or a receiver replay journal. Tests include client reopen with exact pending
and completed bytes. Authenticated dispatch is provided by `SyncFilePump`;
actual OS network IO is still supplied by the embedding host.

Incoming file wire execution now maps the validated request set to consent-gated
operations and returns validated replies without paths. Remote cancel persists a
separate `remoteCancelled` flag: wire state is terminal cancelled, while an already
completed local copy remains complete/retained in local history. Reopen preserves
that distinction and refuses later remote writes. Local explicit removal remains
separate. Incoming journal writes use schema 3 (schemas 1/2 are readable; old
implementations cannot read new writes). Whole-file completion still verifies
bytes; status checks missing blocks before reporting complete. Durable request-ID
replay and authenticated file dispatch are connected to the client.

Native file repair now rechecks the app's two-copy assembly reservation before
writing chunks or rebuilding a damaged completed file. Over-quota repair fails
before mutation; after admission, invalid private complete/staging copies are
removed to avoid keeping a third assembly beside chunks and output. A real
16 MiB/8 MiB corruption fixture covers this transition. Eight Rust file tests
and the 58-test Swift/native suite pass. These checks reverify other completed
files and add IO work; device latency/power acceptance remains unverified.

Incoming file journal schema 3 now retains the latest request ID, exact payload
and exact reply per peer. A pending intent is saved before executing operations;
its bounded future reply space is reserved in the 4 MiB journal. Completed replay
returns the original reply even after consent/status changes. Pending effects can
retry the same ID, but block a different ID; unknown older transport duplicates
do not execute. This assumes sequential request/response and non-reused host IDs,
not an unbounded historical replay archive. The transaction lock includes all
file effects and receipt writes. Schema 1/2 can be read, old implementations cannot
read schema 3. Tests include actual reply reopen and pending-before-consent
recovery. `SyncFilePump` supplies verified transport duplicate classification.

Still required: OS transport coordination, outgoing snapshots and transfer state,
cross-direction IDs/aggregate quotas, safe guest file publication, secure pairing and identity,
BLE/IP and WatchConnectivity adapters, foreground/background lifecycle,
notification integration, the iOS example, Apple SDK builds and physical devices.
