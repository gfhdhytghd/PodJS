# Android first-frame startup

The official Android and WearOS hosts run package admission on the takeover worker.
An admitted process caches the resolved data paths. `PodRuntimeView` creates its
service adapters, then waits for the actual TextureView surface and viewport.
The boot worker creates the Rust runtime (QuickJS realm, UI, filesystem and KV),
creates Vulkan resources, maps and validates the package, mounts the host, evaluates
the guest, and prepares/presents frame zero. The main looper takes ownership,
enqueues the installed background/sync approvals, applies theme/lifecycle, pumps
results and registers the first Choreographer callback. Texture updates and main
callbacks have separate first-frame timings.

## Removed startup work

- When boot finishes with the same SurfaceTexture, equal width/height and a valid
  renderer, retain the swapchain and frame zero. Previously the main handoff
  rebuilt them after `vkDeviceWaitIdle`. Replacement surfaces, changed dimensions,
  detached/stale generations and renderer recovery retain their original paths.
  The renderer's out-of-date recovery still calls `replaceWindow` unconditionally.
- Allocate/zero the CPU RGBA buffer only when GPU submission first falls back to
  CPU rasterization. Keep the original raster size, transparent incremental raster
  API and RGBA submit. This does not defer Vulkan staging/upload resources.
- Official host Applications provide WorkManager's default configuration, and
  their manifests remove only `WorkManagerInitializer` metadata. The startup
  provider and ProfileInstaller remain. WorkManager initializes on its first
  `getInstance(Context)` call, normally on the existing scheduler IO worker after
  guest validation. The runtime AAR does not disable initialization for consumers.
  This follows Android's [on-demand initialization contract](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration).

No startup resource or integrity check is skipped. Pak/bytecode mapping, receipt
validation, host mount, guest evaluation and generation ownership are unchanged.
No guest/eval parallelism is added: runtime viewport and Vulkan attachment still
use the actual surface; error order and single-owner QuickJS access stay intact.

## Optional startup font prefetch

`pod.config.json` may set `resources.preloadFontSlots` to at most eight unique
slot IDs from 0 through 63. The CLI forwards this hint into the package manifest.
Admission rejects malformed hints and slots without a packaged font resource.
After package validation, the existing font worker begins entry SHA-256 validation
and atlas parsing before guest bytecode evaluation. The first actual font use
consumes a completed worker result before falling back to the existing synchronous
load. The hint does not skip resource integrity checks or change required fonts.
Omitting it retains the existing loading path.

WatchRSS prefetches slots 2, 1, 19, and 22 for its startup overlay and visible home
cards. Startup savings depend on worker completion and contention; the entry hash
statistics include this work and must not be added to the enclosing boot duration.

## PodJSPerf timing fields

All durations use monotonic clocks. Startup logs contain timing/state fields only.

| Log | Meaning |
| --- | --- |
| `startup stage=application processToOnCreateMs` | Process start through entry to Application.onCreate, including providers and framework setup; not pure Application CPU time |
| `applicationOnCreate` | Application superclass onCreate plus its startup marker |
| `activityOnCreate` | Activity entry through return, including native library load and admission view setup; asynchronous admission is separate |
| `admissionQueueWait`, `admissionWorker`, `admissionMainQueueWait` | Worker queue, admission resolution/progress, then dispatch to main; `admission cached=true` denotes the existing cache fast path |
| `activityStartRuntime` | Android host view construction, state restore, intent capture and setContentView |
| `libraryLoad`, `servicesCreate`, `viewCreate` | Library load and adapter/view constructor bodies; viewCreate excludes TextureView superclass/field initialization |
| `surfaceReady viewToSurfaceMs` | View field timestamp through TextureView surface readiness |
| `bootQueueWait`, `nativeCreate`, `nativeBootAssets`, `finishBootQueueWait` | Worker queue, native create, asset/eval/frame-zero work, and dispatch to main |
| `create windowConfigMs/runtimeMs/scrollConfigMs/vulkanMs` | JNI input/window setup, complete Rust create (realm/UI/FS/KV together), scroll/geometry configuration, Vulkan construction; total ends after renderer construction |
| `create deferredRgbaBytes` | CPU fallback bytes whose allocation was deferred; not Vulkan allocation savings |
| `vulkanCreate stage=instance/device/uploadResources/clipResources/graphicsResources/surfaceSwapchain` | Constructor phases, success/failure logged in original short-circuit order |
| `finishBootResize resize=false/true` | Surface handoff decision and elapsed time, including readiness check |
| `backgroundEnqueue`, `themeLifecycle`, `firstServiceResults`, `finishBoot` | Main handoff components; backgroundEnqueue measures enqueue only |
| `workManagerAcquire` | Scheduler getInstance duration, including lazy initialization if this call is first |
| `cpuFallbackAllocate` | First CPU fallback buffer allocation/zero duration and bytes |

The existing `boot`, `bytecode`, `firstMainFrameMs`, and `firstFrame` logs remain.
Mount timing is part of the existing bytecode log. Nested timings overlap: do not
add their values to their enclosing total. Worker and main events can overlap.
`firstMainFrameMs` is a renderer callback, not an assertion that the home screen
has finished loading.

## Validation boundaries

`BootSurfaceTest` covers equal surface/geometry, equal-size replacement, width and
height changes, and invalid-renderer recovery. `PodStartupTest` checks the actual
host Application/manifest and WorkManager's first worker acquisition in a fixture
package, refusing the formal package. Its compile success does not prove runtime
initialization, scheduled-job persistence or device surface recovery.

Device acceptance must compare cold-process runs against the pre-change host,
include installed-job recovery, and exercise background/resume, surface replacement
and dimension changes. Report actual medians and maxima. The static removal of
work does not establish a fixed millisecond saving on a watch.
