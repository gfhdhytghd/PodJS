# Android release size

Android Watch and Wear OS release builds enable R8 by default. Debug builds
retain their existing behavior. Runtime AAR consumer rules preserve named JNI
upcalls, exported native methods, optional debug reflection, WebView JS methods,
and Java serialization protocol members. Room, WorkManager, and Media3 use their
bundled consumer rules. PodJS's migration converters read SQLite explicitly;
they do not construct Room entities through reflection.

Applications can extend the rules or disable optimization in `pod.config.json`:

```json
{
  "android": {
    "release": {
      "minify": true,
      "shrinkResources": false,
      "proguardRules": "android/app-rules.pro"
    }
  },
  "resources": { "optimizeFontSeeds": true, "shareFontSources": true }
}
```

The optional rules path is relative to the application. Resource shrinking is
opt-in and requires minification. Direct Gradle users can set
`podMinifyRelease`, `podShrinkResources`, and `podProguardRules` on either app
module. Library consumer rules do not set global optimization flags.

The package command copies R8's `mapping.txt` beside its signed release APK in
`dist/projects/<appId>/packages/<target>/`. Keep the mapping with the exact APK;
neither belongs in source control. An explicitly unminified package removes a
stale mapping from that destination.

`optimizeFontSeeds` defaults to true on Android targets. The build recompresses
existing FONT v6 seed cells at DEFLATE level 9 only when that cell becomes
smaller. All cmap bytes, metrics, seed IDs, decoded coverage cells, source TTF
bytes, icons, and styles remain identical. Runtime behavior and the DCPK v1
container remain unchanged. The existing glyph cache inflates each compressed
cell on demand; the optimization adds no on-disk cache or first-run extraction.
Set the option to false for a build comparison. Other atlas versions pass through.

Inspect an APK or a standalone pak without extracting it:

```sh
python scripts/analyze-apk.py path/to/app-release.apk > inventory.json
python scripts/analyze-apk.py path/to/main.pak > inventory.json
```

The JSON lists every entry's key, byte count, dtype, semantic type, SHA-256,
static runtime consumer, font metadata, exact duplicates, APK entry sizes, and
ABIs, and verified `pakFont` aliases. A conditional consumer means the runtime supports that resource when the
app requests it. It does not prove that a particular run used that entry.

The upstream `vendor/pocketjs/.cache/font-atlas/*.json` files cache build-time
atlas results as base64. The key covers the compiler/spec/lock/font bytes,
font slots, scanned codepoints, density, and glyph-cache options. Each file is
one complete cached atlas set; the build reads only its matching key. The cache
is not packaged or extracted on first launch. Different keys accumulate without
an eviction policy. Do not delete a shared compiler cache during a scoped build.

Android builds now share an identical font asset with its raw
`ui:font-source.<sha256>` entry by default. The asset manifest retains the logical
name, byte count, and digest, and adds `pakFont` with the authenticated entry
range. The build omits the standalone file only when its content matches that
entry. Non-font assets and other targets keep physical assets. Set
`resources.shareFontSources` to false for a legacy direct-AssetManager consumer.

`PodFontServices` resolves the logical font through `PodPakFontAssets` for text
measurement, paragraph shaping, line rasterization, and inline editing. It
validates the manifest range against the signed DCPK directory and hashes the mapped font against its source digest. It opens the stored
pak with AssetManager.openFd, and maps only the TTF range with FileChannel.map.
Font.Builder consumes that direct mapped buffer with the same TTC index, weight,
slant, variations, and default fallback construction as Typeface.Builder.
Native glyph rendering and Android fonts read the same immutable APK file pages.
The adapter creates no TTF byte-array copy, temporary font file, or extracted pak.
Legacy standalone AAR assets still resolve through Typeface.Builder.

The old compressed font allocates a full TTF buffer on ART's heap, which can
also collect unreachable startup objects and run their native cleaners. The
mapped path removes that allocation pressure. After the first successful font
mapping of at least 8 MiB per AssetManager, the adapter requests collection once,
with the new Typeface still live. Cache hits and subsequent styles do not
request collection. The numeric `pakFontFirstCollection` startup trace records
its cost. Include this first-use cost when comparing font paths; a home screen
that does not request an asset font does not execute this collection.

Foreground cloud lifecycle calls authenticate background handlers with a bounded
UTF-8 decoder and streaming SHA-256. They no longer create a whole-source String
just to discard it after verification. Byte length, hash, strict UTF-8, handler
identity, and capability checks still apply. Workers that execute a handler
retain the existing source-loading path.

Capture `/proc/<pid>/smaps_rollup` and full mappings before `dumpsys meminfo`.
The process callback from meminfo can request collection, so also retain the
post-meminfo snapshot. Compare file clean pages, anonymous/dirty pages, native
heap, Java heap, and first-use peak separately. For mixed-core emulator hosts,
record CPU affinity and use the same CPU class for both variants.

 DCPK integrity v1 also
rejects overlapping payload aliases. Whole-pak ZIP compression would discard
the current direct mapping and random-read properties. These changes require
separate runtime designs and acceptance; the lossless seed optimizer does not
introduce them.

The complete `pod.manifest.json` file has a 128 KiB UTF-8 budget, including its
final newline. Android font, background, companion-sync and legacy takeover
readers use `PodManifest`; native package validation and Harmony's background
reader use the same budget. The separate compact `pod.workmanager.json` retains
its 64 KiB limit. A build checks the exact buffer it will write. If font aliases
alone exceed the package budget, it discards that staged asset transaction and
retries with physical font assets. If legacy metadata also exceeds the budget,
the build fails before publishing assets or replacing the previous manifest.
The budget is not raised to make aliases fit.
