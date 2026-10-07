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
