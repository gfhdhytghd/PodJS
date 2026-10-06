# Runtime glyph coverage cache (host ABI 3)

`resources.glyphCache` opts Android Watch and WearOS packages into runtime TTF
coverage cells. Other targets are rejected by the CLI until they have the native
page decoder. Legacy projects still bake the original full atlas and declare ABI
2. The new host accepts ABI 1/2/3; new font packages declare ABI 3 so an older host
rejects the package rather than silently drawing empty fonts.

## Data and integrity

Font v5 stores the original cmap, cell dimensions, offsets, integer advances and
fractional `rasterAdvances` as their original f32 bits, a source SHA-256, and a small
raw seed set. v6 adds individually raw-DEFLATE-compressed seeds. The tofu cell is
always raw; ASCII, explicitly selected characters and optionally AST-scanned
application text use the exact existing baker. Compressed data stays compressed
until one cell is needed. There is a 256 KiB raw seed or 4 MiB compressed payload
limit per slot; single-cell inflate output is bounded to the exact cell length.

The builder adds deduplicated `ui:font-source.<sha256>` entries to the same pak
receipt as font metadata and existing resources. Font metadata is verified before
parsing. Sources are SHA-256 verified once per shared source before TTF parsing or
rasterization. Seeds can draw before reading the TTF because their bytes are part
of the authenticated metadata. Corruption fails closed for that source, and
integrity results feed the existing K6 statistics. Mapped bytes remain owned by
the runtime's pak; read-at/owned paths retain Arc-owned sources. Runtime teardown
cancels and joins the font worker before the pak is unmapped.

## Scheduling and limits

The existing serial font worker parses pending slot metadata, verifies shared
sources and prefetches mounted text glyphs. Its request channel holds at most 8
requests; one warm batch holds at most 1024 glyph requests. A text revision changes
the generation so stale warm batches stop. Only coverage preparation happens on
the worker; Vulkan stays on its existing renderer thread. The frame decoder asks
for immutable Arc-pinned coverage cells through `pod_runtime_glyph`. A miss may
synchronously inflate or rasterize to preserve immediate rendering; this path and
contention with source verification require device timing before a no-regression
claim. Worker prefetch is best-effort and cannot promise every miss is off-thread.

One shared CPU LRU retains at most 8 MiB of decoded one-byte coverage cells, keyed
by immutable font epoch and virtual gid. This separates slot/size/weight and
preserves old frame references when an LRU entry is evicted. Metadata, compressed
seeds, mapped TTF pages and externally pinned cells are additional memory.

Android packs cells into 4x4 RGBA pages, separated by one-pixel replicated edges.
The converted-page LRU retains at most 16 MiB; pages referenced in the current
frame are pinned. A frame that exceeds this budget declines GPU decoding so the
existing software fallback remains available. One finalized page snapshot per
frame avoids a whole-page copy per new glyph; previously returned immutable
snapshots trigger copy-on-write. Older snapshots retained outside the LRU are
additional transient memory. Font epochs prevent stale pages after a font change.

The shader keeps glyph-local UVs and the original fixed-point coverage sampling,
clamps to the original cell and only then adds the page origin. This avoids page
normalization rounding and neighboring-cell bleed. Original draw geometry and
advance/wrap behavior are unchanged. The existing Vulkan cache has a 32 MiB/1024
texture retention budget; staging, in-flight allocations and other textures are
additional memory. CPU/page/GPU limits are retention limits, not a process RSS cap.

`PodJSPerf glyphCache` reports decoded CPU bytes/cells, source/seed bytes,
hits/misses, materialization time (inflate + source verification + raw raster),
and RGBA page bytes/count. `frameWindow` retains existing upload/fence/GPU timing.

## Validation and reproduction

Use the normal framework/app tests and package command with explicit `--project`.
Additional focused checks from the framework checkout:

```sh
CARGO_NET_OFFLINE=true cargo test --manifest-path vendor/pocketjs/engine/core/Cargo.toml --locked
CARGO_NET_OFFLINE=true cargo check --manifest-path vendor/pocketjs/engine/core/Cargo.toml --no-default-features --locked
CARGO_NET_OFFLINE=true cargo test -p pocket-ui-surface --locked
bun test vendor/pocketjs/tests/font-bake.test.ts
CARGO_NET_OFFLINE=true cargo build -p pocket-ui-surface --release --example glyph-cache-check --example glyph-cache-profile --locked
g++ -std=c++20 -O2 -Icrates/podjs-runtime/include platforms/android/runtime/src/main/cpp/gpu_draw_list.cpp platforms/android/runtime/src/main/cpp/vulkan_gpu.cpp platforms/android/runtime/src/main/cpp/gpu_draw_list_test.cpp -o /tmp/glyph-page-test
/tmp/glyph-page-test
```

`glyph-cache-check LEGACY_ATLAS RUNTIME_ATLAS TTF` compares every cmap entry,
f32 advance, cell byte, measurement and wrapping; it traverses every glyph again
after eviction. `glyph-cache-profile legacy|runtime OLD NEW TTF OLD_SHA NEW_SHA
[quintuples...]` runs a separate Linux process, authenticates metadata, loads all
slots and measures first seed, first unseeded glyph with source hashing, new CJK
cells, hot cells and RSS/HWM. It intentionally loads all slots and is a font-only
benchmark, not an app cold-start or watch-memory benchmark. Its p95 values are
per-run samples; repeated-run summaries must label the median of p95 values.

The WatchRSS K10 report records the complete 21-slot comparison, source/seed
selection and package receipt. Device acceptance still needs protected upgrade,
cold start, Task04 scrolling, font/style changes, missing-glyph checks, page
release and actual process/GPU residency. Software and Android compilation alone
do not establish watch visual parity or performance.
