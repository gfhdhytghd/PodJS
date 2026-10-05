# Android package takeover coordinator (P2a)

This is framework infrastructure, not a working WatchRSS data converter or a production upgrade approval. The installed host uses `Converter.EMPTY`, which pauses at DETECTED without claiming a snapshot, creating target stores, or publishing an empty generation. P2b must supply the host-owned converter in `PodTakeover.coordinator`; guest code has no registration, resume, activation or cleanup API.

## Admission and publication

`PodTakeoverCoordinator` serializes migration with an in-process monitor and an OS file lock at `files/package-takeover/lock`. Journal writes use synced temporary files, atomic rename and directory sync. The state sequence is DETECTED, SNAPSHOTTED, COPYING, VERIFYING, READY, ACTIVATING, ACTIVE. Exceptions become PAUSED with a resume phase; unsupported sources and target conflicts become BLOCKED. Source detection failures outside an existing journal also fail admission closed. ACTIVE is terminal and validates the pointer without re-probing or re-importing native data.

Both Android/Wear Activities gate before constructing `PodRuntimeView` (and show a minimal native recovery label). `PodServices` gates before storage construction. Background/notification workers retry before scheduler/inbox access; the foreground playback service refuses creation/binding/start while blocked. Auxiliary sync/scheduler/notification constructors also gate. The installed manifest has no other custom background entry. WorkManager's own internal bookkeeping is not PodJS app storage and is not generation-switched.

All admitted consumers see the single immutable ACTIVE pointer, containing generation and verification receipt. There is deliberately no active-to-new-generation mutation API: opening consumers and subsequent activation cannot overlap. Runtime creation and direct storage constructors share a process-local admission cache keyed by the in-memory sandbox path. The first NONE or validated ACTIVE result publishes immutable data/files/database paths through a volatile reference under a per-sandbox lock. Later ready/path calls do not construct a coordinator, invoke detect, read the journal/pointer, or resolve filesystem paths. Exceptions and nonterminal results are not cached and may retry; each new process validates afresh. Admitted processes intentionally keep their pinned generation even if later detection/pointer I/O would fail. No Activity/Context is retained by the cache. Fresh/legacy PodJS installs with no detected native source retain the exact original roots and SQLite Context opening API and create no takeover directory. An existing empty takeover directory or empty journal also returns NONE without creating a lock, journal, or other file.

| Storage | Legacy | Active generation |
|---|---|---|
| SQL | databases/podjs.sqlite | generations/G/podjs.sqlite |
| Rust foreground/background KV | files/podjs/podjs-kv.json | generations/G/podjs/podjs-kv.json |
| Secure | files/podjs/secure.store | generations/G/podjs/secure.store |
| Files | files/podjs/files | generations/G/podjs/files |

Generations above are relative to `files/package-takeover`. Media, video sessions/surfaces, image, fonts, text raster, HTTP download and installed sync file consumers use the same files root. Keystore aliases remain unchanged; key migration is not implemented here.

## Converter contract and remaining work

- `snapshot`: create and validate an immutable recoverable snapshot before returning. The coordinator does not copy a live SQLite main file or touch source WAL/SHM.
- `copy`: idempotently convert from that snapshot; own batch transactions and cursor receipts in the candidate. No tables or stores are converted in P2a.
- `resume`: validate snapshot identity, converter version, cursors and completed-batch receipts after restart, before continuing. Version mismatch blocks and retains the old candidate; automatic replacement-candidate policy is deferred.
- `verify`: read back through production consumers, return a 64-character hex receipt digest, and never mutate the candidate. Called again before publication, including recovery from a durable pointer with an ACTIVATING journal. No callbacks run after ACTIVE.
- P2a uses an opaque candidate UUID. P2b must persist snapshot-derived migration identity, full manifests/digests, target schema, counts, file/store receipts and checkpoints. The P2a journal already records protocol/converter/schema, presence-only store inventory, space estimates, generation, phase and final verification receipt.
- Source probe reads SQLite header user_version without opening SQLite, inventories known native stores/assets, and conservatively estimates three times source bytes plus 32 MiB. This is not a Room table/identity-hash or recovered-WAL schema check. Those must happen on the consistent snapshot before SNAPSHOTTED. Unknown/corrupt header and store-only source block; insufficient space/unlocked-user requirement pause. Unknown source inventory and actual conversion-dependent space sizing remain P2b work.
- The native fallback is intentional: ordinary app JS cannot run before migration admission. `takeover.status()` from `@podjs/framework/services`, capability `host.takeover`, is a read-only admitted-runtime API; old hosts reject with `unsupported` and emit no request. A richer pre-admission JS progress runtime would need a separately restricted, storage-free host and is not supplied here.
- Preflight currently runs synchronously. Real converter work must be scheduled off the UI thread before installing a non-empty converter; P2a does not execute heavy copying on Activity startup.

## Roots intentionally not generation-switched

These host transport/OS bookkeeping stores are gated but remain in `no_backup`: `podjs-state`, `podjs-inbox`, `podjs-outbox`, `podjs-file-offers`, `podjs-file-requests`, `podjs-sync`, `podjs-pairing-*`, `podjs-sync-owner`, `podjs-background`, `podjs-notifications`. Notification permission preferences, Android Keystore, WebView profiles and platform media caches also retain their platform-managed locations. They are not substitutes for migrated app SQL/KV/secure/files. P2b must decide their conflict/import policy before production activation. Companion-example hosts, Apple/Harmony hosts and low-level Context-free JNI/test harness APIs are not Android installed-app entry points and do not perform source detection.

## Validation

`bun run test` includes the device-free JVM harness (`scripts/test-takeover.sh`) and cargo. The harness additionally checks repeated NONE/ACTIVE cached resolution, fault immunity after admission, one resolution/detect across 16 simultaneous first callers, uncached admission failures, and zero writes with absent/empty takeover roots. The harness exercises restart after each phase, PAUSED retry, BLOCKED/corrupt cases, missing converter, converter version change, invalid receipt, pointer-before-ACTIVE recovery, preservation of post-activation writes, no-source filesystem invariance, concurrent pointer reads, a torn uncommitted temporary pointer, and threads/separate-process lock contention.

`PodTakeoverTest` androidTest uses synthetic Context roots for source detection, no-source paths, blocked production storage constructors and all four active roots. P2a only compiles it. Real Android filesystem durability, OS termination, Keystore continuity and installed-package upgrade/physical acceptance remain PENDING. No device is operated and no source/user data is deleted.
