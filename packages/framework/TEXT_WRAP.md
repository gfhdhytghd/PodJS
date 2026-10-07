`@podjs/framework/text-wrap` exports the portable `layoutText` and
`wrapAtlasText`. The latter measures with the host's atlas font slot, scale and
tracking. Supply the identical portable `measure` callback for older hosts;
this API is for atlas metrics, not an arbitrary native font measurer.

The optional Rust `pod.wrapAtlasTextChunk` bridge accepts at most 4096 UTF-16
units, finite positive width/scale and finite tracking. Unsupported inputs,
including lone surrogates and exotic whitespace/ZWJ clusters, retain the exact
portable path. It keeps the existing cluster and whitespace rules; it is not a
new Unicode line breaking algorithm. A call is synchronous and cannot preempt
its measurer. Keep each input paragraph small and schedule between paragraphs.

`@podjs/framework/text-layout` exposes `prepareTextLayout` without importing
text rendering. It consumes an iterator with the existing default scheduler:
`runtime.delay(1)`, 0.5ms work budget and at most 16 steps per turn. A single
iterator step must be bounded. Cancellation closes the iterator, cancels a
pending timer and suppresses late publication. The final coherent snapshot
contains rows and their key index; it can feed `createIndexedWindowSource`.

`@podjs/framework/keyed-memo` exposes `createKeyedMemo`. Dependencies compare
with Object.is. Own a memo per document/job, clear it when the source changes
and on disposal, and keep dependencies immutable. It retains computed values,
not application or host resources. Use stable keys and document scope in a
windowed source so geometry updates preserve row owners; mounted renderers
should read new geometry through their current-value accessor.
