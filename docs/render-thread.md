# Bounded Android rendering

PodJS Android watch hosts execute Vulkan rendering on a dedicated native thread.
This is a framework capability: it applies to arbitrary PodJS applications and
requires no guest API, route, content type, or application-specific configuration.
Choreographer continues to drive the guest on Android's main thread. FIFO acquire,
frame fences, upload preparation, command recording, queue submission, and present
run on the renderer thread. A blocking WSI call delays that thread's consumer,
while the guest lane can continue processing input and producing newer snapshots.

## Immutable handoff

After `pod_runtime_frame` and `pod_runtime_snapshot`, the main host lane calls
`pod_runtime_pin_snapshot`. The additive C API copies canonical draw words and
pins core texture revisions and font atlases through reference-counted ownership.
Texture updates use copy-on-write only while a snapshot pins an older version;
free/reuse retains the generation-tagged handle semantics. Fonts and their
thread-safe glyph providers remain alive independently of font-slot replacement.
Static font replacements also advance the snapshot page identity and output hash,
even when geometry and virtual glyph ids stay the same.
No retained UI tree, JS guest, mutable runtime pointer, JNI environment, or Java
object crosses the handoff. Capture belongs to the serialized runtime lane.

`PodRenderSnapshot` is exclusively owned by the pending frame, then its consumer.
The renderer-local glyph accessor retains an immutable coverage cell until its
next glyph call. It uses the existing synchronized glyph cache/provider, which
performs rasterization outside its cache lock. Decode's glyph page growth remains
copy-on-write. Its page LRU pins the current decode and both K11 submitted slots.
Slot CPU image snapshots, immutable descriptors and retired texture versions stay
alive until their own fences, exactly as in the two-slot pipeline.

The snapshot owns resource references, not caller-owned mapped package storage.
When using the mapped PAK API, its backing bytes must outlive every snapshot as
well as the runtime. The Android host enforces this by joining the renderer before
destroying the runtime and finally unmapping its `MappedAsset`. Copied PAK resources
have their existing owned storage. Other C API hosts must obey the same extended
mapping lifetime. Snapshot destruction requires exclusive ownership.

If GPU decode, clip/texture capacity or another recoverable submission stage fails,
the renderer executes the existing premultiplied transparent reference software
rasterizer against **the same** draw/resource snapshot, then submits RGBA on its
own lane. This fallback uses full-frame rasterization, because damage history
belongs to a sequence that can now skip/rewrite pending frames. It never calls a
runtime renderer or rasterizes on Android's main thread. The framebuffer is
allocated only on fallback. An invalid renderer stops accepting further frames.

## Queue and backpressure

The queue has exactly one pending frame, in addition to the currently executing
frame and the two submitted Vulkan slots. A handoff exchanges unique ownership
under a short mutex; the worker never holds that mutex during decode, Vulkan calls,
resource destruction or CPU rasterization. If pending is occupied, the new complete
snapshot replaces it. The discarded snapshot releases its revision/font pins
immediately after leaving the lock. Started work and submitted slots are never
modified or canceled by a producer. There is no frame FIFO that grows with lag,
no producer wait for present, and no unrestricted present loop.

Capture/copy time is reported as `drawList`; `handoffMs` covers queue publication,
replacement destruction and notification. The target is handoff <=1 ms. This is a
latency target, not a scheduler or allocation worst-case guarantee: snapshot pin
counts, final resource destructors and OS scheduling must be measured on each
host. Hash gating avoids repeatedly enqueueing unchanged accepted snapshots.
Submission failures invalidate the accepted hash so a later guest tick can retry.

## Lifecycle and ownership

All Java/JNI host entry points remain serialized on the Android main lane.
The renderer is constructed, used and destroyed exclusively on its worker lane.
Surface controls retain their incoming `ANativeWindow` until the worker finishes
the operation; Vulkan retains the installed window until surface destruction.

A resize or detach first stops accepting frames and discards the pending snapshot.
A prioritized control message waits for executing CPU/Vulkan/present work to return.
Only then does the worker run the existing device-idle drain, retire the two slots,
destroy presentation objects, replace the native window and rebuild synchronization
objects/swapchain. The caller receives acknowledgment before Java releases its
SurfaceTexture. A detached/invalid surface accepts no frames; reattach forces
redraw even if draw words are unchanged. Lifecycle barriers can block awaiting
WSI, as required to avoid releasing surfaces that the driver still references;
ordinary Choreographer handoff never waits for that barrier.

Pause cancels pending work, waits for executing work, drains submitted slots and
retires pins. Resume re-enables acceptance only with an attached valid surface. Both the
producer accepted hash and the worker presentation hash are invalidated at the
pause/resume barrier, so the next unchanged guest snapshot is actually submitted
rather than acknowledged and skipped. This also applies to the initial lifecycle
control after background boot.
Acquired-image abort, OUT_OF_DATE, SURFACE_LOST and present error recovery execute
on the worker and retain K11's semaphore consumption/rebuild ordering. FIFO and
per-image render-finished semaphores remain unchanged. Shutdown stops acceptance,
drops pending pins, completes executing work, drains/destroys Vulkan on its owner,
acknowledges, signals exit and joins the worker. The host then destroys runtime and
mapped resources. There is no detached thread or background use of a freed host.

Opt-in renderer tracing and demand-only memory reads use acknowledged owner-lane
controls; these debug calls can wait for the currently executing frame and are
outside steady-state handoff. They preserve pending frames. Guest and renderer
trace buffers are separate, serialized on their respective lanes and allocated
only when tracing is requested. End stops both recorders, merges rows by monotonic
start time, and respects the requested bounded capacity with explicit dropped-row
counts. Schema 1 remains; HostFrame flags add bit 18 for renderer execution and
bit 19 for a main-lane queued snapshot, while bits 16/17 mark actual GPU/CPU submits.
Host frame sequence joins the asynchronous lanes; it remains monotonic across
trace sessions.

## Frame statistics

The existing `frameWindow` fields remain available. `nativeTotal` now measures
main-lane input, guest, layout, capture and handoff; it excludes asynchronous
rendering. Fence/acquire/decode/upload/record/submit/present distributions and
`slotReuses`/`maxInFlight` count completed renderer submissions harvested at the
next callback, independently of host tick counts. Multiple completed submissions
can enter one callback's window. `draws` reports successful submissions, while
ticks and dropped remain Choreographer cadence metrics. Neither proves physical
GPU/display FPS. An empty window has zero renderer-stage samples.

Appended fields are `handoffMs=p50/p95/max`, `queueReplaced` (discarded pending
snapshots in the window), and `rendererLagFrames` (maximum host sequence distance
from the last completed renderer frame). Stable idle output can have a large lag
without pending work; interpret it with draws and replacements. Slow main frame
logs include `rendererSeq` for their latest harvested GPU values; do not pair those
GPU stages with the log's current JS `seq`. Opt-in `PodJSRenderer` DEBUG rows use
the original submission sequence and include `rendererTotalMs` and stage times.
They are CPU wall times, not GPU timestamps or display completion times.

Completion history is bounded to four records; normal production can complete at
most executing+pending between producer polls. A host that invokes repeated
non-frame debug controls without polling can truncate this diagnostic history;
no rendering work or resources are queued by these records. Snapshots can retain
resource versions from executing/pending frames as well as the two Vulkan slots;
measure process memory in addition to cache-owned byte counts.

## Validation and integration

`bash scripts/test-frame-pipeline.sh` compiles the actual renderer thread and
Vulkan renderer against a host synchronization model. Present can be gated while
the producer replaces many frames. Tests check single-pending capacity, immediate
discard-pin release, lifecycle acknowledgment ordering, window ownership, recovery,
worker-only CPU fallback and shutdown. Existing fence/descriptor/image/byte tests
continue to cover K11's in-flight slots and revision budgets. The script accepts
`PODJS_TEST_CXXFLAGS='-fsanitize=thread -g -O1'` for ThreadSanitizer.

Rust tests exercise the real snapshot C API, byte-exact transparent CPU output,
texture update/free/reuse and destruction of runtime before worker consumption.
Core tests cover zero-copy pixel pinning and old-font retention after replacement.
The normal framework suite, no_std core check and both Android native ABI builds
remain required. This is host/model/build proof only. Integrators still need real
WSI, visible glyph/image correctness, cold start, resize/rotation, pause/resume,
foreground/background, surface loss and sustained frame cadence validation on
their supported devices. A smaller main-lane present time alone does not establish
higher throughput, lower input latency or successful device acceptance.
