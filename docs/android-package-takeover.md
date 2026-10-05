# Android package takeover coordinator (P2a–P2d)

The installed host uses the Room/store/file composite described in P2b–P2d below. Verified, unblocked synthetic migrations can publish a complete generation; unsupported data and user-required reauthorization stay blocked. This is not production upgrade approval. Guest code has no registration, resume, activation or cleanup API. Earlier phase sections below retain their implementation scope; P2d supersedes their incomplete-composite gate.

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
- `verify`: read back through production consumers, return a 64-character hex receipt digest, and never mutate the candidate. Called again before publication, including recovery from a durable pointer with an ACTIVATING journal. Only the `active` current-store readback callback runs after ACTIVE; import callbacks never do.
- P2a uses an opaque candidate UUID. P2b must persist snapshot-derived migration identity, full manifests/digests, target schema, counts, file/store receipts and checkpoints. The P2a journal already records protocol/converter/schema, presence-only store inventory, space estimates, generation, phase and final verification receipt.
- Source probe reads SQLite header user_version without opening SQLite, inventories known native stores/assets, and conservatively estimates three times source bytes plus 32 MiB. This is not a Room table/identity-hash or recovered-WAL schema check. Those must happen on the consistent snapshot before SNAPSHOTTED. Unknown/corrupt header and store-only source block; insufficient space/unlocked-user requirement pause. Unknown source inventory and actual conversion-dependent space sizing remain P2b work.
- The native fallback is intentional: ordinary app JS cannot run before migration admission. `takeover.status()` from `@podjs/framework/services`, capability `host.takeover`, is a read-only admitted-runtime API; old hosts reject with `unsupported` and emit no request. A richer pre-admission JS progress runtime would need a separately restricted, storage-free host and is not supplied here.
- Preflight currently runs synchronously. Real converter work must be scheduled off the UI thread before installing a non-empty converter; P2a does not execute heavy copying on Activity startup.

## Roots intentionally not generation-switched

These host transport/OS bookkeeping stores are gated but remain in `no_backup`: `podjs-state`, `podjs-inbox`, `podjs-outbox`, `podjs-file-offers`, `podjs-file-requests`, `podjs-sync`, `podjs-pairing-*`, `podjs-sync-owner`, `podjs-background`, `podjs-notifications`. Notification permission preferences, Android Keystore, WebView profiles and platform media caches also retain their platform-managed locations. They are not substitutes for migrated app SQL/KV/secure/files. P2b must decide their conflict/import policy before production activation. Companion-example hosts, Apple/Harmony hosts and low-level Context-free JNI/test harness APIs are not Android installed-app entry points and do not perform source detection.

## Validation

`bun run test` includes the device-free JVM harness (`scripts/test-takeover.sh`) and cargo. The harness additionally checks repeated NONE/ACTIVE cached resolution, fault immunity after admission, one resolution/detect across 16 simultaneous first callers, uncached admission failures, and zero writes with absent/empty takeover roots. The harness exercises restart after each phase, PAUSED retry, BLOCKED/corrupt cases, missing converter, converter version change, invalid receipt, pointer-before-ACTIVE recovery, preservation of post-activation writes, no-source filesystem invariance, concurrent pointer reads, a torn uncommitted temporary pointer, and threads/separate-process lock contention.

`PodTakeoverTest` androidTest uses synthetic Context roots for source detection, no-source paths, blocked production storage constructors and all four active roots. P2a only compiles it. Real Android filesystem durability, OS termination, Keystore continuity and installed-package upgrade/physical acceptance remain PENDING. No device is operated and no source/user data is deleted.

## P2b: WatchRSS Room v18 candidate converter

`PodRoomV18Converter` is wired to the Android gate. It validates the generated
Room identity (`b44b41535bfc1462c5b15f3a2a561595`), all 15 table names,
column names/types/nullability/primary-key positions, `quick_check`, and foreign
keys on a recovered private copy. Unknown business tables/columns fail closed.
The source is never opened by SQLite. Native writers must be stopped by the
existing admission policy. A read-only POSIX lock over the main DB locking
range and all eight WAL SHM lock bytes excludes writers/checkpointers while
copying DB+WAL. Hashing/copying uses the same descriptors to preserve POSIX
locks. SHM is not copied. WAL recovery/checkpoint happens on the private copy
only; absent SHM for a nonempty WAL or unavailable locks pauses conversion.

The target schema resource is exported directly from WatchRSS `SCHEMA` and its
memo schema. Refresh/check it with:

```sh
bun scripts/export-takeover-schema.ts /path/to/PodJS_WatchRSS
bun scripts/export-takeover-schema.ts /path/to/PodJS_WatchRSS --check
```

The Room contract resource was extracted from native Room v18's generated
`WatchRssDatabase_Impl.createAllTables` (source code, not a user database).
`room-progress.sqlite` is attached to `podjs.sqlite`. Both use DELETE journals
and FULL synchronization, so batch rows, composite keyset cursor, catalog rows,
body-reference manifest and receipt commit through SQLite's super-journal.
No migration tables are added to the application schema. Each batch seals a
bounded-read digest; resume checks snapshot/schema/converter identity and the
sealed target, catalogs, references and cursor before any next batch. Verification
reconstructs an independent expected candidate, compares every target table and
catalog/reference row by key and value, and records counts/digests and orphan
counts. Verification scratch is removed only after successful validation.
Full-candidate sealing is conservative and can be expensive on large libraries;
watch-scale performance has not been measured.

Four reader tables become `reader-catalog.json` (records plus assets), preserving
undecodable payloads, nullable poster normalization and tombstones. Selection,
schedule, final KV merge/quota, secure stores and actual file copying belong to
P2c/P2d. Body markers remain unchanged in application rows and are parsed into a
safe `migration.content_reference` manifest for P2d; source sync/body metadata
is retained in the fixed Room snapshot. Derived article sync caches remain empty,
as with the offline oracle, until actual bodies/files can be resolved and rebuilt.
No download/file success is inferred from a database row. The local device ID is
read lazily after unlocked admission and snapshotted before default identity can
be created. Journal/status never exposes its value or content digests.

`complete()` returns false. Even a verified database ALWAYS ends at
`PAUSED`, `resumePhase=VERIFYING`, `reason=converters_incomplete`; no READY,
active pointer or guest startup is permitted. This is not end-to-end takeover
acceptance. No device or real-user snapshot is used by the tests.

`bash scripts/test-room-v18.sh` (also in `bun run test`) generates a synthetic
v18 source with uncheckpointed WAL, body references/large original text,
NULL/zero token fields, tombstones and orphan sync/note references. It compares
all target schemas and business tables against WatchRSS's two Python staging
oracles (derived revision count is intentionally excluded), checks all four
reader catalogs and the next autoincrement sequence, interrupts before/after
every batch both with exceptions and actual JVM termination, checks source
byte preservation/locking, and rejects schema/identity/tamper cases.
`WATCHRSS_APP_SOURCE`, `SQLITE_JDBC_JAR`, `JSON_JAR`, `SLF4J_JAR` allow explicit
CI inputs; defaults use the local source checkout and resolved Gradle jars.

## P2c: read-only preference and encrypted-store candidates

`PodTakeoverConverters` combines the Room converter with `PodStoreConverter`.
The native device identity now comes from the fixed preference snapshot, before
Room conversion. Room verification produces its reader catalog before the store
merge; records/assets, selection and schedule form the **single** production KV
key `watchrss.reader-presets` (the dotted paths in the design describe fields).
Both converters and the composite remain incomplete. A successful run stops at
`PAUSED / VERIFYING / converters_incomplete`, without an active pointer.
An existing P2b-only journal has a different converter version and is retained
and blocked by P2a's existing version-change rule; it is not silently reused.

The converter inventories every file in `files/datastore` and `shared_prefs`,
then copies bytes to `stores-source` without opening native store APIs. Both
single and doubled DataStore suffixes are recognized from that inventory.
Ambiguous variants and SharedPreferences backup files fail closed, rather than
using a potentially stale XML. Unknown stores/keys remain in the snapshot and
are enumerated; malformed/unreadable stores explicitly report incomplete key
inventory instead of claiming zero keys. The UTF-8 XML reader rejects DTD/entity
declarations before invoking Android's XML factory (which lacks the JVM Xerces
feature switches). The protobuf reader understands native bool/float/int/long/
string/string-set/double/bytes wire formats, rejects truncation and duplicate keys,
and preserves unknown-key bytes in the source snapshot.

Each `store-results/<opaque-slot>/` has a KV fragment, encrypted envelope and
last-written atomic receipt. The envelope authenticates the receipt and snapshot
identity. Interrupted fragments have no committed receipt and are rebuilt;
committed fragments are read back and checked before reuse. Candidate writes
use fsync and atomic rename. All snapshots and candidate generations are retained;
no source file, ciphertext, original alias or native self-repair routine is edited,
reset or deleted. The coordinator's existing process/file lock protects this flow;
as in P2a, native writers must be stopped before snapshotting.

Mappings cover settings with exact types/ranges and exact MiB conversion;
onboarding versions without increasing consent; TTS config plus secret checks;
Bili feed/search/interactions/progress DTOs; reader selection/schedule; home
placement; Douyin watched/history/recent/feed DTOs; announcement/push watermarks;
installation ID; daily cumulative telemetry and release-OOBE state. Bili's actual
progress key is `bili_playback_progress_json`; the design spelling is also accepted
but simultaneous variants fail. Douyin's production `media-services.ts` injects
**KV string storage** for history/feed, so those results go to KV, not secure.
Only exact native home activities are accepted as resume targets in this increment;
other native Intent URIs produce a safe home candidate and remain unsupported.
No Intent is executed and no article/note route is fabricated without referential
validation. Telemetry is copied, never replayed, and no network request is made.

`PodStoreAndroidCrypto` reads AndroidX's existing encrypted Tink keysets directly
using the existing `_androidx_security_master_key_`. It does not use
`AndroidKeysetManager`, `MasterKey.Builder`, `EncryptedSharedPreferences.create`,
or `WatchAccountStore` in production. Source master aliases are lookup-only;
only the separate PodJS target alias may be created. Account `state_json` and
supported TTS API keys become production secure values. Cloud key metadata is
validated and retained in an encrypted per-store quarantine; where account/device
binding is available, an ECDH round trip checks the existing cloud key/certificate
pair without exporting or regenerating the private key. This does **not** claim
that the PodJS cloud runtime has adopted the old alias. Unknown decrypted key
names and retained values stay only in that encrypted quarantine; reports contain
counts, presence, validation/status flags and fixed reason codes, never secret
values, aliases or secret digests. Locked-user failures pause and retry;
missing/broken source crypto or invalid secret semantics report `needs-reauth`.
Neither account token refresh nor automatic guest fallback occurs.

Known retained items are deliberate release blockers, not successful migrations:
custom LLM provider/config and API-key consumer, cloud runtime/state/alias adoption,
installation `first_installed_at`, phone-connection startup policy, temporary hints,
WebView scaling/login/cookies, unknown keys, unsupported native resume activities
and unsupported TTS providers. Raw originals remain available; decoded LLM/cloud
material remains encrypted in per-store candidates. Missing supported TTS secrets
remove the actionable TTS config and mark settings `needs-reauth`. The reports
must be reviewed and any user-required reauthorization choice implemented before
future activation; P2d completion alone must not bypass these statuses.

Final files are `podjs/podjs-kv.json` and `podjs/secure.store` within the candidate.
Mapper schema checks, the 4 MiB aggregate UTF-8 KV limit (including Room reader
catalogs), the production secure size limit, and decrypted readback are mandatory.
`stores-report.json` lists per-store status/reason/version/key inventory;
`stores-validation.json` records candidate validity without values. Android secure
readback shares the actual `PodDeviceServices` decoder. No runtime sees these
files while admission is paused.

`bash scripts/test-stores.sh` is included in `bun run test`. It uses synthetic
protobuf/XML and replaceable authenticated JVM crypto, interrupts and actually
terminates a JVM before files/after files/after receipt for every known store and
an unknown store (60 exception boundaries + 60 process exits), checks source
byte preservation, missing/locked crypto, cloud-binding degradation, backup and
filename ambiguity, target/receipt tamper and quota failures. A Bun oracle reads
synthetic results using the application's actual Settings/Onboarding/Reader/TTS/
Account/Resume/Telemetry/Bili/Douyin consumers. A JDBC integration fixture runs
P2a+P2b+P2c together, verifies catalog/identity preservation and incomplete admission.
No real-user data is consumed. `PodStoreCryptoTest` creates uniquely named
AndroidX/AndroidKeyStore fixtures, exercises the existing-key/cloud-binding path
and production secure decoder; it is **compiled only**, not run in P2c.

## P2d: files, blockers and activation

The installed composite now requires Room, stores **and files**. Standalone
Room/store/file converters still cannot publish. All three verification callbacks
must succeed, the file completion receipts must cover every publishable asset,
and every blocking report must be cleared before READY/ACTIVATING. The existing
P2c `needs-reauth`, `retained-unsupported` and `failed` statuses are **BLOCKED**,
not implicit user approval or an automatic logged-out fallback. Public reasons
are fixed codes; private reports retain details for the lead's later recovery UI.
A cloud cache is retained privately and blocked (`cloud_cache_binding_unverified`)
until a runtime adapter can prove snapshot/chunk/key binding. This increment
never advertises that retained cache as an available cloud snapshot.

`PodFileConverter` snapshots all files/cache entries after native detection,
excluding the owned PodJS/takeover roots and separately snapshotted DataStore.
A versioned `files-snapshot.json` records size, SHA-256 and category. Copying uses
64 KiB buffers, rejects symlinks/non-regular files/path traversal, writes a bounded
hashed `.part` name, verifies length/digest, fsyncs and atomically renames. A matching
existing target may be reused; different bytes are a conflict. There are no hard
links, tar files or source writes. Each published file has a durable completion
receipt in `files-completed`; recovery rechecks completed bytes and receipts.
Even 255-byte source filenames do not grow when constructing a temporary name.

Published roots are `article_content`, `offline/rss`, `reader_assets/fonts`,
`reader_assets/backgrounds`, `reader_assets/variants`, and `notes/assets` under
`generation/podjs/files`. P2b `content_reference` rows must resolve through the
production text-marker grammar, valid UTF-8 and (for v2) the content digest.
Reader catalog lengths/hashes, font faces (Android Typeface loader), presets and
asset references are checked. A missing background master is explicitly degraded
only when a verified watch variant exists. Missing offline media and note images
remain missing, with explicit private failure/retry reports and unchanged DB/Markdown.
Partial files, known refetchable previews/playback caches and `app_log.txt` stay in
the private snapshot, never the guest's file root or automatic upload. Other user
files are retained and block publication rather than being silently discarded.

New-schema sync caches remain empty at activation by design. The application's
existing `LocalArticleManifestStore` hydrates copied bodies, constructs the full
manifest, checks the library revision and publishes derived cache records before
returning the export result; no old derived cache is imported. The consumer oracle
exercises this actual path, including rejection of a concurrent semantic edit.
The fixture also verifies the existing native-hash-to-rebuilt-cache semantics;
this task does not change the application's wire protocol or hash rules.

Before pointer publication the coordinator re-verifies the bundle, writes and
syncs an immutable `activation-receipt`, and syncs all candidate files/directories.
It atomically replaces **one** `active` pointer, reads it and the receipt back,
and records ACTIVE. Admission then checks the committed DB/KV/secure/file roots,
SQLite integrity and current KV/secure decoding. On subsequent cold starts it
checks current stores only, never old snapshot equality or source presence;
new user writes survive. An invalid ACTIVE store fails admission without creating
an empty replacement or re-entering import. A running admitted process retains
its pinned generation. Temporary pointer files, journals and locks reject symlinks.

### P2c no-source regression fix

The reverted P2c probe treated `nonempty(shared_prefs)` as native evidence. A
PodJS-only sandbox containing `WebViewChromiumPrefs.xml` therefore incorrectly
entered DETECTED and wrote a journal/lock despite all known native flags being
false. `PodTakeoverSource` now supplies the **same device-free probe** to Android
and JVM tests: only known native DB/WAL, store names (including account backup)
and asset roots establish source presence. PodJS files, platform WebView prefs,
unknown host prefs and cache alone do not. Full unknown-store/file inventory is
performed only after positive native evidence. The preflight uses raw sandbox
paths instead of directory-creating Context getters. A store-only/corrupt native
source still fails closed; it does not become a fresh install.

Tests cover a PodJS/WebView-only sandbox returning NONE repeatedly without any
new directory/lock/journal or changes to file bytes/directory mtimes, complete
synthetic migration to ACTIVE, actual process termination at every phase and
snapshot/copy part/rename/receipt/publication boundaries, atomic pointer reads,
source-byte invariance, retained new writes, missing ACTIVE stores, long filenames,
path traversal, live/dangling symlinks, tampering, explicit blockers and media
failure states. A Bun consumer oracle loads reader records, streamed text,
note asset paths and the derived sync manifest. Android instrumentation fixtures
are updated and compiled only; no device, real source data or release package is
used. Pre-admission UI/off-main-thread scheduling, reauthorization choices, cloud
adapter completion and physical/real-upgrade acceptance remain lead follow-ups.
