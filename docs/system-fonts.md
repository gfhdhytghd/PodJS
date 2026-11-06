# Android system fonts

`resources.fontSource: "system"` opts an Android project into device fonts.
The default remains packaged fonts. Non-Android targets reject this option.
System mode requires host ABI 4; ABI 1–3 packages remain supported.

```json
{
  "resources": {
    "fontSource": "system",
    "fontSlots": [{"slot": 33, "px": 14}],
    "preloadFontSlots": [2, 1, 19, 22],
    "glyphCache": {"warmChars": "应用常用字符", "preseedScanned": false}
  }
}
```

Remove packaged default font assets and their `fontAsset` requests when moving
an application to system mode. Imported user fonts retain the `fontPath` API.
System mode does not embed the default TTFs or any baked glyph pixels. Each
used slot contributes a 24-byte FONT v7 descriptor containing size, weight and
density. The host supplies metrics before installing the slot; older hosts
reject the package ABI instead of silently rendering an empty atlas.

`PodSystemFonts` reads `/system/etc/fonts.xml`. It selects the default sans
family plus Chinese, Japanese, Korean, emoji, symbols and current-language
fallbacks. It skips absent files and serif-only alternatives. Collection
indices, explicit weights, slant and variation axes come from the XML. Files
under `/system/fonts` are mapped with `FileChannel.MapMode.READ_ONLY`; a font
buffer is retained for as long as its native typefaces can use it. Other script
fallbacks use Android's system sans chain. Android XML is not a stable public
format: this implementation is verified on the devices listed in the report,
not on every Android version or OEM font overlay.

Both retained native glyph callbacks and Android paragraph/EditText services
use the same mapped typefaces. Skia/Minikin provides metrics, rasterization and
emoji cluster shaping. The native surface keeps append-only glyph IDs, and
centers the font's natural primary cell in its line box. Larger emoji/overhang
cells are separate so they cannot move ordinary text upward. The native surface
uses a shared 8 MiB LRU for system-font pixels. In-flight render snapshots can
retain evicted cells, so 8 MiB is a cache-reference limit, not a total PSS limit.
Legacy packaged fonts keep their existing coverage rasterizer and cache.
Font XML parsing and mapping run alongside native runtime/Vulkan creation on
a worker. Boot joins font preparation before package/font installation, so
fonts are ready for the first measurement without serializing independent setup.

After the first displayed texture, a background worker warms the printable
ASCII set and the deduplicated `warmChars` set in the preloaded slots. Visible
text renders on demand. The rasterizer reuses one bitmap and pixel scratch
array. Measurement and rendering retain the same advance and baseline when
cells are read from disk or regenerated. Existing disk records are not decoded
again merely to prewarm them. Cache startup reads file metadata once before
sorting; key validation and hex encoding avoid per-record regular expressions
and formatters.

The disposable disk cache is at `cacheDir/podjs-system-glyphs`. Its **16 MiB
limit includes glyph records, the font identity receipt, and temporary writes**.
The budget charges rounded filesystem data blocks, one additional metadata
block per record, and 512 KiB for directory growth (or a larger existing
directory allocation). This conservatively accounts for tiny
files rather than allowing 16 MiB of compressed payload to occupy much more
physical storage. Compressed records have a raw-size bound and CRC. Reads update LRU order;
writes evict old records before writing an atomic replacement. Invalid or
unavailable cache data causes regeneration, not missing text or failed edits.
This is additional app storage; the APK contains no initial cache image.

The pixel key includes the immutable system-font identity, rasterizer revision,
size, weight, density, cell geometry, baseline and text cluster. The identity
is the SHA-256 of XML, the OS build fingerprint, the rasterizer revision and
all selected file names/sizes/mtimes. No complete font scan runs before first
display. A 130-byte receipt belongs to the same disk budget. Namespace identity
changes when XML, the OS build fingerprint or selected file metadata changes.
This relies on the OS-owned immutable `/system` partition; mutable OEM
font overlays are outside the currently verified scope.

Unsupported characters retain the existing tofu behavior. This option does not
download or add a supplemental font. Acceptance requires that characters the
previous package displayed remain supported, including complete emoji sequences.

Verification includes `bun run test`, the application typecheck/full suite,
`PodGlyphDiskCacheTest`, `PodSystemFontsTest`, native descriptor/cache tests,
separate signed R8 packages, UI screenshots and measured A/B data. The bounded
`pod_runtime_probe_glyph_cache` host diagnostic reports first acquisition and
immediate repeat times and checks that their pixels match; it has no product UI.


## Startup and acquisition work

The bounded `.font-index` caches the selected XML families, collection indices,
weights and axes. Its CRC, XML SHA-256, OS/renderer identity, language and selected
file metadata must match before reuse. A mismatch parses XML again. The index
shares the 16 MiB disk budget with glyphs. Font objects/maps are lazy per requested
style; no-text metrics require only the mapped primary sans face. Disk glyph
hits return before constructing Paint or fallback typefaces. Ordinary 400/700
styles build the nearest XML face of each family and
reuse identical families. A full-family construction remains a differential
reference. Uncommon style requests retain full-family selection.

Disk hits map the existing immutable compressed record read-only and decode
zlib/CRC32 in native code, returning owned ARGB words through JNI. File format,
metric bits and straight pixels are unchanged. This avoids Java inflater and
integer-buffer setup on the first warm lookup. It is not zero-copy pixel mmap.
Host tests retain the independent Java decoder.

JNI receives unpremultiplied ARGB integers from Bitmap.getPixels in bulk. Native
code converts them to the unchanged straight RGBA C ABI. Generated immutable
ARGB arrays enter a bounded 1 MiB pending queue without byte packing or copying;
packing, compression and atomic writes run
at the lowest Android thread priority after first presentation. A full queue
skips persistence, never foreground drawing. Pending records remain readable.
The writer can additionally hold one serialized record (at most 1 MiB) and its
compressed output while writing. Its disk reservation includes the temporary
record. Filesystem writes run outside the foreground metadata lock.

After first presentation, inventory existing glyph records with one bounded native directory
scan and one JNI metadata transfer. Length and modification time come from one
stat per entry. Glyph-name validation and (mtime, name) sorting also run natively.
Java retains compact names/metadata and creates File paths only
when a record is accessed. This scans the live directory and preserves full LRU
and byte-budget validation; it does not trust a persisted glyph catalog. Native
failure, non-ASCII foreign names, or an oversized transfer use the Java scan.
Before inventory, first-screen reads open immutable records by their hashed key
and validate their size and CRC. Pixel records, up to 128 KiB of parsed-font
metadata, and bounded early-access bookkeeping remain in RAM. No cache file is
written before inventory and LRU trimming complete. Early reads/deletions are
reconciled before publishing the inventory. An already oversized/corrupt private
directory is repaired during this deferred inventory; the cache never adds disk
bytes before that repair. The ordinary eager constructor keeps its old behavior.
Font preparation starts in the view constructor, overlapping existing service
and window creation. The UI thread does not wait and the Surface callback is
unchanged. Native boot joins the same preparation future before loading slots.
Cached XML/path validation uses fixed ASCII rules without per-file regex builds.
The same CRC/fingerprint-bound index also retains computed descriptor metrics.
Warm slot installation can return copied cell/baseline/line-height values before
constructing any Typeface. Actual first-run metrics are collected in memory and
persisted after background warmup. Font/build/XML changes invalidate both faces
and metrics. Warmup always receives a copy, so growing a fallback cell cannot
change primary line metrics.

System glyphs retain their advances, offsets and primary baseline while Rust
removes only transparent right/bottom rows/columns. One physical transparent
sample remains for bilinear filtering. Stable per-glyph dimensions survive
cache eviction and old snapshots. Legacy packaged atlas cells stay unchanged.

## Adjacent reader line preparation

`prefetchSystemTextLines(options, cache)` prepares exact `SystemTextLine` requests
without creating scene nodes. This is separate from the device glyph cache.
The caller owns the cache and the cancellation function. Completed textures
enter the same bounded history as released visible lines. Pending reservations
and retained history share its entry/byte limits. Active visible leases keep
their existing ownership rules and are not evicted to admit background work.
One background request leaves the other host slot available for visible text;
queued visible requests precede background requests. Attaching a visible line to
an in-flight preparation promotes that exact entry instead of creating another
raster request. Full options, including font revision, paragraph, dimensions and
style, remain the cache identity. Cancellation/close drain late temporary files
without publishing a stale image. This API does not change glyph pixels, font
selection, line metrics, the 194-character startup warmup or its 16 MiB disk cap.

Preparing a texture not referenced by the current DrawList does not invalidate
that scene's presentation hash. Referenced texture generations/revisions remain
part of the hash, including disappearance of a stale handle. Empty lists,
auxiliary-surface commands, unknown opcodes and truncated records retain the
conservative whole-registry path. Snapshot resource ownership is unchanged.

First-texture startup timings use the callback-entry monotonic timestamp, before
checkpoint, splash and warm-up work. `systemFontWarmStart callerMs` reports only
the caller's warm-up scheduling cost; `firstTextureAfterWork` reports the old
post-work endpoint separately. Neither value changes the first-texture sample.
Warm-up admission and cancellation use an atomic token, not the foreground font
monitor. Cache/config lookup and disk-write activation run on the worker.
The worker has its own bounded Paint maps, Canvas and scratch bitmap; it cannot
hold the foreground raster monitor. Its scratch bitmap is recycled on completion
or cancellation. Glyph pixels and the 194-character/16 MiB policies are unchanged.
