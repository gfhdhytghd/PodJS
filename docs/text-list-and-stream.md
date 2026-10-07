# Text, list state and bounded transport helpers

These modules own reusable algorithms and coordination. Callers own themes,
labels, routing/identity codecs, storage namespaces and snapshot revision checks.
None of these APIs chooses a screen, database table, protocol action or cache.

## Text environments and stable carets

`@podjs/framework/text-environment` exports `createTextEnvironment()` and
`TextEnvironment`. One configuration/owner shares physical/logical `pixelScale`,
`rasterSupported`, and successful `fontMetrics(slot)` results. Missing fonts are
not cached: a later atlas load is visible on the next query. There is no global
singleton. `invalidate()` clears font facts and refreshes display/capabilities;
call it after an atlas, display or host replacement (or replace the environment).
These facts are not Solid signals; the caller controls configuration publication.

`@podjs/framework/text-carets` exports `updateCaretMap(previous,key,positions)`.
An unchanged delivery or absent-key removal returns the same map. A real update
copies the map, preserves other keys/arrays, and retains the supplied positions.
This pure entry point does not load host services or the raster component.

## Separate line profiles

`@podjs/framework/text-lines` is a pure entry point. `text-layout` re-exports the
same functions alongside its existing asynchronous scheduler; use `text-lines`
when host initialization is unnecessary.

- `watchUiLines(text,{width,measure,maxLines?})`: character-fit UI profile with
  closing/opening punctuation and ASCII-word boundary corrections.
- `breakUnits(paragraph)` / `layoutUiText(text,options)`: unit-based punctuation,
  word and URL-slash profile with a legacy grapheme fallback.
- `measuredLines(text,width,measure,maxLines=Infinity,firstIndent=0)`: wraps with
  the unit profile before fitting an ellipsis with the same measurer.
- `normalizeCjkTitle(title)`: explicit compatibility normalization (CJK spaces,
  while preserving ASCII word spacing).
- `balancedTitleMeasurements(title,width,firstWidth,otherWidth,suffix?,minPrefix=0,normalize?)`:
  generator yielding exact measurement requests; final value is balanced lines.
  `balancedTitleLines` drives it with a synchronous measurer. Width presets and
  normalization are explicit; the default normalization leaves text unchanged.

These profiles deliberately differ from `text-wrap.layoutText`/atlas wrapping.
The fallback grapheme logic is legacy partial clustering, not full Unicode
segmentation. Do not silently substitute one profile for another.

`@podjs/framework/regexp.lazyRegExpTest(source,flags)` compiles on the first test.
It preserves native RegExp semantics, including stateful `g`/`y` lastIndex and
syntax errors at first use. Both the line helpers and atlas wrap share it.

## Route owner coordination and variable-height lists

`@podjs/framework/navigation` exports `RouteStateProvider`, `routeState`, and
`restoredSignal(channel,key,initial,validate,enabled=true)`. A provider supplies
`memory():{saved,retained}`, `changed()`, `checkpoints` and `scrollSlots`. The
memory callback is lazy; the framework neither allocates route IDs nor resolves
routes. A valid value restores independently per key; setters (including
functional setters) update the selected channel and call `changed`. Outside a
provider or when disabled, the signal is local. `booleanState`, `finiteState`
and `stringState` validate primitive types; callers add length/schema policy.
The provider does not serialize, enforce schemas, or copy host-owned handles.

`@podjs/framework/variable-list` exports `VariableList`, `ScrollEntry`,
`PositionedScrollEntry`, `IndexedScrollEntries` and `ListRestorationOwner`.
It combines existing window sources/row retention and kinetic scrolling with:

- variable heights, keyed indexed body rows, headers/footers and retained owners;
- optional controller, input gating, fixed layout, tap/long press, D-pad and
  accessibility increment/decrement actions;
- pull refresh (`refreshDistance`, default 48 logical units), manual-scroll and
  offset/scroll-state callbacks;
- exclusive viewport `onSettledRange(first,last)` with `settleKey` invalidation;
- optional `restorationOwner()` returning `{memory,checkpoints,scrollSlots,changed}`.
  This allocates a reusable `scroll:N` slot, saves existing index-based anchors,
  restores late rows, yields to user/programmatic input, and releases ownership
  at disposal. The caller decides which controllers own their own anchors.

Defaults are padding/gap 0 and overscan 60. Supply application geometry explicitly.
The viewport clips without translating; only the content moves. Fixed layout
still preserves the original gesture/refresh behavior. Cleanup checkpoints then
stops the scroller and sends the final scroll-state callback if required.
Index anchors do not promise identity preservation after arbitrary data reorder.

## SQL paging and stream framing

`@podjs/framework/sql` accepts a structural query-only `SqlQueryPort`.
`querySqlPages(db,query,params=[],pageSize=400)` appends `LIMIT ? OFFSET ?`, keeps
parameter ordering and halves pages only for the host's “query result too large”
error. Supply a stable ORDER BY and positive page size; unrelated errors escape.

`readSqlText(db,select,params=[],{maxBytes?,errors?})` requires a trusted single
column SELECT aliased `value`. It uses 32768-byte BLOB pages and four-byte boundary
probes before SQL TEXT decoding, preserving NUL and supplementary characters.
It distinguishes empty, null, missing rows and changed/invalid/oversized replies.
A caller can provide all three `invalid`/`changed`/`boundary` messages and its size
limit. It does not protect against same-length concurrent edits; callers retain
their surrounding revision/transaction checks. SQL strings are trusted code,
not unescaped user input. Original protocol byte limits belong to the caller.

`@podjs/framework/frame-stream` exports `StreamFrameConnection<T>`,
`FrameByteStream`, `FrameCodec<T>`, `TransferByteProgress` and
`TRANSFER_CHUNK_BYTES` (16KiB). Construction takes `(stream,codec,now=Date.now)`.
The codec owns `maxFrameBytes`, optional positive `chunkBytes`, and async
`encode`/`decode`; these consume/produce a complete four-byte signed big-endian
length-prefixed frame. A caller can reuse `sync-framing` for opaque byte codecs
or supply its own parsing and cancellable encoding without framework JSON policy.

The connection enforces a whole-frame read deadline, single concurrent reader
and writer, no empty/oversized transport reads, acknowledged writes, per-chunk
byte progress (including the prefix), and idempotent close. Read/write errors
close the transport; cancellation is exposed to both codec calls. Stream or
codec close failures keep the original rejection semantics. No socket, peer,
authentication or protocol session is created by this coordinator.

## Verification

The framework test command runs `upstream-u8.test.ts`, plus browser-isolated
`text-environment.isolated.ts` and `variable-list.isolated.ts`. They cover native
host/default construction, explicit invalidation, missing-font retries, pure
profiles, caller limits, exact SQL bytes, opaque frames, progress, concurrency,
late-row restoration, pull thresholds and cleanup. Package style checks remain
part of the full suite. These checks do not establish physical-device behavior.
