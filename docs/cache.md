# File cache

`@podjs/framework/cache` exposes `FileCache<B>` and `createFileCache(options)`.
It owns serial disk maintenance/accounting, URL + sorted-header SHA-256 keys,
shared downloads, a bounded worker queue, reference leases, producer pins,
explicit flat-bucket clearing, and stale staged-file cleanup. It contains no
application bucket names, file extensions, labels, or eviction priorities.

```ts
import {createFileCache} from '@podjs/framework/cache';
const cache = createFileCache({
  root: 'assets', buckets: ['covers'],
  namespace: () => ({
    filename: key => `${key}.bin`, ownsFile: name => /^[a-f0-9]{64}\.bin$/.test(name),
    stagedTarget: name => /^([a-f0-9]{64}\.bin)\.download-[0-9]+$/.exec(name)?.[1],
    traverseDirectory: () => false, cleanupStaged: true,
  }),
  evictionPolicy(files, limit, pinned) {
    let bytes = files.reduce((n, f) => n + f.size, 0);
    return [...files].sort((a,b) => a.modified-b.modified).filter(f => {
      if (bytes <= limit || pinned.has(f.path)) return false;
      bytes -= f.size; return true;
    });
  },
});
const request = cache.acquire('https://example.test/cover');
const lease = await request.result;
// Decode/use lease.path before releasing it.
lease.release();
```

Options provide namespace rules, policy, optional `ports`, `limitBytes`,
`concurrency`, directory/file bounds and error strings. Defaults: 64 MiB,
2 workers, 1,000 directories per bucket and 50,000 accounted files. Default
ports use existing file services, SHA-256 and atomic `http.download` with a
16 MiB per-file cap. They do not validate media. Inject `validate` and an
application transport for different content/limits; ports must publish the
final path atomically and settle cancellation. Producers can retain their
existing hash + `file.commit` protocol and register pin/clear/cleared hooks.
Root/buckets/produced filenames and listed child paths are constrained; policy
cannot select an unscanned or pinned file. Host file services still enforce
app-root ownership. Callbacks must be trusted configuration, not user input.

`acquire(url, headers?, {bucket?, variant?})` returns `{result, cancel}`.
Header keys are sorted and header values are part of identity. Cancellation
before hash completion reserves nothing. Cancelling one shared consumer leaves
others running; cancelling the final consumer aborts the running operation or
removes queued work. Release is idempotent and never directly deletes a
completed file. Serial lease reservation prevents an eviction gap between stat
and decode. Maintenance pins leased/running jobs and producer paths, verifies
removal, then republishes usage. Errors never break the serial tail.

`clearBucket` retains the explicit existing flat-cache contract: producer clear,
cancel/settle bucket jobs, delete immediate files (including unrelated names in
that owned bucket), publish usage, producer cleared in finally. It rejects
subdirectories. It deliberately invalidates completed leases in the cleared
bucket; callers coordinate decoder shutdown. A hash not yet reserved when clear
runs can subsequently create new work; clear is not a global acquisition epoch.

`configure`, `maintain`, `inspect`, `subscribe` keep the prior accounting and
notification semantics. `stats()` is a fresh serial, read-only scan: no staged
cleanup, touch, deletion, publication or observer events. It returns accounted
files, total/bucket bytes, limit, current job refs, pin paths, activeWorkers,
queuedRequests and downloadHandles. Workers include stat/validation/cache-hit
work. Download handles remain attached through validation/touch. Neither count
is live network transfer progress. Stats contain no URLs or headers. Bytes are
disk file sizes, never RAM measurements. Concurrent producer writes/slot reads
are observations, not a transactional cross-service snapshot.

Verification: generic namespace/pinning/clear-failure tests; fresh-process
production default-port bridge test; consumer frozen-oracle differentials cover
shared headers, hash cancellation, queue cancellation, clear, staged files,
recursive preserved content, invalid inputs, eviction and image revalidation.
Device/runtime performance and media decoder acceptance are separate.
