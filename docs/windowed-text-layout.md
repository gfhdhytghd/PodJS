# Sliced text layouts and stable windowed lists

`@podjs/framework/text` exports `prepareTextLayout`. It consumes an iterator of
already shaped rows and yields through the real `runtime.delay(1)` service before
each bounded turn. An application supplies its row identity, entry adapter and
publication callback. Geometry, font selection and business data remain with the
caller; the scheduler has no reader preset or application-specific row types.

```ts
import {prepareTextLayout} from '@podjs/framework/text';

const task = prepareTextLayout(paragraphRowIterator, {
  key: row => row.id,
  entry: row => ({key: row.id, top: row.top, height: row.height, retain: true, data: row}),
  publish: snapshot => setDocument(snapshot),
});
onCleanup(() => task.cancel());
await task.done;
```

The iterator yields rows one at a time and returns the complete layout result.
Preparing each entry and its key index is included in the work budget. Defaults
are 1 ms of measured work and at most 64 rows per turn; both can be configured.
A clock with millisecond resolution and an indivisible step can overshoot the target. A
single iterator step is indivisible, so callers must not put a long synchronous
shaping call inside it. This API bounds cooperative work rather than preempting
JavaScript. Native paragraph shaping should happen through text services before
row construction, or the iterator must split its own expensive work.

The `publish` callback runs once, after completion, with `{layout, entries,
indices}`.
For first-screen display, optionally add `preview: {rows: 16, publish: prefix =>
setFirstWindow(prefix)}`. Once a slice has at least that many entries, the preview
receives copied `entries`/`indices` exactly once, unless the iterator already
finished (then only `publish` runs). Further preparation never appends to that
prefix. It has no final layout result: consumers must defer full-document
progress/anchor restoration until `publish`. Cancellation suppresses future
callbacks; a prefix already displayed remains the consumer's responsibility.
The wall/row budget excludes both preview and final publication, including any
reactive mount work they trigger. Keep that work confined to the visible window.
Duplicate keys, failed host yields and iterator errors reject `done`
and do not publish an incomplete source. `cancel()` cancels the outstanding
host operation and closes the iterator, suppressing late replies and publication.
The iterator's return value must contain the same final geometry as the caller's
synchronous layout path. `stats` exposes turns, rows and maximum work time;
`onSlice` can collect numeric measurements without retaining document content.
Publication and yield wait time are excluded from `maxMs`.

`@podjs/framework/windowed-list` exports `createIndexedWindowSource` and
`createWindowedList`. They consume the prebuilt key index rather than scanning a
new source to rediscover all keys. The visible slice plus bounded opted-in
history own the Solid signals. Each window item has a stable `key` and a reactive
`value()` accessor. Use the window item as the identity of a Solid `For`, and read
its current value for geometry and text. A source replacement in the same scope
updates those signals without mounting every line again.

```tsx
import {createMemo, createSignal, For, onCleanup} from 'solid-js';
import {createIndexedWindowSource, createWindowedList} from '@podjs/framework/windowed-list';

const documentScope = {}; // Change for a different document, keep across layout revisions.
const source = createMemo(() => createIndexedWindowSource({
  entries: document().entries,
  indices: document().indices,
  scope: documentScope,
}));
const window = createWindowedList({
  source,
  lower: () => scrollOffset() - 80,
  upper: () => scrollOffset() + viewportHeight() + 80,
  retainRows: 24,
});

// Inside a positioned, clipped column:
<For each={window()}>{item =>
  <View class="absolute" style={{insetT: item.value().top, height: item.value().height}}>
    <Text>{item.value().data.text}</Text>
  </View>
}</For>;
```

Keys must be unique in one source and must match `indices`. Object keys are
supported. Rows and indexes are immutable source snapshots. Native arrival may
change text metrics and fields while preserving the key of the same source line.
If wrapping creates a different source line, use a different key. Removed keys
are removed from retention. Changing `scope` starts new document identities.
Unkeyed array sources preserve source-replacement invalidation; use an indexed
source to retain history across replacement without an O(document) key scan.

`createIndexedWindowSource` can add small headers/footers without copying the
body array. Body `top` values are already laid out, including body gaps. Its
`padding` and `gap` options apply to the small edge sections. `at(-1)` supports
content extent lookup; out-of-range reads return undefined. Header/footer keys
must not collide with body keys. `visibleWindowRange` computes actual viewport
intersection with exclusive end edges, independently of overscan/retention.

The default equality is shallow field equality, so shifting the visible boundary
without changing a row does not notify that row's owners. A custom `equals`
callback may be supplied for immutable domain data. `createWindowValue` provides
a reactive object view for renderers that accept row objects rather than
accessors; it supports frozen source objects and fields added by native layout.
`createWindowRows` and `createLiveWindowRows` expose the selection/reconciliation
primitives for alternate rendering integrations.

The text scheduler and window consumer are complementary: preparation happens in
host turns, then publication updates only the current window and retained rows.
This does not make native shaping/raster calls, GPU presentation or an initial
synchronous fallback preemptible. Measure the actual target watch; host timing
and data equivalence are not device frame-rate or pixel-parity acceptance.
