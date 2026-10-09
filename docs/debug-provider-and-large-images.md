# Debug provider output and bounded large-image decoding

`PodDebugExternal.Provider.dump` is called on Android's main thread. Guest
subscription delivery and replies also pass through that thread. The provider
therefore duplicates the supplied descriptor and returns without waiting for
JS. A daemon worker owns the duplicate, waits at most 1500 ms, writes one
result, and closes its writer/descriptor. Android may close the original
immediately after return. A bounded 32-worker gate and the existing 32-request
queue prevent an unbounded debug queue. Reply, expiry and unsubscribe/close
share a synchronized completion guard; late replies cannot change a completed
result. Provider deadlines are 1500 ms, below TransferPipe's 2000 ms deadline.
Activity aliases and receivers retain their existing 4000 ms deadlines and
DUMP permission declarations. Generated bridges remain in debug or explicitly
selected verification builds, never ordinary release runtime source sets.

`defaultCachePorts` accepts a constant `maxDownloadBytes` or a callback from the
cache's destination path to a byte limit. The callback is application policy;
URLs and domains are not rewritten or interpreted by the framework. Every
resolved bound must be a positive safe integer; Android HTTP additionally
caps downloads at 64 MiB. `http.download` reads at most 32 KiB at a time into an
atomic temporary file. Limits apply to bytes actually streamed, including
responses without Content-Length. Cancellation and call deadlines cancel the
OkHttp call, prevent publication and remove partial files. The image source
never passes through JS as a complete network response or full file.read.

Sampled image overviews and bounded region decoding allow source edges up to
65535 pixels and at most 400000000 source pixels. Output remains capped at
512 per edge; overview sampling uses powers of two before allocation and stops once the
source edge is covered (including the 1x1 validation case), and
region dimensions are bounded by 512 times the sample size. These limits are
independent of source URL or publisher. Unsampled full-image decoding retains
its original 16384-edge and 64*1024*1024-pixel guard. The current public overview
API does not offer an unbounded full-size output mode. Tile output, row buffers,
four decode jobs and the retained overview remain bounded. Compressed-format
metadata and decoder workspace can still vary; the larger source guard is an
anti-abuse ceiling, not a guarantee that every format or device can decode a
400-million-pixel source. Decoder exceptions still propagate to the consumer.

Cache leases cancel outstanding downloads when their final consumer leaves.
The tiled-image cleanup cancels its initial request and tile operations,
releases textures and decoders, and keeps the source lease until in-flight
operations finish. Completed cache files follow the application's existing
size/eviction policy; cancellation deletes unpublished partial files, not
completed reusable originals. Device acceptance must measure the actual UI,
zoom/tiles and process memory; host tests alone do not establish watch memory
or network behavior.
