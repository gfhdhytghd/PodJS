# Digest v1 differential and host benchmark

`LegacyTakeoverDigest.java` freezes `TakeoverDigest.java` from framework commit
`d66ed136e5a8fc9d409bc5d2eeb5868a425084ba`, with only the class name changed.
Its original source SHA-256 is recorded in the T31 handoff report. Do not update
this oracle alongside production changes.

`bash scripts/test-takeover.sh` runs the differential suite. It compares all six
JSON lines byte for byte and compares exception class/message for malformed
entries and sealed ACTIVE metadata. Fixtures are synthetic: randomized Unicode
paths, invalid UTF-16 replacement collisions, empty roots, inherited Properties,
platform metadata exact exclusions, long paths, size overflow, 10,000 entries,
reversed inventory, concurrent calls, missing files and symlinks.

Run `bash scripts/benchmark-takeover-digest.sh` for the JVM microbenchmark, or
`:runtime:testDebugUnitTest --tests dev.podjs.takeover.TakeoverDigestDifferentialTest`
for Robolectric (sdk33). Both use the same frozen oracle and synthetic 10,000-entry
manifest, check output equality, warm up each operation three times, and record
seven wall-clock samples and the median. Projection, sealed `activeLines` and
captured metadata `lines` are measured separately. Disk fixtures and capture are
prepared outside timing; `activeLines` includes metadata reads, parsing, seal
validation and all projections. Payload hashing and logging are not timed.

These are host/JIT benchmarks, not Android ART/watch measurements. Host medians
below 100ms do not establish the device's background-summary target. The legacy
oracle intentionally retains slow code and is compiled only for tests.
