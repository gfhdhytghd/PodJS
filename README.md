# PodJS

PodJS is a watch-first host layer for PocketJS. One bundled Solid TSX app runs
inside the PocketJS QuickJS guest and targets four native watch shells:

- `android-watch` — Android 11/API 30+, Vulkan 1.1, ARMv7 and ARM64
- `wearos-watch` — Wear OS API 30+, Vulkan 1.1
- `watchos-watch` — watchOS 11+, WatchKit/SpriteKit
- `harmonyos-watch` — HarmonyOS 6.1 smart wearable, ArkUI/XComponent/GLES3

The checked-in target profiles and gallery use 240×240 as their portable design
baseline. At runtime each native host derives its logical render surface from
the physical display and raster density, then reports the resulting dimensions,
shape and safe insets through `@podjs/framework`. Application code adapts from
those metrics and capabilities, never target names.

The production runtime evaluates only the JS and pak embedded in the signed
application package. Debug deployment may replace those assets over ADB or HDC.

## Bootstrap and your first app

See [Getting started](docs/getting-started.md) for prerequisites, all public
framework exports, debug/release packaging and signing configuration.

```sh
bun install
(cd vendor/pocketjs && bun install)
bun scripts/link-pocket.ts
bun run pod -- create /absolute/path/my-watch --app-id dev.example.mywatch --name "My Watch"
cd /absolute/path/my-watch
bun install
bun run typecheck
bun run test
bun run build
bun run pod -- package --target=android-watch
```

The scaffold contains a black round-screen-safe list/detail app with animated
Back navigation, file dependencies to this framework and sample tests. The
standalone [Watch Notebook example](examples/watch-notebook/README.md) demonstrates
windowed rows, image/cache, scroll restoration, watch actions, startup tracing,
logging and KV without WatchRSS or takeover. After dependency bootstrap, run
`bun run test:scaffold` from the framework root for offline generated-app and
example install/typecheck/test/build checks. `bun run test` runs framework tests.
Build/package commands do not install to a device.

Android packaging emits an ARMv7/ARM64 debug APK, release AAB and reusable
AAR. Wear OS uses the same runtime/AAR with a separate manifest and rotary
shell. watchOS packaging is run on the configured Xcode 16+ Mac; use
`scripts/build-watchos-runtime.sh` to build the device/simulator XCFramework
and its Swift Package release archive. The checked-in `PodJSWatchApp.xcodeproj`
produces the standalone, Watch-only application bundle. On a Windows or macOS
DevEco host, `pod package --target=harmonyos-watch` uses the checked-in platform
script to cross-build Rust/QuickJS, embed the bundle assets and emit an unsigned
HAP. On macOS, DevEco Studio is discovered in
`/Applications/DevEco-Studio.app`; set `DEVECO_STUDIO_HOME` to the application's
`Contents` directory for a custom installation. The HAP still needs a DevEco
signing configuration before it can be installed on a physical watch.

## Implemented baseline

- Pinned PocketJS submodule, Solid TSX compiler path and four target profiles.
- Graphics-independent Rust/QuickJS runtime with a versioned C ABI, 60 Hz
  frame transaction, touch/rotary/lifecycle/theme delivery, HTTP host commands,
  bounded per-app KV/files, demand rendering and resource revisions.
- Fail-closed package manifest validation before JavaScript execution. Target,
  host ABI, capability list, PocketJS revision and bundle/pak hashes are checked.
- Android/Wear AAR/JNI with dual ARM ABIs, API 30-compatible asset loading,
  lifecycle/surface recovery, haptics/network bridge and Vulkan 1.1 swapchain.
- watchOS Swift Package/SpriteKit host validated with a linked Rust/QuickJS
  XCFramework on an Apple Watch simulator and with signed startup plus a static
  rendered frame on a physical Series 9. Touch and Digital Crown bridging and
  UI tests are present; physical interaction remains a separate acceptance gate.
- HarmonyOS Stage/XComponent/N-API/GLES3 source tree validated by its native
  build host.
- A 1,000-row Solid component/performance gallery and contract tests.

The Android renderer incrementally rasterizes the canonical DrawList at density
2, uploads only changed frames and scales them into the Vulkan swapchain. This preserves
font atlases, textures, triangles, gradients, alpha and clipping while keeping
presentation and surface recovery native. Direct per-operation Vulkan pipelines
remain optional performance work rather than a visual-correctness dependency.

See [architecture.md](docs/architecture.md) for the runtime and host contract.
