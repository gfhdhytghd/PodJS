# Reader memory attribution (K12)

K12 investigates the reported same-PID reader NativeHeap increase after K11.
Host ownership metrics are not Android PSS, GPU performance or device acceptance.
No device commands are run by wrss-c. Device acceptance remains **PENDING**.

## Sampling contract

Android `debug.memoryStats` returns a demand-only JSON snapshot. The SDK exposes
`debugMemoryStats()` in `@podjs/framework/services` and `@podjs/framework/debug-memory`.
Debug/PERF_TRACE view-owned hosts also write one `PodJSMem` JSON log line after
at least one second between completed samples. Release hosts reject the method;
no periodic callback, bitmap counter object, heap scan or log is scheduled there.
There is no new frame hook or allocator/GC policy in the production runtime.
The pre-existing `PERF_TRACE` constant in `PodServices` is the explicit source
opt-in for a non-debug profiling build.

Native/QuickJS and renderer data are read on the serialized UI host lane.
Paragraph cache data are read on the serial text worker, followed by Java totals
on main. `nativeAtMs`, `textAtMs`, `sampleAtMs`, `nativeCollectMs`, PID and native frame identify
this skew; this is not an atomic process-wide snapshot. Samples do not wait for
GPU fences, run QuickJS/Java GC or trim allocators. Busy text work does not queue
an unbounded history of automatic samples: the next is scheduled after delivery.

The app installs the numeric SDK registry hook only after its normal
`application.buildInfo().debugBuild` gate returns true. Hosts without this hook
return `sdk: null`; older PocketJS packages omit unavailable tree/frame gauges.
No text, cache keys, file paths, article URLs, credentials or bitmap content are
included by the installed hook.

## Fields and accounting boundaries

- `native.quickjs`: actual `JS_ComputeMemoryUsage` through rquickjs; malloc/used
  bytes and counts, objects, strings, atoms, shapes, functions/code and arrays.
  QuickJS strings/closures and native Rust vectors belong to different owners.
- `native.surface`: live nodes/textures, node/texture slot counts and capacities,
  node arena bytes, node payload capacities, live texture bytes/allocated vector
  capacity, loaded font bitmap bytes. Free slots remain reusable high-water
  storage; their count alone is not a live-node leak. Payload capacity currently
  covers text/children/property/animation arrays, not Taffy or accessibility data.
- `native.glyph`: K10 cell count/bytes, source/seed resource bytes, hit/miss totals
  and the last externally pinned cell. Source and seed gauges can refer to the
  same mmap resource pages; do not add them to mapped bytes or treat them as
  extra heap allocations. GPU pages are under `renderer`.
- `native.runtime`: mapped pak bytes, draw/accessibility buffer capacities,
  bridge event/effect counts and payload bytes, host network command/completion
  queues. These are ownership gauges, not a census of all Rust allocations.
- `native.sdk`: SystemTextLine live entries/refs/listeners/textures, logical
  texture bytes, text/paragraph/key/caret units, queue slots; pending service promise table,
  watch listener sets, renderer retained/sweep/root/texture/sprite registries and
  frame callback count. Queued cancelled entries are visible in `queueSlots`.
- `renderer`: live and retired Vulkan texture counts/pixel bytes/allocation
  requirement bytes; both K11 slot staging capacities/allocation bytes, clip and
  raster allocations, in-flight slots/image refs, snapshot vector capacity;
  unique CPU image buffers/capacities across decoder, glyph pages and both slots.
  Shared CPU snapshots are deduplicated by backing-buffer identity. Glyph page
  bytes overlap unique CPU bytes. Vulkan allocation requirements exclude driver,
  swapchain, descriptor and command allocator overhead. Retired bytes stay live
  until their referencing slot fences complete; sampling never collects them.
- `pakMapping`: file bytes, mapped length and `mincore` resident page bytes.
  `mincoreBackingResidentBytes` measures backing-page RAM residency, which can
  exceed the process RSS when pages are not in its page tables. `/proc/self/smaps`
  also supplies intersecting `vmaBytes`, `vmaRssBytes`, `vmaPssBytes`, `exactVma`.
  Missing metrics are `null`. Non-exact/merged VMAs include bytes outside pak;
  `exactVma` must be true before calling the RSS/PSS gauges pak-only metrics.
- `hostBuffers`: CPU framebuffer/media scratch vector capacities.
- `text`: Java-owned PodParagraphLayout.Cache entries/UTF16 units/build total
  (32 entries, 262144-unit ceiling) and owned text-raster bitmap created/recycled/
  live/peak counts and allocation bytes. Bitmap counters exist only on debug or
  PERF_TRACE service lifetimes, and recycle is paired in `finally`.
- `java`: `Debug.getNativeHeapAllocatedSize/getNativeHeapSize`, Java heap used/
  capacity, live service requests, idle image decoder count. `allProcessBitmaps`
  is explicitly `null`: Android provides no public process-wide bitmap census
  here. Text-raster bitmap counts are real and scoped; they are not a fabricated
  total for media/system/driver bitmaps. Network request count is top-level.

Bytes from these categories must not be blindly summed. Live resources,
resource content, vector capacity, mmap residency, Vulkan allocations and
allocator totals have different semantics and several overlap.

## Offline probe

The new `reader-memory` Rust example loads a real, validated app pak with mmap,
then runs the production PodRuntime, QuickJS Guest, Rust retained UI surface,
real Solid/ReaderBody/SystemTextLine and real texture upload/release storage.
The companion `reader-scroll.ts --runtime=quickjs` builds a probe entry without
profiling wrappers or a per-frame guest history. The captured task04 starts and
250 ms swipes match the existing offline bench; controller calls replace native
pointer input. The probe writes samples outside the guest heap once per virtual
second, each round end, after full-article sweeps and after disposal/60 s idle.

Android paragraph shaping, Java bitmap raster/image decoding and Vulkan are
absent. Paragraph line breaks/metrics, text/image RGBA pixels and caret results
are explicitly synthetic. The default synthetic `text.layout` service exercises
production full-paragraph attachments and cache keys; `--paragraph-layout=0`
replays baked-font fallback instead.
Class styles resolve to zero while dynamic properties/layout use the real Rust
core. This bounds what a successful replay can establish. It cannot reproduce
Java cache/StaticLayout/Minikin or Vulkan driver allocation behavior.


## Observed offline curve (no leak repair claimed)

The archived reader fixture is **407**, not the device's 411. HTML SHA-256:
`e345b7f90348991be3878cc1e0dd7d3660211dbc831b99bff6529b4e25b6fa82`.
The source/bundle has no per-frame profiling arrays. Actual scalar receipts are
in [memory-attribution-data.json](memory-attribution-data.json); full 1-second
samples are `.codex-tmp/k12-memory/paragraph-20.jsonl` in the app main checkout.

| Stage | QJS used MiB | Linux allocator in-use MiB | Nodes | Text entries | Surface texture MiB |
|---|---:|---:|---:|---:|---:|
| reader-before | 3.60 | 17.59 | 79 | 8 | 1.12 |
| round-end 1 | 3.65 | 18.98 | 124 | 13 | 2.12 |
| round-end 5 | 4.23 | 19.82 | 124 | 13 | 2.12 |
| round-end 9 | 4.81 | 20.66 | 124 | 13 | 2.12 |
| round-end 10 | 3.07 | 18.14 | 124 | 13 | 2.12 |
| round-end 15 | 3.80 | 19.19 | 124 | 13 | 2.12 |
| round-end 20 | 4.52 | 20.24 | 124 | 13 | 2.12 |
| full-sweep 1 | 5.66 | 26.57 | 271 | 32 | 5.12 |
| full-sweep 2 | 5.47 | 26.30 | 271 | 32 | 5.12 |
| full-sweep 3 | 5.47 | 26.30 | 271 | 32 | 5.12 |
| full-sweep 4 | 5.47 | 26.30 | 271 | 32 | 5.12 |
| full-sweep 5 | 6.38 | 27.62 | 271 | 32 | 5.12 |
| after-stop | 5.07 | 21.61 | 1 | 0 | 1.00 |
| after-idle-60s | 5.07 | 21.61 | 1 | 0 | 1.00 |

Twenty task04-equivalent rounds retain 124 live nodes/13 text entries at round
ends. Node arena capacity stays 512. Paragraph-reference units/key units are
465/8864 at those points; pending services and cancelled queue slots are zero.
QJS used memory grows between releases, then drops at round 10; it does not grow
monotonically across all 20 rounds. Five whole-article traversals end with
271 nodes/32 text entries and reuse the same glyph cells (950/1468456 bytes).
Dispose returns to one native root, zero text entries/refs/text textures,
zero frame callbacks and zero pending services; 60 virtual seconds idle add
no QJS growth. The remaining 1 MiB surface textures are the reader's existing
image-cache resources, not SystemTextLine refs. QJS residual bytes are not a
reachability proof: sampling does not force cycle collection. The underlying
native allocator/PSS on Android is still unmeasured.

Baked-font fallback (paragraph-layout=0) was also exercised for five rounds and
five complete sweeps, with the same cleanup invariants. Neither replay recreates
the reported Android NativeHeap increase. **No allocation/GC/cache policy fix
has been made on speculation.** This delivers attribution and a reproducible
host probe; Android root-cause repair remains open.

## Reproduce on Linux (no device)

Use the task app/framework worktrees with their package dependencies linked to
the task framework. Build the real package before running the probe. The
production `--project` command below is the candidate-framework build; the
LEAD_BRIEF main-checkout CLI command was also verified separately.

```bash
app=/home/wilf/data/OPPO_Develop/PodJS_WatchRSS.wt-c
fw=/home/wilf/data/PodJS/podjs-watch.wt-c
out=/home/wilf/data/OPPO_Develop/PodJS_WatchRSS/.codex-tmp/k12-memory
mkdir -p "$out"
bun "$fw/packages/cli/src/cli.ts" build --target=android-watch --project="$app"
bun "$app/scripts/bench/reader-scroll.ts" --runtime=quickjs --framework="$fw" --out="$out/reader.js"
cargo run --quiet --manifest-path "$fw/Cargo.toml" -p podjs-runtime --example reader-memory -- \
  --bundle="$out/reader.js" \
  --html=/home/wilf/data/OPPO_Develop/PodJS_WatchRSS/.codex-tmp/k9-profile/article.html \
  --timing=/home/wilf/data/OPPO_Develop/PodJS_WatchRSS/.codex-tmp/device/t04-scroll-timing.json \
  --package="$fw/dist/projects/com.lightningstudio.watchrss.podjs/android-watch" \
  --rounds=20 --out="$out/replay.jsonl"
```

The source pak/integrity is checked; only the probe entry/hash/capabilities are
substituted in a transient manifest. The real runtime is destroyed before its
pak mmap is unmapped, including on failure. Synthetic RGBA files use a private
host temp directory and are consumed/deleted; there is no formal/native app
store and no production backend. `--paragraph-layout=0`, `--sweep=0` and
`--rounds=1..100` are explicit probe controls, not production settings.

## Device owner handoff — wrss-perf only (PENDING)

Only wrss-perf/lead may execute the following. A reviewed debug APK must include
**all three task branches**: app debug-hook wiring, framework/Java/JNI, PocketJS
counts/QuickJS API. Check the current device brief first. The lead must supply
an explicit audited APK path and installation instruction. Any formal upgrade
uses `scripts/device/acceptance.py upgrade --serial e3aca8af --apk <reviewed-apk>
--out <fresh-directory>` and requires the protected before/after `equal=true`
receipt. Do not uninstall/clear data or create full userdata tar snapshots.
No APK has been produced or installed by K12.

After the authorized upgrade, start the app normally and keep one PID from
home through reader/rounds/home-idle. Use article 411 or the original task04
long article, record its hashed identity and starting offset, and leave preset,
account and source data unchanged. Capture the same normal-log and meminfo
samples with the following Bash commands (run from the app main checkout):

```bash
k12_dir=$(mktemp -d .codex-tmp/device/k12-memory.XXXXXX)
k12_package=com.lightningstudio.watchrss.podjs
k12_pid=$(adb -s e3aca8af shell pidof "$k12_package" | tr -d '\r')
[[ "$k12_pid" =~ ^[0-9]+$ ]] || exit 1
adb -s e3aca8af logcat -T 1 --pid="$k12_pid" -v epoch -s PodJSMem:I PodJSPerf:I '*:S' > "$k12_dir/memory.log" &
k12_logcat_pid=$!
k12_mark() {
  [[ "$(adb -s e3aca8af shell pidof "$k12_package" | tr -d '\r')" == "$k12_pid" ]] || return 1
  adb -s e3aca8af shell date '+%s.%N' > "$k12_dir/$1-device-time.txt"
  adb -s e3aca8af shell dumpsys meminfo "$k12_pid" > "$k12_dir/$1-meminfo.txt"
}
k12_mark home-before
```

1. After home stabilizes, record `home-before`; open the selected long article,
   wait five seconds, record `reader-before`. Confirm `PodJSMem` lines have the
   intended PID; `native.sdk.systemText` and tree/frame gauges must be present,
   otherwise SDK/host package identities do not match the candidate.
2. Repeat the existing device-owned task04 helper five times without leaving
   the reader. It uses 250 ms alternate swipes / 0.6 s after the ADB command for
   a 15 s target. It clears logcat at each start; keep the external stream running
   and mark immediately before/after each round, outside its timing window:

   ```bash
   for k12_round in 1 2 3 4 5; do
     k12_mark "r${k12_round}-before" || break
     python3 .codex-tmp/device/s4-scroll-task04.py "k12-r${k12_round}"
     k12_mark "r${k12_round}-after" || break
   done
   ```

3. If ownership gauges still increase at round 5, continue to 20 using the
   identical timeline and article/offset, unless interrupted by a system popup
   or disconnect. Do not mix a changed PID into the curve. Navigate back via the
   ordinary app path, mark `home-after-reader`, then after 15 and 60 seconds mark
   `home-idle-15s` / `home-idle-60s`; collect logs throughout, then `kill
   "$k12_logcat_pid"` / `wait "$k12_logcat_pid"` on the host. No GC/allocator trim,
   app force-stop, data reset or route replacement occurs between these samples.
4. Run compiled instrumentation only in the approved test package/runner:
   `adb -s e3aca8af shell am instrument -w -e class
   dev.podjs.runtime.PodMemoryStatsTest <approved-test-package>/androidx.test.runner.AndroidJUnitRunner`.
   The two tests use the test target's own files and never formal/native data.
   This is device PENDING; wrss-c only compiled the test sources.

For each marked phase, extract the nearest earlier and later `PodJSMem` sample
alongside NativeHeap PSS/Heap Alloc/Graphics from that phase's original meminfo.
Include PID, frame, both sample clocks, text-lane delay and `nativeCollectMs`.
This is a diagnostic run with sampling enabled; its frame rates must not be
compared as a clean performance acceptance run without measuring tracing cost.

Interpret growing owners before choosing a fix:

- QJS malloc/used/objects/strings + text key/paragraph units or SDK listeners:
  inspect Solid owner disposal, callback tables and paragraph retention. Sawtooth
  drops with stable live registries distinguish automatic collection from a
  monotonically retained registry, but do not certify reachability.
- Surface live textures/bytes or SDK refs rising after stable repeated visits:
  inspect texture ownership/cancellation before touching cache budgets.
- Text paragraph entries/units above their bounds or repeated builds with
  unchanged semantic parameters: capture sanitized key attributes/hit reasons,
  not full article text; inspect Java layout/Typeface key stability.
- Vulkan retired bytes growing despite slot reuse: inspect fence ownership.
  Staging capacities/clip/raster stay high-water by design; CPU/glyph page metrics
  overlap and must not be summed. Check `inFlightSlots<=2`.
- NativeHeap Alloc growing while these owners plateau: the current census has
  not attributed that memory. Next investigate Android StaticLayout/Minikin,
  font/typeface and driver/native allocator ownership with the matching process.
  Do not label it a QuickJS leak or “fixed” based only on cache/texture gauges.
