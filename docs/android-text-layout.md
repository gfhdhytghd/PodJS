# Android paragraph layout

The `text.layout` capability is available on Android watch and Wear OS hosts. `textLayout.layout(options)` from `@podjs/framework/services` returns a cancellable operation with `{lines: [{start, end, visibleEnd, topPx, bottomPx, baselinePx}]}`. All offsets are UTF-16 offsets into the supplied string, including hard newlines. `end` includes a consumed newline and trailing whitespace; `visibleEnd` follows Android Layout's visible-end rules. A terminal newline can produce an empty last line.

`textLayout.layoutBatch(paragraphs)` uses the same capability and paragraph options, returning ordered `{results}` in one cancellable host operation. A result contains either `lines` or an `error` for that paragraph; one invalid paragraph does not discard valid neighbors. The host accepts 1–32 paragraphs and at most 262,144 UTF-16 units total. It checks cancellation between paragraphs and before replying. Callers should split longer documents into bounded batches and still validate returned line offsets against their own source text. This reduces service round trips; it does not change Android's paragraph shaping or make a long `StaticLayout` build interruptible.

## Current text environment

Android watch and Wear OS hosts advertise the separate `text.environment` capability. `await textLayout.environment({spValues: [12, 14, 21, 26]})` returns `{density, fontScale, spValuesPx}`. The optional `spValues` array accepts up to 128 finite numbers in the inclusive range 0–8192. The returned physical-pixel values preserve input order and duplicates without integer rounding. Omitting `spValues` or supplying `[]` returns an empty `spValuesPx`; other types, `null`, invalid values and oversized arrays return `invalid_argument`. Hosts that advertise only `text.layout` or `text.raster` do not support this query and return `unsupported` through the framework capability check.

Each request reads the current host Activity context's `Resources` on the main thread. `density` is physical pixels per dp and `fontScale` is the current configuration setting. Each supplied SP value is converted with Android `TypedValue.applyDimension(COMPLEX_UNIT_SP, value, metrics)`. Treat `fontScale` as a setting rather than a universal multiplier: Android 14/API 34 and later can use nonlinear scaling, so `sp * density * fontScale` is not a replacement for the returned conversions. The host returns floating-point values; callers choose their own pixel rounding and must still satisfy the separate layout/raster size limits.

The query does not cache configuration or alter system settings. Query when a guest mounts or remounts, and query again after an active lifecycle transition if configuration could have changed while suspended. The stock Android and Wear OS activities follow normal Android configuration recreation. No dedicated configuration-change event is added by this API: a custom shell that consumes a font configuration change without recreating its guest must arrange a fresh query. Existing layout, measurement and raster methods continue to receive physical pixels and do not apply another font scale.

### Android platform SP and Compose

This contract reports the platform's SP conversion, not every Compose release's typography behavior. Inspection of the locally cached AndroidX Compose **1.7.1** `ui-unit-android` and `ui-text-android` artifacts confirms that both `Density(context)` and `Density(density, fontScale)` use Compose's own nonlinear converter from font scale 1.03, including on Android versions before API 34. Android's platform conversion on those older versions is linear. On newer Android releases, platform and Compose converter tables can also differ; the API does not promise equality with a particular Compose version or an app's custom `LocalDensity`.

The isolated Android 30 WatchRSS startup fixture exercised this public JavaScript query before and after actual Activity recreation: density 2, fontScale 1, and `[12,14,21,26,12]` SP converted to `[24,28,42,52,24]` pixels. The device configuration and unrelated fixture KV remained unchanged. All six `PodTextEnvironmentServicesTest` cases also passed on that device, including alternating isolated Resources configurations and rejected input. Evidence is recorded in the consuming application's `artifacts/startup-mmap-finish/` directory. Context-level configuration tests do not establish rendering at a nondefault Activity font scale, API 34+ platform behavior, or Compose pixel equality.

Compose 1.7.1 also treats SP line height specially above font scale 1.05: it preserves the requested SP line-height/font-size ratio relative to the resolved physical font size, rather than independently converting the line-height SP value. A caller porting that behavior must account for its source toolkit's converter, custom density and line-height rules before supplying `fontSizePx` and `lineHeightPx`. The environment query alone does not establish Compose layout or pixel parity.

`PodTextEnvironmentServicesTest` exercises isolated `createConfigurationContext` overrides for density and font scale against the platform converter, repeated resource reads, input limits, and main-thread access without changing device settings. These tests establish the platform contract; they are not a Compose parity test.

Inputs are `text`, integer `widthPx`, `fontSizePx`, optional `lineHeightPx` (1–8192 px), `fontWeight` (default 400), `italic` (false), `letterSpacingEm` (0), and `lineBreak` (`paragraph`, `balanced`, or `simple`, default `paragraph`). Dimensions are physical pixels, independent of display density and system font scaling; the caller must use the scale actually used to render its text. When `lineHeightPx` is supplied, line spacing uses proportional distribution with first-line top and last-line bottom trimming, including the terminal empty-line adjustment. The Java span is adapted from the Apache-2.0 [AndroidX Compose 1.7.1 source](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.7.1/ui-text-android-1.7.1-sources.jar), with its license retained. The system default Typeface is used unless a local font is selected as described below. Automatic hyphenation is not represented by this API.

The service builds Android StaticLayout on the service worker, with subpixel text, fallback font line spacing, no font padding, first-strong LTR text direction, no hyphenation, and high-quality, balanced or simple break strategy. Cancellation prevents reply delivery, with cancellation checks before and after layout and during line extraction. Android's layout builder itself is not interruptible. Inputs are limited to 262144 UTF-16 units, width 1–16384 px, size 1–1024 px, weight 1–1000, and letter spacing -1–10 em. Out-of-range and non-numeric dimensions return `invalid_argument`.

`PodTextLayoutServicesTest` compares the production implementation to 288 captured layouts from WatchRSS's production Compose DetailTextBlock and DetailTitle on OWW242 (Android 11). The fixture records source hashes and capture provenance. It covers Chinese punctuation, mixed Latin text, hard newlines, emoji and combining marks, two widths, two sizes, three line-height multipliers, three source presets, empty text, a single line and terminal blank lines. An additional 36 cases exercise the production title formatter and bold title style. The device test compares every line end, visible end, top, bottom and baseline. This is device-specific layout evidence; it does not establish pixel identity, all fonts, all locales, automatic hyphenation, justification, or other Android versions.


`getFontMetrics(slot)` from `@podjs/framework/watch` reads the loaded font atlas through the runtime. Its cell dimensions, baseline and line height are logical pixels; `rasterDensity` describes bitmap coverage resolution and must not be applied to those dimensions again. It returns undefined for an unloaded slot or an older host. A reader can align a native paragraph baseline with the actual baked atlas baseline while keeping source text, line boxes and highlight geometry consistent.


## Fractional layout and glyph coordinates

Android and Wear hosts enable `Ui::set_fractional_layout(true)` and `Ui::set_subpixel_glyphs_supported(true)`. Taffy and layout readback retain fractional logical coordinates. `GLYPH_RUN_F32` (op 13) carries four float cell-axis components and three words per glyph (float x, float y, glyph ID). Other hosts keep the previous defaults and wire formats.

The software sampler scales the float coordinates before coverage sampling. Damage tracking rounds the cell bounds outward. Android decodes one parallelogram per glyph, retains its float vertices in Vulkan push constants (mode 6), and samples with two cell coordinates. This avoids rounding twice and avoids overlap along a split glyph diagonal. The old integer paths remain available.

Regression evidence: logical (10.5, 14.5) renders at physical (21, 29) at 2x density, while the default legacy path still renders at (22, 30). Incremental and full redraws agree for fractional and negative glyph cells. The independent PocketJS commit passes 148 core tests; the integrated tree passes 150. Android decoder/push packing tests pass under address/undefined-behavior sanitizers. This establishes coordinate preservation, not identity of the font outlines or all native UI pixels.

PocketJS draft PR: https://github.com/gfhdhytghd/pocketjs/pull/2 (stacked on the edge-clipping fix).


## Application font resources and atlas v4

`resources.fontSlots` in pod.config.json accepts explicit slots 33–63 with
`px`, `path`, optional `bold`, and optional `rasterAdvances`. Paths resolve
against the application project. The compiler includes these fonts even
when fontSlot is selected dynamically; its cache incorporates resource
settings and file contents. Existing regular/bold resources and slot indices
remain unchanged. Font size may be fractional, for example 21.7 logical px.

When `rasterAdvances` is true, the baker rounds font advances in physical
pixels and divides by raster density. Atlas v4 uses a 12-byte cmap entry:
the original eight bytes followed by an f32 logical advance. Coverage starts
after this expanded cmap. The core keeps v2/v3 behavior and rejects invalid
or truncated v4 entries. The new assets require the matching runtime;
package the bundle and runtime together.

This provides additional bundled font families and size profiles. It does
not add runtime font imports, shaping, or pair kerning. WatchRSS's OWW242
reader profiles match ten of twelve sampled native string widths; the two
Latin phrase samples differ by one physical pixel. Static profiles are not
a universal system-font selection mechanism across Android devices.


## System color text lines

Android watch and Wear OS expose `text.raster`. `textRaster.line(options)` from `@podjs/framework/services` returns a cancellable operation producing `{path,width,height,contentWidth,contentHeight,carets}`. Input is a single line (`text`, at most 4096 UTF-16 units, no CR/LF), `widthPx` 1–1024, `heightPx` 1–1024, `baselinePx` within the content height, `fontSizePx` 1–1024, and optional `fontWeight` 1–1000, `italic`, `underline`, `strikethrough`, `letterSpacingEm` -1–10, `colorArgb`, and `align` left/center/right. Dimensions and carets are physical pixels. The caller supplies an already wrapped line.

Android uses the selected local or default system Typeface, StaticLayout and color-font fallback, clipping to the content rectangle. Output is a power-of-two, straight RGBA8888 file under private `text/` storage, bounded to 4 MiB. Carets contain one primary horizontal position per UTF-16 boundary. Direct service consumers must delete delivered files after reading them. Resource-owning consumers should drain started operations and delete their result; cancelling after the native worker has queued a reply can otherwise lose ownership of its path.

`SystemTextLine` from `@podjs/framework/text` accepts `options`, `pixelScale`, optional fallback JSX and `onCarets`. It preserves a text accessibility label, hides its backing image from accessibility, reads the temporary file in bounded chunks, uploads a texture and removes the file. Matching live requests share a ref-counted texture; the final release skips queued work or frees the texture; already-started jobs drain their result and remove the temporary file even after release. At most two raster jobs are active. Load errors keep fallback content visible. Carets supplied to the component callback are divided by pixelScale.

`systemTextResourceCounts()` retains its `{active, queued, cached}` shape. For readiness checks, use the read-only `systemTextResourceStatus()` from the same module. It returns a new deeply frozen snapshot with those counts plus `pending`, `ready`, and `failed` counts of currently cached entries. An entry is ready only after upload and successful `setImage` plus ready-callback delivery to **every current listener**. A failed late subscriber makes a shared entry failed until that subscriber is released; other subscribers can still receive the texture. Zero active jobs alone also occurs after a failed load and does not prove readiness.

`status.entries` contains anonymous runtime-local IDs, `status`, `stage`, `refs`, `uploaded`, listener-state counts, and `failureStages`. It exposes no text, cache keys, file paths, or error messages. `status.totals.succeeded` counts entries that have reached ready at least once, `delivered` counts successful listener deliveries, and `failed` counts failed non-cancelled operations. `totals.failuresByStage` separates `raster` (including invalid raster metadata), `read` (including invalid pixel data), `upload`, `set-image`, and `ready-callback`. These totals only increase within a JS runtime; there is no reset API. Cancellation, including late rejected operations after release, is not a failure. Cleanup may keep `active` nonzero briefly after an entry becomes ready.

For a stage expected to display at least one system text line, sample a baseline before mounting it, then check the following while its consumers are still mounted:

```ts
import {systemTextResourceStatus} from '@podjs/framework/text';
const baseline = systemTextResourceStatus().totals.failed;
// Later, after mounting and while waiting for rendering resources:
const status = systemTextResourceStatus();
const settled = status.cached > 0 && status.ready === status.cached
  && status.pending === 0 && status.failed === 0
  && status.active === 0 && status.queued === 0
  && status.totals.failed === baseline;
```

This establishes texture preparation and delivery, not visibility, clipping, displayed pixels, or visual equivalence to another renderer. For a clean-run check, also require the baseline failure count to be zero.

WatchRSS device evidence covers Chinese plus emoji, ZWJ profession, flags, skin tones, transparent texture padding, bounds validation and cancellation (two runtime cases), plus five application regressions and one production Compose reference capture. SDK lifecycle tests verify shared upload and final release. This is not full font, RTL, grapheme hit-testing or native pixel-identity qualification; local font selection has the separate device evidence described below.

### Local font files

`textLayout.layout`, `textLayout.measure`, and `textRaster.line` share optional `fontPath`, `fontFaceIndex` and `variationSettings`. `fontPath` is relative to application files; traversal, absolute paths and symlinks escaping that root are rejected. Missing or invalid font files and invalid variation axes fall back to the system typeface with the requested weight and italic style. A 32-entry native cache keys by canonical path, file length and modification time, collection face, weight, italic and variation settings. Replacing a file invalidates its cached typeface using the same file identity rules as the native reader.

Alternatively, `fontAsset` selects an original font packaged through `resources.assets`, with the same face and variation options. It is mutually exclusive with `fontPath` and loads from Android's `pod-assets/` namespace. See [packaged project assets](packaged-assets.md) for configuration, naming rules, cache behavior, and current verification limits.

`textLayout.measure` returns `{widthPx}` for text with the same font, size, weight, italic and spacing inputs used to render it; it uses the existing `text.layout` capability. Measurements have no file side effects and can be cancelled. `SystemTextLine.options.fontRevision` is an optional guest texture-cache revision for a resource update, independently of native file metadata. Text decoration flags use Android's font-aware underline and strikethrough drawing. Tall content line boxes up to 1024 physical pixels allow large font-size/line-height combinations; a texture remains bounded to 1024 × 1024 and two active loads.

### Native preview spacing and image coordinates

`lineHeightMode: "spacing"` applies Android `setLineSpacing` using the requested height minus natural font metrics; the default `"span"` retains Compose line-height behavior. `fallbackLineSpacing` defaults to true and can be disabled for the native preview profile. These are different native rendering profiles, not interchangeable line-height algorithms.

Android watch and Wear OS also enable `Ui::set_subpixel_images_supported(true)`. Axis-aligned image quads use `TEX_QUAD_F32` (op 14): texture ID, four float rectangle bounds, four float UV bounds, and color. Other hosts retain the integer default. Software rasterization and damage tracking handle fractional bounds, while Android uses the existing parallelogram shader with float geometry. This prevents line textures from snapping to logical integer coordinates at 2x density. Bundle and runtime must be deployed together.
