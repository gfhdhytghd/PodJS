# Getting started with PodJS

PodJS builds standalone watch applications from Solid TSX. The framework provides
watch metrics, input, native services and reusable UI/data modules. The notebook
example is independent of WatchRSS and has its own application ID and KV keys.

## Install the development checkout

Use Bun 1.4+, Rust/Cargo and Git. Clone this repository with submodules (or run
`git submodule update --init --recursive` in an existing clone), then run:

```sh
bun install
(cd vendor/pocketjs && bun install)
bun scripts/link-pocket.ts
```

The last command restores Bun's PocketJS file dependency to a symlink into this
checkout. Keep one PocketJS and Solid instance: copied dependencies can split
host registration and reactive ownership. Run this repair after reinstalling framework dependencies. A working C/C++ toolchain is needed for the Rust QuickJS
bytecode compiler. Building JS/pak/bytecode does not require an Android SDK.
Android native packaging additionally needs Java 17+, Android SDK (set
`ANDROID_SDK_ROOT`), NDK 27.2.12479018 and CMake 3.22.1. Use the checked-in Gradle
wrapper. See [architecture](architecture.md) for other native hosts.

## Create an app

From the framework checkout:

```sh
bun run pod -- create /absolute/path/my-watch --app-id dev.example.mywatch --name "My Watch"
cd /absolute/path/my-watch
bun install
bun run typecheck
bun run test
bun run build
```

`pod create <dir> --app-id <id> --name <name>` requires a new destination; it never
overwrites an existing directory. It generates `pod.config.json`, `package.json`,
`bun.lock`, `tsconfig.json`, source, tests and CLI/install helpers. The initial
app has a black round-screen-safe list, a detail page and an animated Back button
using `BackMotion`. System Back uses the same animation. Selection writes KV.
Use host dimensions and safe insets rather than device names.

Dependencies use `file:` paths to the checkout that generated the project.
`postinstall` links PodJS, PocketJS and Solid to that checkout to preserve their
single runtime instance and source-relative imports. Scripts resolve the project
path themselves, so they work outside the framework directory and with spaces.
If moving either directory, update `package.json` file paths/override,
`scripts/framework.json`, `scripts/pod.ts` and the lockfile, or regenerate.
The framework checkout must retain its installed dependencies.

The generated lockfile reuses the framework's pinned registry resolutions. After
the initial framework dependency bootstrap has warmed Bun's cache, the same
installation can run with `bun install --offline`. A cold machine needs the
initial dependency downloads; the scaffold does not vendor registry packages.
No WatchRSS code, takeover configuration or signing credentials are generated.

## Build and package

```sh
# From the generated app (or use bun run build for this target):
bun run pod -- build --target=android-watch
bun run pod -- package --target=android-watch
```

Build outputs live in the framework's
`dist/projects/<appId>/android-watch/`: `main.js`, `main.pak`, `main.qbc` and
`pod.manifest.json`. `build` packs fonts/assets and compiles QuickJS bytecode.
It is not an APK build. Keep rasterized images at power-of-two dimensions <=512;
32x32 SVG icons rasterize to 64x64 at density 2.

Default Android `package` builds the debug-signed APK, release AAB and reusable
runtime AAR. Debug APK output is
`platforms/android/app/build/outputs/apk/debug/androidApp-debug.apk` in the
framework checkout. Native module output paths are shared between projects, so
copy a package to a project-specific location before packaging another app.
Neither command installs to a device.

For a release APK, configure your own external signing properties:

```json
{
  "android": {
    "signing": { "keystoreProperties": "../private/keystore.properties" }
  }
}
```

This is a placeholder, not a real credential path. Keep the properties file and
keystore outside source control and build assets. The properties file needs
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative
to that file. Supply private values locally; no passwords belong in command
arguments, examples or application code. Then run:

```sh
bun run pod -- package --target=android-watch --release
```

With signing configured, this emits a non-debuggable, zipaligned and verified
APK at `dist/projects/<appId>/packages/android-watch/app-release.apk`. The
current CLI falls back to default debug packaging if `--release` has no signing
configuration: do not treat that output as a signed release. The default release
AAB is not signed by the APK signer. See [Android signing](android-signing.md)
for certificate rotation and packaging details.

## Notebook example and verification

```sh
cd examples/watch-notebook
bun install --offline
bun run typecheck
bun run test
bun run build
# Or, from the framework root, verify both a new temporary app and the example:
bun run test:scaffold
```

The standalone [notebook](../examples/watch-notebook/README.md) demonstrates
windowed-list, cached RemoteImage, ScrollRestoration, WatchConfirmDialog,
SwipeActionCard, startup trace, logging and KV. The smoke script creates an app
in a temporary path with spaces, installs offline, checks dependency identity,
typechecks, runs tests and builds JS/pak/bytecode for both apps. It cleans only its
own temporary app/output. It does not build/install APKs or access a device.
`bun run test` includes generator safety and export-documentation coverage.
These checks establish source/build contracts. Physical touch, Back timing,
image decoding and rendering remain device acceptance work.

## Framework module overview

Every public package export is listed below. Import the narrow module you need.
Native service availability varies by host; check capabilities and handle failure.

| Export | Purpose |
| --- | --- |
| `@podjs/framework` | PocketJS primitives plus watch APIs, targets and windowed rows; Solid mounting also uses `@pocketjs/framework/solid`. |
| `@podjs/framework/watch` | Display metrics, capabilities, lifecycle, Back/rotary events, navigation state, haptics and synchronous KV. |
| `@podjs/framework/testing/bun-runtime` | Isolated Bun test service-pump binding; never import in a production app. |
| `@podjs/framework/targets` | Target profiles, host ABI and capability identifiers. |
| `@podjs/framework/services` | Cancellable native operations for files, network, input, media, text, crypto and platform services. |
| `@podjs/framework/sync-state` | Versioned companion saved-state clocks, merge and persistence rules. |
| `@podjs/framework/request-policy` | `RequestFilter`, `createRequestAllowlist`: generic request rejection hooks; app owns keys and rejection replies. |
| `@podjs/framework/browser-qr-wait` | `BrowserQrWait`, `browserQrWaitStatus`, `BrowserQrWaitOptions`, `BrowserQrWaitStatus`: configurable QR waiting state independent of page loading; no UI rendering. |
| `@podjs/framework/sync-framing` | Bounded wire frame encoding/decoding for companion transports. |
| `@podjs/framework/sync-messages` | Companion message wire protocol and acknowledgement helpers. |
| `@podjs/framework/platform-services` | Background scheduling, notification and companion/file-transfer service interfaces. |
| `@podjs/framework/background-handler` | Typed headless background-handler entry and delivery contracts. |
| `@podjs/framework/debug-memory` | Scoped diagnostic resource ownership/accounting; counts are not native heap measurements. |
| `@podjs/framework/text` | Text components and watch font/layout integration. |
| `@podjs/framework/text-layout` | Cancellable sliced layout preparation and native paragraph measurement. |
| `@podjs/framework/text-wrap` | Pure compatibility wrapping/line-breaking helpers. |
| `@podjs/framework/media-surface` | Host-backed image/video surface components and lifecycle. |
| `@podjs/framework/bytes` | Portable UTF-8, byte and Base64 codecs. |
| `@podjs/framework/async-bytes` | Chunked/yielding byte codecs for bounded guest work. |
| `@podjs/framework/watch-ui` | Progress, round-safe geometry (QR layout, top blocks), swipe action cards and confirmation dialogs. |
| `@podjs/framework/navigation` | BackMotion, ScrollRestoration, activity/route state and automatic scrolling. |
| `@podjs/framework/windowed-list` | Keyed bounded visible-row selection and indexed header/body/footer sources. |
| `@podjs/framework/keyed-memo` | Caller-owned memoized values with explicit dependency identity and clearing. |
| `@podjs/framework/image` | File-backed RemoteImage, bounded decoder queue, texture leases and image fitting. |
| `@podjs/framework/service-trace` | Optional service timing and work diagnostics. |
| `@podjs/framework/html` | Generic HTML parsing, entities and text extraction. |
| `@podjs/framework/html-slices` | Incremental/cancellable HTML fragment parsing. |
| `@podjs/framework/xml` | Generic XML parsing and document navigation. |
| `@podjs/framework/json` | Bounded JSON projection without materializing unused fields. |
| `@podjs/framework/json-numbers` | JSON parsing that keeps lexical number tokens (`1` vs `1.0`). |
| `@podjs/framework/text-compat` | Compatibility text policies including `kotlinTrim` (JVM whitespace), kept independent of application labels. |
| `@podjs/framework/startup` | JS phase tracing, content-ready gates and startup exit interpolation. |
| `@podjs/framework/startup-overlay` | Startup overlay that stays until its exit frame finishes while content mounts. |
| `@podjs/framework/logging` | Configurable redaction and serialized persistent FileLogger with caller-owned storage ports. |
| `@podjs/framework/async` | SerialWriteQueue, cancellable bounded final-write waits and target-identity exit coalescing. |
| `@podjs/framework/editing` | Pure editing/hit-test helpers for text input. |
| `@podjs/framework/restorable-input` | Restore native input state with explicit application ownership. |
| `@podjs/framework/qr` | QR encoding and generic QR geometry/data helpers. |
| `@podjs/framework/image-preview` | Image preview geometry and interaction helpers. |
| `@podjs/framework/image-tiles` | Image tile addressing and bounded preview selection. |
| `@podjs/framework/tiled-image` | Tiled image presentation component. |
| `@podjs/framework/cache` | Generic bounded disk cache, downloads, accounting, eviction and reference leases. |
| `@podjs/framework/regexp` | Lazy RegExp construction to defer guest startup work. |
| `@podjs/framework/sql` | Bounded SQL result pagination and UTF-8-safe large text reads. |
| `@podjs/framework/frame-stream` | Bounded framed byte-stream connections with deadlines, cancellation and transfer progress. |
| `@podjs/framework/variable-list` | Variable-height windowed viewport with caller-owned row data and scroll behavior. |
| `@podjs/framework/text-environment` | Native text environment lookup and font selection helpers. |
| `@podjs/framework/text-carets` | Preserve caret map identity when row positions are unchanged. |
| `@podjs/framework/text-lines` | Pure line breaking, measured titles and UI text layout helpers. |
| `@podjs/framework/text-anchor` | UTF-16 anchors that keep the same text visible across font or width changes. |
| `@podjs/framework/site-challenge` | Bounded HTTP retry after native WebView script-cookie challenges, with matching user-agent cache and cancellation. |
| `@podjs/framework/http-request` | Single-request HTTP parser with a whole-request deadline and bounded buffers. |
| `@podjs/framework/theme-schedule` | Calendar-based local day/night theme policy. |
| `@podjs/framework/telemetry` | Configurable daily cumulative counters, capture upload scheduling and actual active-playback battery samples. |
| `@podjs/framework/disclosure` | Persistent initial acknowledgement and per-route confirmation state, without UI or network access. |
| `@podjs/framework/build-gate` | `FailClosedGate`: once-only asynchronous capability acceptance; caller supplies the predicate. |
| `@podjs/framework/media-volume` | Float crown volume guard and serialized controller; caller supplies the full profile and media ports. |
| `@podjs/framework/private-response` | Owned private-file lifecycle plus raw-byte/strict encoded-chunk readers; caller supplies allocation policy, ports, limits and errors. |
| `@podjs/framework/preview-session` | Session/sequence publication, tail coalescing and expiry; caller supplies data conversion and timeout policy. |
| `@podjs/framework/text-match` | UTF-16 match ranges and measured segment/line geometry, including wrapped matches. |
| `@podjs/framework/round-layout` | Dependency-free round-screen geometry: QR layout, `rectInsideCircle`, `safeTopBlock`. |

Additional guides: [gates/volume/response/preview/matching](private-response-preview.md),
[UI/navigation](watch-ui-navigation.md),
[actions](watch-actions.md), [cache](cache.md),
[activity/logging](activity-and-file-logging.md),
[text/list/stream](text-list-and-stream.md),
[QR/images/editing](qr-images-and-editing.md),
[startup/text compatibility](startup-and-text-compat.md),
[service contracts](device-services.md),
[debug external components](debug-external.md).
