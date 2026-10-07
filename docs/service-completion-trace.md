# Service completion tracing

Android diagnostics are off by default. Use a debuggable host and explicitly set
`adb -s <serial> shell setprop log.tag.PodJSService DEBUG` before recreating the
runtime View. Non-debuggable hosts reject the opt-in unless the source
`ServiceResultTrace.PERF_TRACE` switch is enabled (default false).

Each completion logs an allowlisted exact `method`, numeric request `id`,
`label`, UTF-8 event JSON `bytes`, result-array `rows` (-1 for non-array values),
and main-looper completion wall `ns`. It includes native event delivery, the
service pump, dependent guest Promise jobs and host-effect dispatch. The result
JSON itself is never logged. Diagnostics stop logging after 4096 completions per
host; the last record says `capped=true`. Request tracking is bounded to 64.

Use optional code-authored labels such as
`sqlite.query(sql, params, 'article.metadata')` or
`sqlite.execute(sql, params, 'article.markRead')`. Unlabelled SQL queries/writes
receive a CRC32 fingerprint of the SQL text for correlation, not an assertion
of unique call-site identity. Never supply user values, URLs, SQL or tokens as
labels. No arguments or parameter values enter the log.

`traceServiceWork('article.mapRows', () => mapRows(rows))` from
`@podjs/framework/service-trace` logs synchronous guest spans only after the
host enables tracing. `ms` uses the guest millisecond clock; `exclusiveMs`
subtracts nested traced spans. It does not measure time spent awaiting Promises
or subtract untraced callees. Default-off spans do not call a clock or emit an
effect, and diagnostic failures cannot change results or replace product errors.
KV get/set have generic spans to separate nested synchronous storage work from
SQL callback CPU. Do not add inclusive completion and guest span times together.

Clear the log property before recreating the final release View. Recording
metadata adds diagnostic overhead; release acceptance must use the default-off
configuration. Method labels identify callbacks, not necessarily the cause of
all work reached from a callback.

XML preparation exposes `xml.nativeStep` synchronous spans. Upload and flat-row
adoption are 4096-unit/byte pieces; the default scheduler yields a host timer
between slices. These spans exclude the timer wait and do not include all work
in the enclosing completion.

An optional third `traceServiceWork` argument records a nonnegative integer
`units` only. Supply lengths/counts with a fixed code-authored label; never pass
content or sensitive identifiers. KV exposes nested `kv.encode` and `kv.write`
spans; write units are encoded UTF-16 length (not UTF-8 bytes).

Worker-side `host phase=secure.* id=<requestId> ns=<duration>` records are separate
from completion records, under the same debuggable/explicit opt-in gate.
`secure.lock` covers JVM/OS lock acquisition, `secure.key` alias lookup/creation,
`secure.read` encrypted file IO, `secure.decrypt` AEAD, `secure.json` UTF-8/JSON
adoption, and `secure.total` the entire secure operation. A missing file has no
key/decrypt stages. Only fixed labels and integers are logged (4096 stage cap);
there are no keys, values, paths or errors. Guest continuations run later on the
main looper: a long `completion method=secure.get` does not establish slow AEAD.

Opted-in Android hosts also record a bounded 128 DOWN/UP/CANCEL (non-MOVE)
input events with numeric action, MotionEvent `eventMs`, application `receivedMs`
(uptime) and `epochMs`. MOVE events consume no quota. Align the application wall
clock with the measurement clock and retain the synchronization uncertainty;
`receivedMs-eventMs` is dispatch delay, not ADB process startup. Completion
`roundTripNs` measures dispatch-to-end-of-completion (including dependent guest
jobs); `ns` remains completion-turn wall time. A lost/cancelled request uses -1
for its unknown round trip. These two durations overlap and must not be added.
`prepareTextLayout` accepts an optional fixed `traceLabel` for synchronous
iterator/adoption work in each slice, excluding host timer waits/publication.
Guest records carry numeric `beginEpochMs`/`endEpochMs` sampled in the guest,
so delayed host-effect draining does not move the span's placement on the
measurement timeline. Keep millisecond clock precision and host clock alignment
uncertainty in the report; these are wall timestamps, not CPU cycle counters.
