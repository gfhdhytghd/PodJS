# Incremental HTML fragments

`@podjs/framework/html` provides a synchronous fragment collector,
`parseHtmlFragmentSteps`, and `normalizeHtmlWhitespace`. The scheduler is a
separate `@podjs/framework/html-slices` entry, so pure parsing does not import
service scheduling or watch lifecycle state.

```ts
import {requestHtmlFragment} from '@podjs/framework/html-slices';
const work = requestHtmlFragment(source, tree => {
  // null: caller should run its existing parser for exact compatibility.
  if (tree) useFragment(tree);
});
// On source replacement, inactivity, or disposal:
work.cancel();
await work.done;
```

The foreground Rust runtime exposes optional `pod.htmlBegin`, `htmlAppend`,
`htmlNext`, `htmlCancel`, and `htmlNormalizeWhitespace` functions. Source is
uploaded in at most 4096 UTF-16 units without cutting a surrogate pair. Each
native scan emits at most 64 tokens / approximately 16 KiB source, and stops
between tokens after 1 ms of native work. A token itself is bounded to a
16 KiB regex window. Rows are direct QuickJS arrays
`[parentIndex, tag, alternatingAttributeNamesAndValues, decodedText]`, with an
implicit root at index 0. No service event JSON or complete native object graph
is constructed. The SDK adopts rows in bounded batches and reconstructs a
fragment for application adapters. The default scheduler uses valid
`runtime.delay(1)` turns. Returning/closing the iterator releases its native job;
cancellation also cancels the outstanding host delay and suppresses publication.

This is a tolerant fragment grammar, rather than a complete browser DOM/HTML5
parser. It accepts HTML void/self-closing tags, unmatched closes, unclosed tags,
and optional consecutive `p`/`li` closes. Tag names use ASCII case folding;
attribute names use Unicode lowercase, retaining insertion order and the last
value of duplicate attributes. Quoted attributes may contain `>`. Comments and
declarations are discarded. `script` and `style` subtrees are omitted by default;
`skipTags` can override this. Skipped subtrees still participate in tolerant stack
handling, preserving the fragment grammar around malformed embedded markup.

Compatibility entities are `amp`, `lt`, `gt`, `quot`, `apos`, and `nbsp` (space),
case insensitive and semicolon terminated. Unknown names remain literal. Numeric
entities preserve the compatibility parseInt/clamp behavior, including removing
non-finite/invalid values. Surrogate numeric entities and lone UTF-16 source
surrogates decline the native path instead of losing data. Whitespace collapse
matches ECMAScript `\s`, including FEFF and excluding NEL; small strings use the
portable implementation to avoid bridge overhead. Preformatted whitespace is
preserved by caller choice.

Limits: four concurrent jobs per mounted namespace, 4 MiB source per job,
65,536 rows/stack entries, and 1024 bytes of skip-tag options. Long tokens, bounds,
or unsupported source return `null` and discard partial results. The caller owns
its portable fallback. The runtime releases every retained job at namespace
teardown. Limits apply to native work; the caller's legacy fallback can still be
synchronous. Closing-tag stack search and attribute decode are bounded by these
limits but are not preemptible inside one token. Native 1 ms is a work target
checked between tokens, not a strict worst-case deadline.

Selectors, hidden classes/roles, root preference, URL normalization, serialization,
text grouping, media types, code/table/list styles, and source-specific block
mapping belong to application adapters. None of those rules are in this runtime.

Verification: Rust tests use the real QuickJS bridge; SDK tests cover direct rows,
old-host fallback, default host timers, cancellation and late delivery. A consumer
should also compare all final blocks/attributes/serialized output against its
previous parser on captured documents and malformed/Unicode fixtures. Host timing
and bounded native turns are not device, GPU, or UI acceptance evidence.
