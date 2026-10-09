# System font fallback fixture

The application supplies characters at runtime so the compiler cannot preseed their atlas entries. `verify-pack.ts` checks the packaged cmap and exports its actual font metrics for the Android test. Samples cover CJK, CJK extension A/B, enclosed symbols, color emoji, ZWJ families, skin tones and variation selectors, in normal and bold text.

Missing atlas characters use an optional PocketJS host provider. Android `Paint` with `Typeface.DEFAULT` supplies both advance and pixels. Existing atlas advances, line height, baseline and pixels remain unchanged. Grapheme clusters keep emoji sequences together during layout and UTF-16 wrapping. Fallback cells have separate dimensions so emoji overhang is not cropped. Alpha masks retain text tint; bitmap glyphs retain native RGB, including gray/black detail. Both CPU and Vulkan consume the same cells.

Each atlas owns append-only glyph identifiers and its provider, so pinned frames remain valid across font replacement. Unsupported characters retain tofu. Each slot bounds metadata to 4096 entries / 64 KiB of text and pixels to 256 KiB with eviction and deterministic regeneration. Android bounds its Paint cache to 32 entries; GPU pages retain the existing 16 MiB budget. Primary atlas hits never call the Java rasterizer.

On OWW242 / API 30, `/system/etc/fonts.xml` selects `/system/fonts/NotoSansCJK-Regular.ttc`, `/system/fonts/NotoColorEmoji.ttf` and `/system/fonts/NotoSansSymbols-Regular-Subsetted2.ttf`. The implementation uses the platform stack, without hardcoding these OEM paths. Coverage and bitmap color depend on the installed system fonts.

Build the fixture **after** the compiler test suites, since those regenerate `styles.generated.ts`:

```sh
bun packages/cli/src/cli.ts build --target=android-watch --project="$(pwd)/apps/font-fallback"
bun apps/font-fallback/verify-pack.ts dist/projects/dev.podjs.fontfallback.rehearsal/android-watch/main.pak
ANDROID_SDK_ROOT=/path/to/Android/Sdk bash scripts/build-android-runtime.sh
cd platforms/android
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PpodAppId=dev.podjs.fontfallback.rehearsal -PpodCleartextTraffic=true \
  -PpodDistDir="$PWD/../../dist/projects/dev.podjs.fontfallback.rehearsal/android-watch" \
  -PpodTestSourceDir="$PWD/../../apps/font-fallback/androidTest" \
  -PpodTestAssetsDir="$PWD/../../apps/font-fallback/androidTest/assets" \
  -PpodAbis=armeabi-v7a,arm64-v8a
```

`FontFallbackDeviceTest` targets only this isolated package. It compares 16 host rasters/advances to independent native Canvas output, checks 13 real Vulkan screenshot regions against native pixels, and saves screenshots and JSON in the fixture's external files directory. An absent or blank renderer fails the test. Installation, ownership and removal are performed by the caller under its device policy.

Core tests cover metrics, original atlas preservation, color, negative caching, invalid providers, eviction, font replacement and grapheme wrapping. Runtime tests cover the C ABI and pinned snapshots; C++ tests cover color/alpha and transformed glyphs; Android unit tests cover validation and density-aware advances. The core example `system-font-profile` compares primary CJK measurement with/without the provider. This measurement comparison does not establish device scroll or frame-time performance.
