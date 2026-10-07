# Build gating, volume, final writes and preview publication

These mechanisms accept application policy rather than watch/app defaults. Import
narrow modules; public exports are listed in [getting started](getting-started.md).

## Capability gating

`new FailClosedGate(accepts)` from `build-gate` starts closed. `initialize(read)`
shares the same promise with all callers, evaluates the host value once, and
returns false on query failure without retrying. The caller supplies its literal
capability predicate and rejection text/actions. No KV/env/version fallback is
provided. If the predicate itself throws the promise rejects and the gate remains
closed; predicates should be pure and return booleans.

## Fractional crown volume

`media-volume` exports `CrownVolumeController`, `applyCrownVolumeGuard`,
`scaledCrownVolumeDelta`, `emptyCrownGuard` and their ports/state/profile types.
The caller must provide `sessionGapMs`, `capFraction`, signed `rawScale`,
`rangeScale` and `hideMs`; no media policy is selected by the framework.
Positive rawScale reverses raw input; negative rawScale preserves its direction.
The scaling function rounds each Float multiplication exactly. The guard uses a
minimum positive physical volume step and one notification per capped session.

`adjust(raw,eventMs,guard)` retains fractional motion and applies every sample's
direction/guard/timestamp in order. It reads current host state per batch and
serializes final integer writes. External current/range changes reset fractional
state. Fixed streams show progress but do not write. `reset()` clears pending
motion, guard state and hide timer; generation checks ignore old read/write
completions. It does not cancel an already-issued host write. All display/error/
notification callbacks belong to the caller; ports retain their method receiver.

## Final-write exit waits

`async.waitForFlush(flush,timerFactory,timeoutMs)` resolves `{status:'saved'}`,
`{status:'failed',error}` or `{status:'timed-out',timeoutMs}`. Timer construction
failure prevents invoking flush. Timer cancellation runs after either race result
and also after a synchronous flush throw. Timeouts bound waiting, not the actual
write: a late write can still finish. The timer must have a promise `result` and
nonthrowing `cancel()`; write callbacks return promises.

`ExitFlushGuard<T extends object>` accepts an explicit timer/budget. `begin` drops
repeated attempts for the currently pending **object identity**. A different
object can start an independent wait; an old completion cannot clear a newer
pending identity. Completion callbacks are caller policy and should not throw.
No target value comparison, navigation, progress schema or persistence is added.

## Private HTTP response files

`private-response.withOwnedPrivateFile(source,remove,work,failed)` accepts either
an already accepted path or an async allocation callback. Validate ownership
**inside allocation before returning**: rejection leaves the helper without an
owned path. Work receives only that accepted path. A response's path never
changes ownership. Errors are reported before cleanup; late failures also clean
up; cleanup rejection is suppressed; settlement waits for cleanup. `remove` must
return a promise. No underlying download cancellation is inferred from failure.
Do not supply an allocation callback that launches detached downloads.

Two separate reading profiles avoid silently strengthening existing clients:

- `readDownloadedBytes(path,size,read,incomplete)` allocates with the original
  Uint8Array size semantics and reads raw chunks at successive offsets. Empty and
  overrun chunks reject; no result-path/EOF/size metadata validation is added.
  Supply the download's size cap and expected-path policy outside this profile.
- `readDownloadedChunks(path,response,read,options)` checks response path identity,
  safe nonnegative size <= `maxBytes`, offset and total-size metadata, bounded
  canonical base64 and exact EOF. `chunkBytes` must be a positive safe integer;
  `maxBytes` a nonnegative safe integer. Caller factories provide metadata/chunk
  errors. Decoding/encoding reuses services (including native encodeBase64 when
  available) and reads through caller ports, normally `fileChunks.read`.

The caller chooses temporary directory/regex, HTTP method/body/timeout/header/
status policy and safe logs. Empty responses do not issue reads. This is a bounded
file-to-memory response helper, not an HTTP client or cache. Limits and concurrent
request count remain caller responsibilities.

## Ephemeral preview sessions

`preview-session.PreviewSession<T,D,S extends PreviewFrame>` accepts a caller
clock (wall/monotonic/after), normal/extended timeout and publication interval.
Supply validation, normalization, delta conversion/emptiness, missing-baseline
error, frame creation and extended-state predicate. Payloads are non-null; `0`,
false and empty string work. Frames include caller `sessionId` and `sequence`.
The validation callback must reject disposed, invalid ID and invalid sequence
inputs according to the application's protocol.

A full update publishes immediately and changes session ownership after successful
normalization. Delta accepts its sequence **before** converting; failure consumes
that sequence but leaves the previous payload. Empty deltas/heartbeat advance
accepted sequence and renew the normal timeout without publishing. Recent deltas
coalesce to the final frame. Resource-style renewal (`refreshTimeout`) uses the
**published** frame's extended-state predicate; pending frames do not qualify.
`stop(id)` affects only matching ownership, cancels both timers and emits null;
`dispose()` stops then clears listeners. Expiry uses caller intervals. Listener
callbacks run synchronously in Set order; exceptions/reentry retain that order,
so callbacks should normally be nonthrowing. No data is persisted.

## Match geometry

`text-match.textMatches` trims the query, lowercases text/query and produces
nonoverlapping ranges using the **original query's UTF-16 length**. This preserves
legacy casing semantics even when lowercasing expands a code point. It is not a
Unicode grapheme search engine. `matchSegments` calls caller measure on prefix
and matching slice. `matchLines` matches normalized CRLF source before wrapping,
finds visible lines forward, removes a trailing ellipsis for geometry and falls
back to per-line matching for missing source lines. It emits only visible
intersections. Snippet selection, HTML cleanup, colors and view rendering are
application policy.
