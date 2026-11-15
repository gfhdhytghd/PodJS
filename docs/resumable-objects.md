# Application-private resumable objects, native contract 1

`VerifiedChunkStore` and `ResumableObject` require `data.files.resumable`.
The runtime must answer an actual `file.resumable.status(operation=probe)` with
version 1, all five methods, durable/fencing guarantees, monotonic elapsed time,
and positive bounded limits. Missing or older runtimes fail closed. These methods
are independent of public companion `sync.files` reception and its consent.

The caller authenticates and authorizes its application scope before acquiring a
lease. Native storage additionally binds package name and the installed sandbox.
A lease contains `scope` (64 lowercase hex), locally generated `connectionId`
(32 lowercase hex), and a persistent positive `epoch`. A new connection, process,
or expired 30-second monotonic lease increments the epoch. Every operation checks
the actual connection and epoch under the same OS lock as its side effect and
SQLite FULL commit. The application must also hold its business/account writer
boundary through its final SQL transaction. Native publication is never APPLIED.

All object methods receive `object={id,size,sha256,chunkHashes}`. Object IDs and
hashes have 64 lowercase hex characters. Chunks are 65536 bytes, with a shorter
last chunk. Objects are at most 16 MiB. Descriptors and request bindings are
immutable. Native failures return `{error,detail}`; `VerifiedChunkError.code`
preserves the code. `detail` contains a local field name, never wire content.

| Method | Additional arguments and result |
| --- | --- |
| `file.resumable.status` | `operation=probe`; or `acquire` with scope, connectionId, readOnly, maxInFlightObjects, maxStagingBytes, returning epoch; or `check`, `readOnly`, `object` with lease; or `collect` with scope and explicit discardPublication |
| `file.resumable.stage` | lease, object, index, canonical dataBase64, **bindingId**, requestDigest; returns current PENDING/PARTIAL/STAGED and missing indexes |
| `file.resumable.missing` | lease, object; revalidates actual persisted bytes and returns missing indexes |
| `file.resumable.finalize` | lease, object, optional safe relative path; returns sandbox-relative immutable path, size and sha256 |
| `file.resumable.release` | lease, object, discardPublication=false; retires temporary core state, preserving caller-owned publications by default |

`bindingId` carries the application's 128-bit request identity. `requestId` is
reserved: PodServices overwrites it with the host operation number. It must never
carry a wire request identity into the native adapter.

The adapter reuses the verified Rust receiver and no-overwrite guest publication.
A complete chunk set is assembled, hashed, fsynced and atomically published before
returning object STAGED. Every adopted cache is revalidated, including after a
process restart. Corrupted/missing caches return missing chunks. Incoming bad
bytes fail before adopting a historical receipt. Finalization permits one
successful immutable publication path per object; conflicting files are never
overwritten. A caller can select a different conflict path after a failed
no-overwrite publication. Callers must choose paths deterministically so crash
recovery does not manufacture aliases.

The application-wide limit is four active objects, including retained native
orphans. Negotiated smaller windows apply across active scopes. Each active
object reserves four times its declared bytes for blocks, assembly, immutable
publication and copy temporary; total reservation is at most the negotiated
staging bound (hard maximum 64 MiB). The four-copy bound limits admitted object bytes to 16 MiB, so even two-copy
core reservations fit its unchanged 32 MiB limit. A second partial 16 MiB
object waits until the first is applied and released. Typed legacy-host
QuotaException failures also become WINDOW_FULL. Public `sync.files` quotas are unchanged. The private journal
uses at most 8 MiB of SQLite pages and reserves another 8 MiB for rollback files.

Valid activity extends retention to at least 24 hours. Collection only visits an
explicit expired, unleased scope; unknown time after reboot delays collection.
It never infers business file ownership. `discardPublication` only permits
removing this scope's default temporary publication; application-chosen business
paths remain caller-owned. The caller must retain references/asset registration
and APPLIED in its business database, and collect auxiliary metadata only after
its own retention and reference checks.
