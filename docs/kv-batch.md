# Synchronous KV batches

`kv.batch(() => { kv.set("first", value); kv.set("second", other); })` stages
JSON snapshots and commits the final mutations with one locked atomic file
replacement/fsync. Reads, deletes and keys inside the callback see staged
values. Repeated writes to a key retain its final snapshot; input mutation after
`set` does not change that snapshot. Nested batches share the outer batch,
without savepoints. Use synchronous callbacks only. Async callbacks are rejected
on a batch-capable host; their later work cannot be scoped by this API.

A thrown outer callback discards its staged mutations. A commit error propagates. Validation/quota failures and IO failures before
publication leave persisted state unchanged. A directory fsync failure after
rename can report failure after publication, as for ordinary `set`; callers
should read back before retrying non-idempotent updates. Other side effects
of the callback (signals, network requests) are not rolled back. Unlike ordinary
`set`, disk errors and the total quota are checked at commit. One batch accepts
at most 1024 distinct keys and the existing 4 MiB store quota. It does not hold
a disk lock across guest code: commit reloads under the cross-process lock and
merges mutations with unrelated external keys. Read-modify-write of the same
key still needs application ownership across foreground/background writers.

Older hosts without `pod.kvApply` run the callback with legacy per-write
persistence; they provide neither atomic rollback nor the write reduction.
Unbatched APIs retain their existing immediate persistence. Both paths preserve
key validation and JSON snapshots. Empty batches do no disk work.

Every commit retains file and directory fsync, including byte-identical values.
Commit reloads and validates actual bytes under the existing OS lock; it does
not use an mtime or guest-only persistent value cache. Batch reduces the number
of commits without deferring persistence beyond the synchronous call.
