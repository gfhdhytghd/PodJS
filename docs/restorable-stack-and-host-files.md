# Restorable stacks and host file adapters

Import `@podjs/framework/restorable-stack` for pure task envelopes. Each
`createRestorableStack` owns a namespace, version, saved/retained budgets and
valid-prefix recovery. Callers supply encode/decode, memory access, retained-state
sanitization, root validation and entity resolution. The helper preserves other
owners' namespaces. Prefix resolution takes either a synchronous boolean or a
`{result, accept}` step; it awaits only the caller's entity request, without
adding a microtask for routes that do not require one. `createRouteMemoryAllocator` has instance-local sequencing;
a caller's decoder validates IDs before `observe` reserves restored numbers.
Retained drafts are never copied into saved state by this helper. These APIs do
not implement route names, consent gates, persistent checkpoints or process-loss
acceptance. The navigation barrel also exports them, but the narrow entry avoids
loading Solid or route context when only serialization is needed.

`@podjs/framework/log-file` provides `createHostLogFile({path,
temporaryDirectory,chunkBytes,messages}, files?)`. Default ports use real host
file services. Reads validate offsets, total size and progress, UTF-8 is decoded
only after assembling the bytes, and writes stay below the 64 KiB bridge limit.
Replace uses a temporary file, hash and atomic commit before best-effort cleanup.
The caller still owns redaction, file names, retention, startup and output. A
failed commit rejects and preserves the old destination; no rollback beyond the
host commit contract is claimed.

`defaultCachePorts({maxDownloadBytes,validateImageDimension})` keeps the existing
16 MiB default download limit. Optional image validation decodes at the supplied
size and removes only the returned decoded path. App policies can retain a
smaller download limit without copying hashing/download/file-port code.

`services.images.decodeOperation` and `decodeRegionOperation` preserve the
underlying cancellable host operation. Results retain optional source dimensions,
content dimensions and decoder timing when the host supplies them. Existing
`images.decode(path,maxDimension)` still returns a Promise. No dimensions or
performance evidence are invented when the host omits these fields. Decode-file
ownership, sampled texture uploads and bounded cleanup queues remain with their
resource owners; these service facades do not unify their policies.
