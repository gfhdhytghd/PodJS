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
| `@podjs/framework/services` | Cancellable native operations for files, network, input, media, text, crypto and platform services; images.decodeOperation/decodeRegionOperation retain source dimensions and cancellation. |
| `@podjs/framework/acked-state-vector` | Bilateral applied receipts for opaque per-domain roots; frozen windows and final-session ACK publication. |
| `@podjs/framework/directory-witness` | Small immutable-resource directory metadata witnesses; missing native change tokens disable certification. |
| `@podjs/framework/json-delta-journal` | Checksummed local JSON map deltas, complete-frame commits and torn-tail recovery; not authentication or a remote ACK. |
| `@podjs/framework/work-receipts` | Caller-scoped bounded receipts for deterministic work; persistence and authentication belong to the caller, and receipts are never remote ACKs. |
| `@podjs/framework/digest-cache` | Bounded memory-only memoization of exact small byte digests; checks the caller fence on hits and never retains failures. |
| `@podjs/framework/sync-state` | Versioned companion saved-state clocks, merge and persistence rules. |
| `@podjs/framework/request-policy` | `RequestFilter`, `createRequestAllowlist`: generic request rejection hooks; app owns keys and rejection replies. |
| `@podjs/framework/browser-qr-wait` | `BrowserQrWait`, `browserQrWaitStatus`, `BrowserQrWaitOptions`, `BrowserQrWaitStatus`: configurable QR waiting state independent of page loading; no UI rendering. |
| `@podjs/framework/sync-framing` | Bounded wire frame encoding/decoding for companion transports. |
| `@podjs/framework/sync-messages` | Companion message wire protocol and acknowledgement helpers. |
| `@podjs/framework/platform-services` | Background scheduling, notification and companion/file-transfer service interfaces. |
| `@podjs/framework/work-window` | Cooperative host-unit admission before a deadline, input-size estimates, measured margins and closed phase diagnostics; never races writes against timers. |
| `@podjs/framework/background-handler` | Typed headless background-handler entry and delivery contracts. |
| `@podjs/framework/debug-memory` | Scoped diagnostic resource ownership/accounting; counts are not native heap measurements. |
| `@podjs/framework/text` | Text components and watch font/layout integration. |
| `@podjs/framework/text-layout` | Cancellable sliced layout preparation and native paragraph measurement. |
| `@podjs/framework/text-wrap` | Pure compatibility wrapping/line-breaking helpers. |
| `@podjs/framework/media-surface` | Host-backed image/video surface components and lifecycle. |
| `@podjs/framework/media-policy` | Session-local playback recovery budgets, prepare deadlines and source-expiry policy. |
| `@podjs/framework/crypto` | Length-prefixed binary framing, hex, bounded decimal SAS, host HMAC-SHA256 (32-byte keys) and P-256/P1363 signature verification. |
| `@podjs/framework/bytes` | Portable UTF-8, byte and Base64 codecs. |
| `@podjs/framework/async-bytes` | Chunked/yielding byte codecs for bounded guest work. |
| `@podjs/framework/watch-ui` | Progress, round-safe geometry (QR layout, top blocks), swipe action cards and confirmation dialogs. |
| `@podjs/framework/restorable-stack` | Pure bounded saved/retained stack envelopes, valid-prefix restoration and per-instance route IDs; route codecs/root/entity policy stay with the caller. |
| `@podjs/framework/navigation` | BackMotion, ScrollRestoration, activity/route state, bounded restorable stacks with caller codecs, instance route identities and automatic scrolling. |
| `@podjs/framework/windowed-list` | Keyed bounded visible-row selection and indexed header/body/footer sources. |
| `@podjs/framework/keyed-memo` | Caller-owned memoized values with explicit dependency identity and clearing. |
| `@podjs/framework/image` | File-backed RemoteImage, bounded decoder queue, texture leases and image fitting. |
| `@podjs/framework/service-trace` | Optional service timing and work diagnostics. |
| `@podjs/framework/render-checkpoint` | Opt-in numeric Android render checkpoints, snapshot/submit timings and bounded UI texture alpha counts; not a display fence. |
| `@podjs/framework/render` | Cancellable `afterPaint` boundary before expensive guest continuation work; explicit bounded fallback. |
| `@podjs/framework/html` | Generic HTML parsing, entities and text extraction. |
| `@podjs/framework/html-slices` | Incremental/cancellable HTML fragment parsing. |
| `@podjs/framework/xml` | Generic XML parsing and document navigation. |
| `@podjs/framework/json-raw` | Bounded immutable JSON token spans; patch known fields retaining unknown bytes. `canonicalIntegerJson` provides RFC 8785 JCS with exact nonnegative safe integer token validation for signed protocols; never use it to rewrite unknown extensions. |
| `@podjs/framework/record-tuples` | Compact ordered JSON values for derived indexes; unknown names retained, original records remain authoritative |
| `@podjs/framework/json` | Bounded JSON projection without materializing unused fields. |
| `@podjs/framework/json-numbers` | JSON parsing that keeps lexical number tokens (`1` vs `1.0`). |
| `@podjs/framework/text-compat` | Compatibility text policies including `kotlinTrim` (JVM whitespace), kept independent of application labels. |
| `@podjs/framework/splash` | Android system/AndroidX splash hold and release; bounded Activity lifetime. |
| `@podjs/framework/startup` | JS phase tracing, content-ready gates and startup exit interpolation. |
| `@podjs/framework/startup-overlay` | Startup overlay that stays until its exit frame finishes while content mounts. |
| `@podjs/framework/log-file` | Bounded UTF-8 host log files with caller paths, atomic hash/commit replacement and injected or default file ports. |
| `@podjs/framework/logging` | Configurable redaction and serialized persistent FileLogger with caller-owned storage ports. |
| `@podjs/framework/async` | SerialWriteQueue, cancellable bounded final-write waits and target-identity exit coalescing. |
| `@podjs/framework/editing` | Pure editing/hit-test helpers for text input. |
| `@podjs/framework/restorable-input` | Restore native input state with explicit application ownership. |
| `@podjs/framework/qr` | QR encoding and generic QR geometry/data helpers. |
| `@podjs/framework/image-preview` | Image preview geometry and interaction helpers. |
| `@podjs/framework/image-tiles` | Image tile addressing and bounded preview selection. |
| `@podjs/framework/tiled-image` | Tiled image presentation component. |
| `@podjs/framework/cache` | Generic bounded disk cache, downloads, accounting, eviction and reference leases; configurable default transport download/image-validation limits. |
| `@podjs/framework/download-cache` | Serial whole-file downloads with lazy URL resolution, request headers, cancellation, byte limits, stable content identity and pinned LRU retention. |
| `@podjs/framework/media-prefix-cache` | Bounded range-aware Media3 prefix transport and cancellable windows |
| `@podjs/framework/regexp` | Lazy RegExp construction to defer guest startup work. |
| `@podjs/framework/sql` | Bounded SQL result pagination and UTF-8-safe large text reads. |
| `@podjs/framework/sql-changes` | `onlyChangedSql`: value-change guards for code-authored deterministic UPDATE/UPSERT statements. |
| `@podjs/framework/json-string` | Android-compatible JSON string quoting, including slash escaping and lone UTF-16 preservation. |
| `@podjs/framework/frame-stream` | Opaque signed/unsigned frame facade with caller codecs, error/poison/EOF policies; bounded connections with deadlines, cancellation and progress. Stream-identity read/write ownership prevents duplicate wrappers from stealing or interleaving frames; `FrameLengthError` exposes numeric bounds without body bytes. |
| `@podjs/framework/texture-upload` | Shared raw/sample RGBA uploads and explicit file ownership/release policies; no lifetime scheduling or pixel conversion. |
| `@podjs/framework/variable-list` | Variable-height windowed viewport; opt-in `round` adds minimal safe-circle endpoint space, `ScrollEntry.bounds` describes visible content, and `round.development` warns about impossible rows. |
| `@podjs/framework/round-list` | `roundRowRange`, `roundListInsets`, `roundRowInteractionBounds`, `RoundListOptions`: adaptive circle/viewport fitting with independent visible and clipped interaction bounds; see [round lists](round-lists.md). |
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

Additional guides: [restorable stacks and host files](restorable-stack-and-host-files.md), [gates/volume/response/preview/matching](private-response-preview.md),
[UI/navigation](watch-ui-navigation.md),
[actions](watch-actions.md), [cache](cache.md),
[activity/logging](activity-and-file-logging.md),
[text/list/stream](text-list-and-stream.md),
[QR/images/editing](qr-images-and-editing.md),
[startup/text compatibility](startup-and-text-compat.md),
[service contracts](device-services.md),
[debug external components](debug-external.md).

Round lists opt in with `round: {width, height}` using full display dimensions,
not the reduced height below a header. For a displaced viewport, also provide
`viewport: {x, y, width}`; `height` on `VariableList` remains its clipping height.
Whitespace is added to the existing vertical padding without changing row keys,
indices, gestures or numeric scroll offsets. `ref` exposes a `VariableListHandle`
with `roundInsets()`; subtract its `start` from screen-to-content hit coordinates
and add it to independently positioned row overlays. A row's optional `bounds` is its
visible rectangle relative to the padded row origin; omit it to fit the whole row.
Set `round.development` from your host debug flag to warn about rectangles that
cannot fit at any scroll position; split or narrow those rows in your UI.
`round: null` opts out and `fixed` lists retain their supplied geometry. An
external `controller` must include the added insets in its own maximum offset;
keep round fitting disabled if that controller owns document/text anchors.


### Opaque frames and texture ownership

Use `createFrameFacade<T, Work>` from `@podjs/framework/frame-stream` for four-byte big-endian frames. Supply synchronous/asynchronous body codecs and `maxFrameBytes`; the facade does not interpret the payload. `prefix` defaults to `signed` (`unsigned` is optional). Customize `errors` with factories. Incremental `FrameDecoder.push()` returns complete decoded bodies. By default push errors and truncated `finish()` poison the decoder, and a successful `finish()` permanently closes it. Set `poisonOnFinishError:false` to resume an incomplete frame, `closeOnFinish:false` to allow later frames, or `poisonOnPushError:false` to discard a bad frame and permit a fresh one. Async calls forward the caller's work context unchanged. This entry is pure and permitted in headless background handlers.

Use `createTextureUploader` from `@podjs/framework/texture-upload` when a resource owner needs the same low-level upload across text or images. `uploadRgba(bytes,width,height,{psm:3,sample:1})` passes the same bytes to optional sampled upload; only nullish results fall back to regular upload. Omit sample for regular RGBA. `uploadFile(path,width,height,{take,unavailable})` calls `take` before the host invocation; only null triggers optional `unavailable`. A negative handle or thrown error leaves ownership with the invoked host. The caller controls whether file upload is available and where cleanup happens. Call `release(handle)` at the resource owner's existing lifetime boundary. Optional ports (`ops`, `uploadFile`, `release`) let owners inject/defer release without changing pixels or adding implicit cleanup. This host-backed entry is not allowed in headless handlers. Existing text/image/tiled-image owners share it while retaining their queues, cancellation, file deletion and cache policies.

`quoteAndroidJsonString` provides Android JSONObject string escaping. `sha256Bytes` from the bytes entry optionally hashes at most 256 KiB with the native bridge and returns null when unavailable, so callers retain their fallback. Async byte codecs batch at most 16 bounded pieces or 8 ms before yielding, checking cancellation between pieces. Frame connections expose optional `setFrameTiming` with numeric read/decode/encode/write milliseconds and byte counts; observers cannot affect transport behavior.

`androidJsonQuotedByteLength` reports exact Android quoted UTF-8 size, using a bounded native counter when available and the portable quote/UTF-8 fallback otherwise. Large inputs split at 32768 UTF-16 units without splitting a surrogate pair. The native counter rejects inputs over 256 KiB. Legacy BLE discovery requests both coarse and fine location as one location group; RFCOMM permission remains separate.

`gunzipBytes` is an optional bounded native decoder (256 KiB compressed and decompressed). It validates members/CRC and returns null for invalid/oversized/unsupported input so callers retain their existing larger-input and error paths. It performs no I/O and retains no guest buffer.

For bounded Media3 streaming previews, import `mediaPrefixCache` from
`@podjs/framework/media-prefix-cache`; see [cache.md](cache.md). Opaque registered
URIs require a fresh process-local registration and still need the network for
uncached ranges.


### Render checkpoints

`renderCheckpoint()` returns a process-local numeric ID when a host accepts the
`diagnostic.renderCheckpoint` effect. Call it at a logical UI change and retain
that ID with the application's timing record. Hosts without this diagnostic sink
remain inert. Android records receipt, the most recent snapshot at receipt, the
next eight snapshots/submissions and up to eight UI TextureView updates within
two seconds. Host and renderer records include monotonic times, frame sequence
and content hash; a recent snapshot at receipt can precede a service-only guest
turn, so subsequent snapshots bound that turn's committed scene.

`renderCheckpoint({sampleAlpha:true})` additionally samples the first two updated
UI textures into a 16x16 bitmap and logs only transparent/partial/opaque counts
in the inner 10x10 region. This optional GPU readback can perturb timing; it does
not export pixels or sample other applications. Checkpoints reset with the guest
lifetime. **Queue submission, vkQueuePresentKHR return and TextureView update
callbacks are not panel scan-out timestamps.** Use independently timed visible
capture when evaluating presentation, and keep its acquisition interval.


`afterPaint()` from `@podjs/framework/render` requires `runtime.timer`. Android
waits for two window frame-commit callbacks, letting an asynchronously submitted
UI texture be consumed before a heavy continuation. The operation returns
`{committed:true}` after those commits; hidden/unattached views and a 250ms timeout
return `{committed:false}`. Cancellation removes the registered commit callback
and timeout. This is a scheduling boundary, not a panel scan-out guarantee, and
does not make subsequent synchronous guest work inexpensive. Keep durability
ordering explicit when placing persistence after this boundary.

### Cached state and directory witnesses

`AckedStateVector` compares both owners' current roots with mirrored durable
receipts, without requiring different platforms to encode content identically.
Call `completed(domain)` only after application and transport ACK succeed, stage
only roots unchanged since summary, and call `applied()` only after the enclosing
session's final applied ACK. Abort discards staged receipts. This is an
optimization proof; it never advances an application's content cursors.
`versionRootText(namespace, domain, epoch, revision)` formats a namespaced,
owner-local monotonic version preimage for an application-provided SHA-256 port.

`kv.fingerprint(key)` obtains the native store's cached SHA-256 of the canonical
raw value without parsing a large object in JS. Missing keys, old hosts and keys
with staged batch writes return `undefined`; callers must not certify unknown
state as equal. The store rereads authoritative bytes under its existing file
lock, including writes made by another handle/process.

Android `fileChunks.list/stat` include an opaque `changeToken` containing inode,
size and nanosecond mtime/ctime. `directoryWitness` hashes these small metadata
records without reading file bodies. It is a cache-validity witness, not a
content checksum: validate missing/changed resources through the normal content
integrity path before acknowledging a completed domain. Old hosts without change
tokens yield no witness. Android network snapshots also expose `localWifi`, which
observes non-default Wi-Fi without requiring internet validation.

`parseRawJson` rejects duplicate decoded keys, invalid Unicode, trailing syntax and
container depth over 32 by default (UTF-8 input limit 16 MiB). `rawJsonProperty`
selects a subtree; `patchRawJsonObject` accepts complete JSON tokens for explicit
properties and copies every untouched key-through-value span verbatim. An
`undefined` edit removes that property. This syntax helper does not authenticate
input or decide application feature/compatibility policy. `parseRawJsonAsync`
uses the same scanner, yielding between bounded pieces; optional `check` and
`yield` ports support cancellation and host scheduling without changing tokens.

`jsonRecordTupleCodec(fields)` encodes ordered object entries as known numeric
indexes or unknown string names. Decode after JSON parsing restores own properties
(including `__proto__`) and rejects duplicate fields/unknown numeric indexes. Keep
ordinals append-only within a cache format. This is a JSON-value codec for derived
metadata, not a substitute for raw-token preservation in authenticated documents.

`querySqlTableInfo(db, names)` batches exact sqlite_master table/view names while
retaining PRAGMA column order and values; unsupported older SQLite uses quoted
individual PRAGMAs. `ProgressDeadline` bounds a single asynchronous I/O operation
by first-byte and positive-byte idle limits; the caller retains its total budget
and closes its transport on timeout. Disarm it after byte input completes, before
long decoding or application work. Directory witnesses batch at most eight file
stat probes and drain each batch on errors; changed/missing tokens still fail.

`secureStorage.compareAndSet(key, expected, value)` atomically replaces one public
secure string only when its current value matches `expected`. `undefined` means
absent for the expectation and deletion for the new value. It returns `false` on
a conflict. Hosts without `secure.compareAndSet` reject the operation; callers
must not fall back to an unconditional write. Android checks and publishes under
the same secure-store process and file locks used by batch updates.

`log.append` is a bounded native append for an application-owned log path. Android
serializes append/rotation with process and file locks, limits a call to 8 KiB,
and retains at most a 256 KiB tail when rotating. Cloud workers may call it with
closed diagnostic fields. Applications own redaction and must not pass secrets.

Headless guests expose existing bounded byte codecs through `__podByteCodecs`,
without mounting `pod`, UI, files, or network surfaces. The framework SHA-256 and
chunk codecs use this pure CPU path; older hosts retain their fallback. SHA-256
input remains limited to 256 KiB. JSON delta journal dictionary values are
immutable: replace an entry when editing it. Complete checksummed frames are
local persistence receipts and never authenticate application data.

Headless native guests run one handler invocation and do not install browser timers or the UI service bridge. `defineBackgroundHandler(handler, {yieldTurn: context => ...})` optionally scopes the pure byte codecs' default scheduler to a caller-owned asynchronous yield port, then restores the previous scheduler on success or failure. The caller checks cancellation/deadlines and uses only its manifest-authorized request route; this option grants no services. Android cloud workers admit the bounded `runtime.delay` timer (1..120000ms) through their closed method set. Keep application host-port literals within that set; the CLI's `host-method-contract` source inspector supports executable-source/Java-set contract tests without importing host services.

`WorkWindow` checks remaining time before each asynchronous unit and closes the
window after any deferral. `windowedHost` applies it to Pod host requests, including
checkpoint ports that otherwise finish after foreground cancellation. Large AEAD
and transform calls use tracked file sizes for admission; subsequent calls also
reserve twice the largest observed stage duration. Estimates are conservative
software policy, not real-time operating-system guarantees. Lease release and
bounded logging can unwind after admission closes. Callers must keep atomic
publication and resumable receipts at the application level. Android hard guest
deadlines retain authorized continuation queues with exponential backoff; only a
completed handler with durable progress earns the one-second continuation. Worker
logcat records only closed stage codes, elapsed time and method counts.
