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

JNI receives unpremultiplied ARGB integers from Bitmap.getPixels in bulk. Native
code converts them to the unchanged straight RGBA C ABI. Generated immutable
ARGB arrays enter a bounded 1 MiB pending queue without byte packing or copying;
packing, compression and atomic writes run
at the lowest Android thread priority after first presentation. A full queue
skips persistence, never foreground drawing. Pending records remain readable.
The writer can additionally hold one serialized record (at most 1 MiB) and its
compressed output while writing. Its disk reservation includes the temporary
record. Filesystem writes run outside the foreground metadata lock.

System glyphs retain their advances, offsets and primary baseline while Rust
removes only transparent right/bottom rows/columns. One physical transparent
sample remains for bilinear filtering. Stable per-glyph dimensions survive
cache eviction and old snapshots. Legacy packaged atlas cells stay unchanged.
