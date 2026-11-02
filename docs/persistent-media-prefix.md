# Persistent media prefixes

`mediaPrefixCache.register(key, url, headers, true)` uses a stable content key for stored bytes. The caller must include the asset identity and exact rendition in that key. Use a new key whenever content or rendition changes. Signed URL and header changes re-register access without discarding matching bytes. Registration is still required in each process. Native storage never records URLs or headers.

`prepare(uri, bytes, frameKey)` runs in the background admission lane. `bytes` is the initial contiguous sample-prefix estimate (128 KiB to 4 MiB). MP4 preparation and ordinary window prefetch both locate a complete `moov`, walking at most 32 top-level headers without reading through `mdat`. Front metadata extends the prefix when necessary. Tail metadata is stored as a complete suffix with a 64 KiB read-ahead guard. A `moov` larger than 512 KiB is rejected. For ordinary sample tables (`stts`, `stsc`, `stsz`, `stco`/`co64`), the planner extends the prefix to cover the first approximately 1.5 seconds of every parsed track. Unsupported/fragmented sample tables retain the caller's byte estimate; telemetry reports `planned=false`. The complete prefix plus suffix remains bounded by 4 MiB and the cache's total storage limit. Non-MP4 preparation retains the legacy bounded suffix fallback within the caller's total budget.

A cache-only decoder validates the stored ranges and saves a bounded first-frame poster. An unavailable decoder or an uncached range produces `ready: false`, including when the decoder returns a fallback image after a failed probe. A zero-byte decoder read is not a cache gap. The ranges are checked again before publishing the frame, so concurrent eviction cannot publish readiness. Decoder validation performs no network IO.

Window publication protects the requested sources before evicting unrelated files. If protected files alone exceed the hard total limit, older protected files may still be evicted, preserving the first requested foreground source. Publishing the same in-flight source again updates its opaque result identity without reopening the transport. Ordinary metadata/sample range reads use the foreground bandwidth reserve; persistent preparation additionally uses the background admission lane.

Native `prepare_stage` snapshots include per-prepare network bytes and ranges, and `cache_network_bytes`, `cache_range_requests`, and `cache_bytes` for the registered cache entry. Entry counters include prefetch and playback transfers in the current process. Repeated playback of the same entry retains cumulative counters. Existing disk bytes are represented by `cache_bytes`, not historical network traffic. No URL, header, or content identity is logged.

Explicit persistent registrations bind the stored frame to the same content/rendition key as the media bytes. Rotating URLs or headers therefore preserve both bytes and pixels. Nonpersistent registrations retain their URL/header binding. Frames saved under the older URL-bound identity are conservatively refilled.

Call `inspect(uri)` and `video.restoreFrame(frameKey, {url: uri})` before advertising readiness after restart. The app owns metadata persistence, expiry, consent, feed refresh, watched state, and inventory policy. A cache URI alone does not prove readiness. Cache eviction invalidates inventory; callers must recheck it.

`window([])` cancels preparation as well as foreground-window prefetch. Cancellation removes waiting background admissions even while foreground media remains active. Background IO never holds a shared-work ticket across a network request or decoder call. One native read already in progress may finish before cancellation; generation checks prevent it from republishing inventory.

Prefixes are not complete videos. Playback reads the cached prefix and tail, and uses upstream for uncached ranges. `ready` proves that the local decoder obtained a first frame. Device measurement must establish the player-specific first-frame behavior and buffered duration. Cold start p95 and network-free Media3 playback are not implied by JVM tests.

The cache remains bounded to 4 MiB per entry and its configured total (6–16 MiB in production), with at most six byte snapshots retained at process close. Posters have a separate 4 MiB total and six-entry limit.

Range responses must match the requested start and have a consistent total length. A capped 206 body ends a response range, not the media file: preparation continues from the next contiguous byte, with at most 64 requests per range operation. Short bodies and conflicting ranges fail preparation before readiness. Failed or cancelled suffix bytes remain counted as network reads but are never published as a partial tail. Transfer callbacks also count bytes skipped inside the HTTP transport when a server ignores Range and returns 200. Sources without transfer callbacks count returned read bytes instead.

`stock_gap` diagnostics contain only numeric requested position/length, prefix length, tail start, and total length. They contain no URL, content identifier, headers, or exception text. Device sampling is still required to attribute a historical gap to a specific extractor read.

## First-range scheduling and mirrors

`mediaPrefixCache.register(key, url, headers, persistent, alternates)` accepts at
most one alternate. The caller must supply a byte-identical URL for the selected
track, with the same HTTP scheme. The host rejects credentials in URL user-info,
more than one alternate, and a scheme change. The capability identity includes
nonpersistent mirrors; persistent content identity remains the caller's key.
No URL, request header, DNS answer, or mirror list is persisted or logged.

The prefix transport owns an OkHttp pool (four idle connections, two minutes)
and a bounded DNS cache (32 hosts, 60 seconds). Bounded Range bodies finish before
close, so the next real request can reuse the connection. There are no HEAD or
synthetic warmup requests. Standard TLS verification, redirects and HTTP/2
negotiation remain in OkHttp; no traffic disguise or signing changes are added.
Automatic connection retries are disabled. A 403 or 429 stops the transport
instance, cancels its calls, and rejects later opens without another request.

For a fresh prefix, two same-track candidates race to the first **body byte**,
not response headers. The losing call is cancelled, including a pending open.
The winner remains selected for subsequent ranges. A single fetch scheduler
serves window and persistent-stock preparation. It fetches first-frame stages
in window order, then prefix remainders in window order. Window updates reorder
pending work without restarting a still-wanted transfer. An admitted range is
not preempted merely for reordering. Normal playback bypasses both scheduler and
race workers, using the selected mirror if available or the original source.
It never waits for a prefetch job or races another pair behind background opens.
Existing foreground network bandwidth reservation still gates cache publication.

The first stage reads up to 192 KiB, locates and caches the complete `moov`, then
uses the first video sync sample's table entry to extend the contiguous prefix
if required. A 128 KiB extractor read-ahead guard is separate from the sample
size. Tail metadata uses one bounded suffix when it fits within 512 KiB instead
of a header probe followed by the same suffix. The existing 1.5-second sample
plan and requested prefix remainder run at lower priority. The 4 MiB entry and
16 MiB cache limits still apply. Unsupported or malformed first-sample tables
report `planned=false`; they retain the byte estimate. A keyframe byte plan is
not decoded-frame readiness. Frame readiness continues to require decoding.

`PodMediaNetworkTest` runs the same cache-only Android decoder test against the
base and candidate using `tests/runtime-dyswipe/slow-cdn.py`. The fixture's
handshake delay is only a latency simulation; it does not prove TLS or HTTP/2
performance. The first keyframe packet is checked independently with ffprobe.
Emulator results do not establish watch playback, mixer, or swipe acceptance.
