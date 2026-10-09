# Published ACTIVE admission and deferred validation

**The first publication still performs full verification.** VERIFYING and
ACTIVATING recompute the conversion receipt, seal the native backup, synchronize
the candidate and atomically publish the pointer. The transition to ACTIVE still
calls the complete Room/store `active` checks before recording a successful deep
validation. None of the replay or source capture checks are bypassed.

An already ACTIVE generation uses bounded startup checks. Journal protocol,
pointer generation/receipt, activation receipt, canonical generation paths,
required nonempty database/KV/secure files and the files directory are checked.
A read-only SQLite connection checks `user_version`, four required table names
and the primary-key local identity row. Supported WatchRSS target versions are
0 (the converter's published schema before guest initialization), 1 and 2; newer
versions block before guest execution. Store provenance continues to reject an
isolated encrypted-store generation in a formal package. No article count, body,
file inventory, KV value or ciphertext is traversed at startup.

`active-validation.properties` is a bounded, atomically replaced and fsynced
record within the generation. It binds its protocol, PASS/FAIL, versionCode and
completion time to the activation receipt. Malformed, empty, oversized,
receipt-mismatched and failed records are rejected. A missing record on upgrade
is a legacy ACTIVE generation: its activation receipt proves the original full
publication check, **not the current contents**. It is admitted through the same
bounded invariants and is immediately eligible for a deferred deep check. No
fresh PASS is invented for such a generation.

The Android host schedules validation only from the first
`onSurfaceTextureUpdated` callback (actual guest texture presentation), with a
10-second grace period for initial guest SQL and layout. Admission through a
background worker alone never starts a scan. A low-priority dedicated executor
performs `quick_check`, `foreign_key_check`, current schema/identity, KV quota/key
validation and secure authentication/schema validation. It opens an existing
SQLite file with OPEN_READONLY, without creating, checkpointing or changing its
journal mode. The queries each see SQLite's consistent statement snapshot.

**The stricter deadline wins.** A missing history is due on the first launch of
this implementation. After any completed check (including initial migration),
no versionCode change may bypass the seven-day interval. A new version arriving
inside that interval has its first deep check deferred until the interval
expires; that success records the new versionCode. The same version is eligible
again after seven days. Changing versions repeatedly does not trigger repeated
scans. A backwards wall clock postpones validation until it catches up; it never
accelerates scans. This interprets “each version's first launch” subject to the
explicit stricter seven-day condition, rather than treating upgrades as an
exception to it.

Foreground SQL for the sandbox registers with a process-local workload gate.
If SQL is in flight, the optional reader yields; new foreground SQL cancels its
CancellationSignal before acquiring SQLite locks. Cancellation/SQLite lock
contention yields and retries after 30 seconds without updating the successful
history or marking corruption. KV and secure writers publish through atomic
rename, so the validator reads a complete old or new file descriptor, never a
partial file, and performs no read-modify-write on business stores. Different
stores need not share a single cross-store snapshot: the deferred checks assert
individual format/integrity, not relationships between live business values.

A genuine deep failure first persists FAIL, then emits sanitized release
`PodTakeover` diagnostics. The current admitted process is not hot-switched to a
backup. On the next cold admission the FAIL record transitions the journal to
BLOCKED with `active_deep_validation_failed`, retaining the pointer, generation,
new user writes and backup for the existing recovery UI. No automatic re-import
or reset is authorized. Storage errors that prevent recording history are
reported as failures; atomic publication has the same filesystem durability
assumptions as the takeover journal itself.

The tradeoff is explicit: corruption of content/pages, invalid live KV values or
secure authentication failures can be discovered after guest startup, up to the
scheduled weekly check. Missing paths, pointer mismatches, schema incompatibility
and already known failures still block startup. A crash before a check completes
leaves the previous successful/missing history and retries on a later eligible
foreground launch. Foreground SQL can postpone a check for a busy session;
cross-process writers still rely on SQLite locking (currently Android app
services share the default process). These are delayed-detection risks, not
permission to discard data or claim formal same-UID/device acceptance.

Release diagnostic timing lines contain only fixed step names and elapsed
milliseconds. Database-open, quick-check, foreign-key, schema/identity, KV,
secure, pointer and publisher timing allow before/after attribution without
printing paths, data, SQL values or key material.
