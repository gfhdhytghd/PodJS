# Process memory budget

A watch project opts in with `"memoryBudgetMb": 128` in `pod.config.json`.
The unit is decimal MB: this produces `memoryBudgetBytes: 128000000` in
`pod.manifest.json`. The Android acceptance metric is **TOTAL PSS <=125000 KiB**
from `dumpsys meminfo`. Java heap size, RSS, disk cache size, and allocator
resident counters are different metrics. Omission preserves legacy policy.

Android installs one budget owner from the Application, before any foreground
or background QuickJS realm. Reservations are serialized across realms and
threads. A reservation precedes the covered allocation; commit follows success;
cancel returns an unallocated promise; release follows the final actual owner.
Rendered CPU textures remain charged while immutable snapshots hold them.
Vulkan textures remain charged through their retirement fence.

The PSS sampler starts a generation before reading memory. `Debug.getMemoryInfo`
can omit protected graphics on real devices. The process reader combines the
current local PSS with the positive difference between system-server graphics
and locally reported graphics, obtained through
`ActivityManager.getProcessMemoryInfo` for the process's own PID. It never adds
the complete graphics total twice, or substitutes cached native/Java heaps for
the current local CPU sample. System queries are coalesced at five seconds;
Android Q+ may cache them further. Diagnostics expose the local reading,
graphics correction, query age and cache warning. A failed protected query is
not accepted as a successful uncorrected sample: admission retains the last
ledger observation and requests trim/retry. This feedback is an estimate with a
potentially stale protected component; independent `dumpsys` scene gates remain
mandatory, particularly where AVD graphics accounting differs from hardware.
See [Debug](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/os/Debug.java)
and [ActivityManager](https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int[])).

Resident
allocations committed at or after that start contribute their cohort's live
high-water mark, including freed blocks until a later PSS observation. Reusable
scratch contributes a peak rather than a cumulative sum for every frame.
Allocator retention beyond the logical peak still requires headroom and PSS
feedback; the ledger is not a physical-page census. Earlier
residents are already included in the observation. Pending reservations and a
safety margin are added. Freeing an observed allocation does not subtract its
bytes from PSS, because the allocator or driver may retain its pages.

The current category targets, scaled for other declared budgets, are QuickJS
32 MB, image 6 MB, Vulkan 16 MB, media 32 MB, CPU scratch 6 MB, and text raster
3 MB. Foreground QuickJS may borrow unused global capacity beyond its advisory
32 MB target; the other targets remain admission ceilings. Background realms
share a 28 MB QuickJS ceiling, leaving 4 MB for foreground allocations. They
share one process admission check and are never summed as observed PSS.
Small critical QuickJS/render allocations use 4 MB reserve headroom;
optional allocations use 12 MB. The PSS floor before the first sample is 45 MB.
These are initial policies, not a kernel-enforced process limit. System/driver
memory outside these allocation paths still requires scene acceptance tests.

Pressure notifications contain only `t: "memory.pressure"` and numeric `level`
0..3. SDK image and text histories trim in priority order, and low quality images
retry after pressure recovery. Java drops idle image decoders, paragraph caches,
and non-transaction SQLite page caches. Renderer pause drains submitted work
before releasing caches. No cleanup deletes persistent user content, changes
credentials, resets a traffic stop, or terminates the process. Automatic QuickJS
collection thresholds are 24 MB for the foreground and at most 12 MB for a
background realm. Valid host pressure notifications collect unreachable cycles
at a serialized boundary before later service results enter the guest, never
inside an allocator callback. Reachable roots and queued events stay intact.
Background work declines admission or
unwinds for a durable retry when the process cannot preserve foreground space.

Binary service attachments reserve their copied Rust payload before allocation.
The same QuickJS owner charge moves from the handle table to an external
ArrayBuffer, and returns only on native drop, detach, collection or failed
wrapper construction. Taking an attachment does not release its charge.
Snapshots use the shared 28 MB lower-priority JS lane and 12 MB global headroom,
leaving room for foreground JS wrapper/control allocations. Empty payloads do
not consume byte quota; the existing slot/size limits and no-replay byte IO
contract remain intact. The process-global injector test for retained buffers
is explicitly run alone; its ignored marker does not replace that run.

`PodMediaMemoryBudget` defines typed `tryReserve`, `commit`, `release`, and owner
events. Its owner identity includes a generation, resource kind and priority.
A released event must follow actual resource destruction. Cancellation is not
release. `PodMediaMemoryAccounting` binds to the reviewed codec gate before any
codec is created. A separate byte-admission callback reserves the same immutable
Allocation before construction; its lifecycle listener keeps it pending through construction, commits on the
created acknowledgement, and
returns it only on the gate's physical-release acknowledgement. Observers never
veto admission, and the three-video-slot concurrency policy is unchanged.

Video codec estimates include aligned YUV output and fixed overhead; unknown
formats use 6 decimal MB. Audio adapters reserve 1 MB without a video slot.
These are conservative envelopes, not measurements of driver bytes. Surfaces,
compressed buffers and cached keyframes keep separate reservations. Budgeted
Media3 buffers target 1 MiB with time-over-size disabled; keyframe readback has a
preallocation scratch reservation. No lastFrame copy exists. Resource pressure
requests reclamation through the gate's main-lane owner registry, protecting
CURRENT and promoted owners. Optional byte refusals finish that prewarm with
a transient scheduling result instead of spinning a retry. A denial delivered
after promotion preserves the selected player. The existing transient admission
retry remains in charge of CURRENT codec recovery. Media quotas and actual PSS still require local-video
scene acceptance. Numeric estimated bytes by kind appear in shell diagnostics;
they must never be added to observed PSS again.

Acceptance uses non-debuggable release APKs without heap tracing. Record the
ABI, actual screen contents, cold home, long text, image scrolling, local mock
video, rapid scrolling, background, repeated navigation, and transient maxima.
An empty/error page is not a low-memory success. heapprofd and owner counters
are attribution tools, not replacements for TOTAL PSS. Hardware acceptance
requires the target hardware owner; an x86_64 AVD does not prove arm64 PSS.

The 128 MB profile uses a 32 decimal MB media ceiling after the offline 480x854
release measurement: the initial 22 MB ceiling refused a second 7.505 MB video
codec plus its independent surface/buffer promises while process PSS remained
below 80,000 KiB. This is a shared-process ceiling, not an additional reservation
on top of PSS. Budgeted keyframe caches retain at most three entries. Optional
media-domain quota misses do not emit process-wide allocation pressure; global
PSS admission still runs for every actual reservation.
