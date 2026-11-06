# Bounded content lane

`content-worker`, `content-files`, `content-host`, `content-record`, and
`content-sha256` are opt-in framework ports. Importing them schedules no work.
The application owns identity checks, immutable source revisions, authenticated
session membership, protocol receipts, and queue ordering.

- One content window is at most 300 seconds, with a 10 second reserve and an
  8 MiB wire-byte limit. The owner must explicitly schedule the next window.
- Each step transfers at most one 65,536 byte frame. A bounded preparation or
  verification phase is also permitted. CPU updates are at most 256 KiB.
- `ContentWorker` yields before every step and rechecks confirmed conditions.
  Unknown power/network observations cannot grant a condition. The optional
  charging-cellular setting defaults to true.
- `AuthenticatedContentSessions` accepts leases only from the application's
  authenticated session owner. It never inspects radio power, pairing, headphones,
  or system ACLs. A dead lease stops contributing immediately.
- `content.conditions` observes charging, active Wi-Fi, cellular routing, and
  validated internet. Bluetooth application authentication comes from the owner.
- `content.*` work has a distinct executor, device service, and HTTP client.
  Secure/control requests retain their existing lanes. HTTP cancellation and
  teardown cancel both independent clients. No content operation acquires an
  account or business writer lease.
- File paths are restricted to the `content-v2` projection root. Stat/read batches
  contain at most 32 entries; read batches return at most 262,144 data bytes.
- `content.files.frame` writes and fsyncs data, then atomically publishes and
  fsyncs its checkpoint directory before returning. A crash before checkpoint
  publication leaves at most one unconfirmed frame; retry truncates that tail.
- `content.files.publish` is immutable. Its optional checkpoint certifies a
  completed local validation/preparation phase. Applications encrypt and bind
  these proofs before publication. A lost reply can adopt that proof without
  repeating completed authentication, decompression, or export.
- `content-record` authenticates a bounded set of small encrypted fragments. It
  prevents a large local recipe descriptor from exceeding the existing host
  crypto input cap. This encoding is local storage only, never protocol wire.
- `crypto.v2.decrypt` accepts at most 262,160 ciphertext bytes (256 KiB plus a
  16 byte AEAD tag), with at most 4,096 AAD bytes. Legacy crypto limits remain
  unchanged. `createSyncV2Crypto()` uses this v2 port above the legacy ciphertext cap.
  Small ciphertext retains the existing native route.

These capabilities do not enable content synchronization, local object transfer,
automatic eviction, custody release, or note segmentation by themselves.

`ContentFiles.discard(path)` retires an untrusted derived prefix after an upper
layer integrity check fails. It is restricted to the existing hashed content-v2
projection root. Native IO publishes/fsyncs a zero checkpoint before truncating
and fsyncing the data file. Thus a lost reply or interruption cannot resurrect
an accepted prefix; the next frame starts at zero and truncates an old tail.
The caller must only pass the rejected derived cache, never an original source
or another authenticated block. This operation does not evict business content.
