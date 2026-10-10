# Scheduler retirement on ACTIVE startup

The CLI emits `pod.workmanager.json` for the Android host, containing protocol 1
and only the selected `androidWorkManagerTakeover` configuration (or explicit
null). It selects the same replacing package as the full guest manifest. The
runtime bounds this input at 65536 bytes and validates the same identities and
fingerprint as before. A malformed compact asset fails closed; only an absent
asset falls back to the old full manifest for package compatibility. This avoids
parsing the guest's large asset inventory to locate a small host configuration.
The configuration remains installed owner policy, never guest input.

After storage admission, scheduler retirement retains its process mutex, safe
canonical paths, inter-process file lock and AtomicFile fingerprint receipt.
The WorkManager supplier is acquired only if there is no durable receipt. A
verified receipt means the original cancellation completed: opening WorkManager
and its Room database again would add work without changing the result. A
corrupt receipt still fails admission. Missing or changed configuration receipts
still synchronously initialize WorkManager, await cancellation of the selected
names/class tags/explicit tags and durably write/read back the receipt before
guest startup. Other current work remains untouched. An AtomicFile backup is
handled under the same lock and path checks as before.

`activeWorkConfig` and `activeWorkRetirement` report fixed numeric step timings.
Already completed process-local lookups emit no repeated timing logs.
The retirement line includes the lock and receipt check, or actual cancellation
when needed. It is separate from `activeConverters`, `activePublisher` and the
nested `activeFastSchema` line; inclusive timings must not be summed twice.
The admissionQueueWait line measures queue scheduling, not constructor/Room work.

This change does not modify coordinator publication, pointer or activation
receipt validation, required stores/files, read-only schema/identity checks,
provenance, receipt-bound FAIL history, first publication full verification or
first-frame deferred deep checks. Existing ACTIVE startup invariants are
specified in [takeover-active-validation.md](takeover-active-validation.md).
WorkManager initialization for current guest scheduling retains its ordinary
scheduler path. The optimization is identical in debug and release builds.
Rebuild the APK: a JS-only replacement cannot add the host asset or native gate.
