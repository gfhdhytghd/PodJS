# Persistent media prefixes

`mediaPrefixCache.register(key, url, headers, true)` uses a stable content key for stored bytes. The caller must include the asset identity and exact rendition in that key. Use a new key whenever content or rendition changes. Signed URL and header changes re-register access without discarding matching bytes. Registration is still required in each process. Native storage never records URLs or headers.

`prepare(uri, bytes, frameKey)` runs in the background admission lane. It stores a bounded prefix and, for larger assets, up to 512 KiB of the tail. Both count against the per-entry and total cache budgets. A cache-only decoder reads those ranges and saves a bounded first-frame poster. An unavailable decoder or an uncached range produces `ready: false`. The operation does not promote incomplete media to ready. It never uses network IO during decoder validation.

Call `inspect(uri)` and `video.restoreFrame(frameKey, {url: uri})` before advertising readiness after restart. The app owns metadata persistence, expiry, consent, feed refresh, watched state, and inventory policy. A cache URI alone does not prove readiness. Cache eviction invalidates inventory; callers must recheck it.

`window([])` cancels preparation as well as foreground-window prefetch. Cancellation removes waiting background admissions even while foreground media remains active. Background IO never holds a shared-work ticket across a network request or decoder call. One native read already in progress may finish before cancellation; generation checks prevent it from republishing inventory.

Prefixes are not complete videos. Playback reads the cached prefix and tail, and uses upstream for uncached ranges. `ready` proves that the local decoder obtained a first frame. Device measurement must establish the player-specific first-frame behavior and buffered duration. Cold start p95 and network-free Media3 playback are not implied by JVM tests.

The cache remains bounded to 4 MiB per entry and its configured total (6–16 MiB in production), with at most six byte snapshots retained at process close. Posters have a separate 4 MiB total and six-entry limit.
