# QR, tiled images, editing and restorable input

Independent subpaths keep QR generation and image components out of the root
entrypoint. No application routes, cache buckets, note schemas or assets are used.

- `qr`: `qrPixels(value, dimension, options)` supports byte/UTF-8 encoding,
  correction level, module margin, optional bitmap-grid resampling, inversion
  and error messages. `QRCode` accepts a raster callback, pads to a power of two,
  clips the displayed image and frees its texture on effect cleanup.
- `image-preview`: preview geometry, integer-pixel placement, centroid pinch,
  pan/scale decay and back-gesture eligibility.
- `image-tiles`: source pyramid tiles with a one-pixel halo at full resolution.
  `fullResolution(geometry)` supplies caller-specific sampling exceptions; the
  default uses screen density alone.
- `tiled-image`: `TiledImage` accepts `sourceProvider.acquire(url)` returning
  `{result: Promise<{path, release}>, cancel}`, or a direct `filePath`. A provider
  is still a required prop when using a file. Supply `decodeOptions`, `tileOptions`
  and messages for compatibility presets. It bounds decode/delete concurrency
  separately to four. A private service-result listener retries busy deletions.
  Source leases outlive canceled work; decoded files use native RGBA reads or
  chunked fallback and are deleted. Sampled uploads and the retained overview
  preserve filtering. Same mount has stable source identity, as before; remount
  to change the source. Decode options default to host behavior.
- `async`: `SerialWriteQueue` preserves each rejection and continues after failure.
  `flush()` captures the current tail, not future writes.
- `editing`: `TextHistory(initial, now?, capacity?, groupDelayMs?)` groups adjacent
  edits with bounded undo/redo. `DraftAutosave<T,R>` requires immutable draft
  snapshots, equality/empty checks, save and result-to-draft callbacks. The caller
  owns schemas, normalization and identities. `tick()` checks due time; `flush()`
  saves until the current draft matches the result. `dispose()` clears due time
  only. Default clock is Date.now. A permanently different normalized result
  prevents flush convergence; this is the preserved contract.
- `navigation`: `AutoScrollController` distinguishes requested and foreground
  running state, clamps bounds and stops at the end. Caller owns frame scheduling
  and must avoid competing inertia writes.
- `restorable-input`: call `useRestorableInput` inside a Solid owner. Optional key
  and recovery pending/dismiss callbacks reuse the existing host retained prompt.
  Inactive/disposed owners invalidate late continuations with an epoch. Recovery
  is checked once per owner; submit is not replayed. Caller owns route key, service,
  activity predicate and error presentation. No second persistence layer is added.

Contract tests: `packages/framework/tests/upstream-u7.test.ts` and browser-condition
`restorable-input.isolated.ts`. Downstream frozen-source differential tests compare
QR pixels/component trees, geometry, texture calls, deletion retry, delayed lease
release, edit/save ordering and stale input results. These are host tests; device
pixels, seams and process recovery require separate acceptance.
