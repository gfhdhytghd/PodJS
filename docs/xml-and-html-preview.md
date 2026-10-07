# XML and lightweight HTML previews

`@podjs/framework/xml` provides `parseXmlDocument`, `parseXmlDocumentSteps`,
`requestXmlDocument` and `parseXmlDocumentAsync`. This is a compatibility tree,
not a validating XML/HTML5 parser: ASCII names, quoted attributes, CDATA and
aggregate descendant text, a single root element and matching closing names.
Comments and processing instructions are ignored; there is no DTD or external
entity/network resolution. Known common/numeric entities follow the portable
SDK, unknown entities stay literal. Invalid UTF-16 or a native quota rejection
uses the exact portable generator; malformed XML preserves its error.

Native jobs cap source storage at 8 MiB, nodes at 65536 and concurrent jobs at
four. Source upload and text output are 4096-unit/byte pieces; scan slices stop
between tokens after 64 tokens/4096 scanned bytes/1ms. A single long token or
attribute can exceed that work budget; this is cooperative scheduling, not a
hard CPU deadline. Cancellation closes the generator, cancels the current host
timer and releases native storage. Completed output uses the same tree shape as
the portable path. The default scheduler uses runtime.delay(1) on capable hosts
and a portable timer otherwise; existing text-layout defaults are unchanged.

`htmlTextPreview(source, maxUnits)` from `@podjs/framework/html` returns the
whitespace-collapsed text's UTF-16 length, a prefix without a split surrogate,
and the first decoded img src. The lightweight regex dialect removes comments,
script/style/noscript text, introduces separators for common block tags, strips
remaining tags and decodes common/numeric entities. Image selection follows its
own simple img-src dialect, excluding comments/script/style. It is not a DOM,
sanitizer or URL validator. The caller owns punctuation, URL resolution, source
precedence and the exact fallback when the host returns null. Limits are 8 MiB
source and 4096 prefix units. This native call scans one input synchronously;
large-input callers should schedule each call as a separate unit of work.

Rust mount points are shared by foreground, background and the offline QuickJS
service-bench example. `xml.nativeStep` can be traced only through the existing
explicit default-off diagnostic gate; no document text enters diagnostic logs.

The offline `service-bench` example accepts `PODJS_BENCH_KV_DIR` for a private
scratch directory. It mounts the real `KvStore` get/set wire contract, including
locking and atomic fsync/rename. Never point this option at a production data
root. Host-disk timing cannot establish Android/eMMC performance.
