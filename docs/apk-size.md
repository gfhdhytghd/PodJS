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
  "resources": { "optimizeFontSeeds": true }
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
ABIs. A conditional consumer means the runtime supports that resource when the
app requests it. It does not prove that a particular run used that entry.

The upstream `vendor/pocketjs/.cache/font-atlas/*.json` files cache build-time
atlas results as base64. The key covers the compiler/spec/lock/font bytes,
font slots, scanned codepoints, density, and glyph-cache options. Each file is
one complete cached atlas set; the build reads only its matching key. The cache
is not packaged or extracted on first launch. Different keys accumulate without
an eviction policy. Do not delete a shared compiler cache during a scoped build.

A full font included in both `ui:font-source.<sha256>` and `pod-assets/` serves
two consumers: native glyph fallback and Android Typeface-based reader rendering.
Removing either copy requires a shared source adapter. DCPK integrity v1 also
rejects overlapping payload aliases. Whole-pak ZIP compression would discard
the current direct mapping and random-read properties. These changes require
separate runtime designs and acceptance; the lossless seed optimizer does not
introduce them.
