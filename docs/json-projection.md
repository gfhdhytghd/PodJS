# Sliced JSON field projection

`@podjs/framework/json` exposes `projectJsonAsync(source, selection)` and
`requestJsonProjection(source, selection, publish, options)`. A selection is an
object of fields, with `null` retaining a complete subtree, another object
selecting its fields, and `[selection]` applying to each array element. Shape
mismatches retain the value; callers still own schema validation and mapping.

The Rust host validates the complete document, including discarded fields, and
keeps selected subtrees as raw JSON. Guest `JSON.parse` owns selected numeric and
string semantics (negative zero, overflow, exact number rounding and escapes).
Duplicate object keys use the last value while preserving first insertion order.
The SDK defines own data properties, including `__proto__`. Unsupported input,
WTF-16 upload, limits and nonobject roots use the exact SDK JSON.parse/projection
fallback. Native jobs retain at most 8 MiB each, four jobs and 65536 elements per
selected top-level array. Cancellation closes the iterator and cancels the pending
host timer; completion and native failure also release the job.

Uploads are at most 4096 UTF-16 units without splitting surrogate pairs. Native
outputs selected top-level array elements individually; adoption and application
mapping can yield between entries. `json.nativeStep` and `json.adopt` diagnostic
spans are default off. `units` reports selected JSON string length, never content.

This is not a hard 16 ms guarantee. Initial full-document validation and a single
large selected subtree remain indivisible. The portable fallback parses the full
source synchronously. Callers must choose fields based on their existing mapper's
reads and use differential tests to prove their projection does not change results.
No feed schemas, URLs, cookies or domain names are implemented in the framework.
