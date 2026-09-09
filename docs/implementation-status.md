# Implementation status

This repository is an executable framework baseline, not a claim that all v1
acceptance gates have passed.

## Capability-plan audit — 2026-09-08

The original requirements remain those in
[the watch capability plan](plan-watch-platform-capabilities.md#summary).
The following is a fresh source-level gap audit, not a four-device acceptance
report. Earlier progress entries describe incremental components and must not be
read as proving that a watch target exposes an end-to-end service.

| Required boundary | Inspected current source/evidence | Unfinished work |
| --- | --- | --- |
| ABI, manifest and public API | `packages/framework/src/targets.ts` declares ABI 2/minimum ABI 1 and the seven capability names; default framework/background/accessibility tests and `cargo test --workspace` pass via `bun run test` | Verify the whole new capability set through each native host, not just exported method names |
| Android/Wear watch synchronization | Shared runtime dispatches state/message/file operations and guest events, with native pairing/LAN/BLE controls and foreground lifecycle. Android/Wear profiles now advertise the three generic sync capabilities. OWW242 normal MainActivity/native boot creates the installed owner, runs guest state set/get and opens settings without owner injection | Verify BLE permissions/scanning/connection UI, physical phone/watch operation and actual Wear OS host; generic capability exposure does not imply Wear Data Layer support |
| Harmony watch synchronization | Normal watch `entry/.../pages/Index.ets` now composes state/message/file services, events and the foreground authenticated connection owner. Native settings provide app-scoped pairing records, QR invitation with bilateral approval and BLE responder connection. Fresh file imports/registration have recovery markers and terminal source cleanup. Updated Harmony TS suite: 349 tests / 4790 assertions; native cross-process import/registration recovery tests and DevEco HAP compilation pass | Capability remains off. Verify normal guest boot, real phone/watch pairing and all channels, round-screen input/reader/crown behavior, background cleanup and import pause latency. Active discovery, authenticated IP/link upgrade and optional transport mapping remain incomplete; older unmanaged source snapshots are not automatically reclaimed |
| Apple phone SDK and watch sync | `platforms/apple-companion` contains a leased app/device client, durable LWW state/message journals, authenticated four-channel routing, incoming consent/replay, descriptor-based import with coordinated peer registration, shared quotas/IDs, profile limits and outgoing request steps. Guest publication uses shared IO gating, verified no-overwrite copying and cooperative cancellation. Incoming methods/events, state get/set/delete and a bounded worker queue connect through a borrowed-runtime poll/post adapter. 93 Swift 6/Linux tests and all 55 Rust runtime library tests pass, including real QuickJS offer/consent/save bytes, event backpressure, native capability queries and cross-app storage rejection. watchOS host source uses the IO gate but does not instantiate this sync adapter | Complete normal watchOS integration, mixed/outgoing/message guest services, state events and authenticated synchronize waiters, trusted manifest/profile wiring, system file authorization, secure pairing, transports, iOS example and watch adapter. Apple SDK compilation/linking and device acceptance are unverified; Linux tests do not compile the watchOS host branch or prove OS phone/watch transport |
| Native optional transports and upgrades | Android RFCOMM/BLE/LAN and Harmony BLE/distributed/TLS components exist | Components are not evidence of Wear Data Layer preference/fallback, WatchConnectivity mappings or authenticated link switching with confirmed-progress preservation |
| Local notifications and scheduled handlers | Android `PodServices` dispatches local notification/background methods. Harmony has native reminder/Work Scheduler handlers composed into the page | Complete and verify capability/preflight exposure for the actual device; Apple service implementation is still missing; system execution/cold-start events must be proven on devices |
| Remote notifications | No native remote registration implementation was found by inspecting the platform registration entry points; public method declarations do not register a token | Native registration/unregistration, token update, real push arrival and payload/open/action tests for every required target |
| Accessibility | Semantic/native bridge source and tests exist; the freshly rerun default suite includes semantic tests | Physical TalkBack/VoiceOver/Harmony reader interaction and complete primary actions remain unverified; do not infer acceptance from golden output |
| Three phone examples | Android and Harmony examples exist; Harmony now has pairing, connection, state, message and file screens with method tests and a compiled unsigned HAP | iOS example is missing; verify real phone/watch offline edits, merges, message handling, file interruption/restart and recovery |
| Four-device acceptance | Rechecked 2026-09-09: `adb devices -l` lists one OWW242 Android watch; DevEco host HDC reports `[Empty]`. Device presence only proves connectivity | All four platform sync/notification/background/reader scenarios and the specified 10-minute stability/power observation remain open |

Immediate implementation priority is the missing Apple SDK/host path and real
host/device verification of the newly wired Harmony services. The watchOS Swift
package still contains rendering/accessibility components without a sync effect
pump or WatchConnectivity integration. The new Apple session package is not yet
the full iOS SDK and no iOS companion example exists. Keep unsupported capabilities unadvertised until their host boundary is
actually implemented. Android/Wear profiles now expose generic state/message/file
sync through their shared runtime. Apple/Harmony sync exposure and remote
notifications remain incomplete; generic Android exposure is not four-device acceptance.

Android integration preparation: the durable owner and bounded foreground driver
now live in the runtime module as `PodSyncClient` and `PodSyncForeground`.
`PodCompanion` and `PodForegroundSync` retain their phone-facing names as thin
source-compatible facades, so the watch does not need a dependency on the phone
SDK. This extraction does not itself dispatch guest sync services or enable a
capability. Rebuild SDK consumers together: inherited nested session/listener
types now belong to the runtime owner, so this is not a binary compatibility
promise for previously compiled Java clients.

The initial service adapter copies approved grants rather than accepting guest
identity/capability arguments. Its state calls and routing were exercised through
the real `PodServices` dispatcher on OWW242 (2 instrumentation tests, no failures).
Cancellation before work, immutable grants, explicit JSON null, persisted
tombstones and missing connection rejection were checked. The normal watch boot
now instantiates the installed owner after native package validation. A separate
sync-apk fixture exercises actual MainActivity/native boot, guest state requests,
and the cold-start settings intent on OWW242 without injecting an owner. Its first
run exposed an old prebuilt runtime revision; rebuilding both Android ABIs with
the repository script resolved the mismatch without relaxing package validation.

Message send now maps the public JSON/TTL/priority/caller-ID contract to the shared
outbox using stable JSON encoding and atomic TTL retry intents. This permits
offline enqueue without a network delegate. Message ACK/events and active-run
wake-up now exist and were exercised through authenticated in-process peers;
native watch host controls now exist; actual paired physical-device operation
remains unverified.
OWW242 outbox/service tests passed 9 cases, including ACK-then-restart retry and
transaction failure. This does not replace two-device message delivery acceptance.

Current local verification: `bun run test` passed, including 40 Rust tests;
Harmony-specific suite separately passed 268 tests/4,277 assertions before this
documentation-only audit. These results do not cover the missing host adapters
or physical acceptance gates above.

| Milestone | Current evidence | Remaining gate |
| --- | --- | --- |
| Feasibility / Android | OWW242 API 30 real device loads the signed bundle, starts QuickJS, emits a 6,464-word DrawList, incrementally rasterizes at density 2 and presents through Vulkan with text, gradients, rounded geometry and clipping intact | physical crown matrix and sustained frame/power gates |
| Feasibility / Wear OS | ARMv7/ARM64 APK builds with the shared Vulkan AAR and rotary adapter | emulator launch/input run |
| Feasibility / watchOS | Xcode 27 on an Apple Silicon Mac cross-builds Rust/QuickJS, packages device/simulator slices as an XCFramework, passes three SpriteKit/parser tests on an Apple Watch simulator, and launches a signed Hero build on a Series 9 running watchOS 27 | touch and Digital Crown input run |
| Feasibility / HarmonyOS | DevEco 26 on Windows cross-builds ARM64 Rust/QuickJS; the compiled N-API host validates and evaluates embedded assets, owns the XComponent NativeWindow lifecycle, accepts touch/frame callbacks and emits an unsigned HAP | configure signing, build with the HarmonyOS 6.1 wearable SDK and run on WATCH 5 |
| Runtime Alpha | C ABI, package preflight, 240x240 metrics, frame-boundary events and deterministic hash snapshots have native tests | four-host tape parity |
| Renderer Alpha | Android damage-rasterizes the canonical DrawList to density-2 RGBA and transfers changed frames through the Vulkan swapchain; the DevEco-compiled Harmony EGL/GLES3 path clears, scales RECT commands and swaps the XComponent surface; watchOS uses the same canonical RGBA path and submits it through SpriteKit only when its content hash changes | run GLES on WATCH 5; full DrawList on GLES and establish screenshot goldens |
| Framework Beta | Solid API, capabilities, rotary, lifecycle/theme, KV, haptics and Android HTTP bridge exist | four-host error recovery and storage/network integration tests |
| v1 | not reached | device matrix, 60-second frame gate and 10-minute stability/power baseline |

Platform source that has not been compiled by its native SDK must not be used as
release evidence. A successful APK/HAP/XCFramework transfer is likewise not a
physical interaction or performance acceptance result.

The current HarmonyOS build proof used SDK/API 26 and a connected API 26 phone
emulator. It validates the project schema and native toolchain only: the emulator
is not a wearable, the HAP is unsigned, and none of those facts satisfy the
HarmonyOS 6.1 / HUAWEI WATCH 5 device gate.
An install attempt on that emulator was rejected with bundle-manager code
`9568320` (`no signature file`); no unsigned-install bypass was applied.

The current watchOS proof used Xcode 26.6, watchOS SDK/runtime 26.5 and the
Apple Watch SE (3rd generation, 40 mm) arm64 simulator. The device archive
contains both watchOS 11-compatible `arm64_32` and newer `arm64` slices. The
separate physical-device evidence is recorded below.

The latest signed physical run used Xcode 27 beta and an Apple Watch Series 9
running watchOS 27. CoreDevice installed and launched
`com.wilflin.podjs.watchgallery` 0.1.0 (7), the guest reported `runtime ready`,
and a CoreDevice screenshot confirmed the 1,000-row Hero/gallery UI, density-2
baked Chinese glyphs, rounded geometry, colors and clipping on the physical
display ([captured evidence](evidence/watchos-series9-hero.png)). This proves
startup and the static first frame; touch, Digital Crown,
60-second frame pacing and 10-minute stability are still separate gates.
