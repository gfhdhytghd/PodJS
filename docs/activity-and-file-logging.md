# Activity coordination, scroll restoration and file logging

`@podjs/framework/navigation` exports `ActivityStateHub`, `ScrollRestoration`,
`validScrollSnapshot` and the scroll snapshot/row types. These helpers have no
route registry, application keys, or implicit service calls.

`new ActivityStateHub(publish)` starts with empty saved/retained namespaces.
`seed(state)` and `read()` preserve the supplied state reference. `write(next, keys)`
clones both channels, replaces/deletes only the owned keys, updates its local state
synchronously, then calls `publish(merged)`. The returned promise preserves rejection;
failed or reordered acknowledgements never roll back local state. Callers own schema
validation, persistence, batching, trace labels and protection from external mutation.
It is a guest coordinator, not a second implementation of native activity persistence.

`new ScrollRestoration(offsetOrSnapshot)` accepts a legacy absolute offset or a
version-1 item anchor. `target(max, rows?)` follows late-arriving rows until `cancel()`
on user input or explicit scrolling. A legacy offset is consumed when max reaches it;
an anchor remains pending. Unchanged max/row identity skips recalculation. Rows must
be sorted with valid heights and a stable index identity; replace the rows object on
layout changes. `snapshot(offset, rows?)` retains pending state or captures a new
anchor. Validate deserialized objects with `validScrollSnapshot` before construction.

`@podjs/framework/logging` is a separate entry point. `createLogRedactor` accepts
`sensitivePattern` and optional `headerPattern`, **trusted regular expression
alternatives without capturing groups**, not raw user input or literal key arrays.
The header override replaces the default cookie/authorization header set, so callers
extending it must include those defaults. The returned function redacts header values,
Bearer credentials, URL userinfo/query/fragment, relative-path queries and configured
key assignments, in that order. Application credential dialects remain in adapters.

`new FileLogger(file, options, now?, output?)` requires read/size/replace/append ports.
Size and append offset are UTF-8 bytes. Options supply `redact`, `maxBytes`,
`trim(lines)` and a fixed public `writeFailureMessage`. Clock defaults to local Date;
output defaults to a no-op. Only redacted formatted lines reach normal output/writes.
Do not put exception details or credentials in the failure message.

One FIFO owns init/read/write/clear/flush. `init(legacy?, scanExisting=true)` sanitizes
old content, importing legacy content only if the file is empty; disabling scan skips
historical redaction. Init and clear generate no automatic application messages.
Before each append, strictly exceeding maxBytes invokes trim once; trim receives
lines with the final empty line removed and returns the complete replacement text.
This is a compatibility policy hook, not a hard cap on the resulting file size.
Logging methods swallow formatting/output/write errors; init/read/clear can reject,
but failed work does not poison later operations. `flush()` is a barrier for already
queued work, not future writes. Upload authorization, encryption, transport, payload
limits and singleton installation belong to the application.

U6 tests cover neutral contracts and default construction. WatchRSS freezes its old
implementations for differential coverage of namespaced state, scroll evolution,
credential dialects and exact file/output ordering. No native lifecycle, physical
scrolling or device parity is implied by these host tests.
