# S1 byte attachments (host ABI addition)

## Decision and scope

QuickJS is embedded by `rquickjs 0.12.2`. The framework emits JSON
`service.request` effects. Rust queues those strings, JNI polls them, and
`PodServices` submits work to independent Java executors. Replies return through
the main-thread event queue. `TypedArray::as_bytes()` borrows the exact guest view,
including byte offset; The byte bridge transfers owned Vec storage to
QuickJS with a detach-safe external ArrayBuffer finalizer. The pinned engine
invokes that finalizer again with a null pointer after detach; the bridge ignores
that second callback. It does not use rquickjs 0.12.2's default Vec finalizer for
byte replies.

Use **single-owner native byte attachments**, addressed by opaque, guest-local,
monotonically increasing handles. This is a bounded copy bridge, not a claim of
end-to-end zero-copy IO. The JSON lane carries metadata and a handle only. No
payload is Base64-encoded, JSON-stringified, or expanded into an array of numbers.

A shared JS/JVM buffer would let the guest mutate an in-flight write. Borrowing a
QuickJS ArrayBuffer pointer across Java worker threads would also cross the
runtime's single-thread ownership and GC boundary. Pinning JNI arrays or direct
buffers until asynchronous IO finishes needs another lifetime/teardown protocol.
Snapshot ownership is simpler, bounds retention, and preserves the old API's
call-time snapshot semantics. Native FileRef pipelines remain a later stage.

## Contract

- `pod.byteBridgeVersion()` returns 1 only when the embedding host explicitly
  enables byte services. Other embeddings return 0 and use the old wrappers.
- `pod.putBytes(Uint8Array)` snapshots the exact view and returns a positive handle.
  Zero denotes full/exhausted attachment capacity. Oversized/detached views are
  rejected; a valid zero-length view receives a real handle.
- `pod.takeBytes(handle)` consumes a handle and gives JS an owned `Uint8Array`.
  Unknown/repeated handles return null/undefined. `dropBytes` is idempotent.
- 64 slots per guest, at most 65536 bytes per slot: retained payload <= 4 MiB.
  Handles never wrap/reuse; exhaustion fails closed at INT32_MAX. Destruction
  drops the whole map. No global registry spans applications or guest lifetimes.
- `.bytes` service aliases reuse the legacy method's capability and sandbox:
  `stream.read`, `stream.write`, `file.read`, `file.write`,
  `file.resumable.stage`, `file.publishBytes`. No new permission is granted.
- `byteHostOperation` accepts one optional input view. Byte replies have a `bytes`
  field alongside the unchanged metadata. `byteStreams.readBytes/writeBytes` and
  `fileChunks.readBytes/writeBytes/appendBytes` select the alias only when the
  host advertises version 1. Without it, they adapt the unchanged legacy API.
- Existing `read/write`, `fileChunks.read/write/append` and default
  `VerifiedChunkStore(host)` retain Base64 behavior. Injecting its second callback
  opts chunk staging into byte services. Runtime failure never replays a mutation
  through the legacy API.

## Ownership and copies (N <= 64 KiB)

JS -> Java worker:

1. The synchronous QuickJS binding copies the view into a Rust Vec (N bytes).
2. Main-thread JNI copies that Vec directly into a Java byte[] with
   SetByteArrayRegion, then consumes the handle. There is no intermediate C++ Vec.
3. The worker owns that Java array until IO completes or cancellation discards it.

Two payload copies; <= 3N live bridge representations including the caller's
view (the original ArrayBuffer may of course be larger than its view). IO and
filesystem implementation copies are outside this count.

Java worker -> JS:

1. Read allocates its bounded Java byte[]. A short read trims it once; a full read
   reuses the array. EOF uses an empty array.
2. On the main thread, GetByteArrayElements provides JNI access; Android may pin
   or copy. Rust snapshots that region into one Vec and JNI releases with
   JNI_ABORT. This is one mandatory copy plus a JVM-dependent JNI copy.
3. `takeBytes` moves the Vec into QuickJS with that detach-safe finalizer. There is no second payload copy at the
   Rust -> QuickJS boundary.

Read/short-read allocation is separate from bridge transfer. The bridge retains
<= 3N payload bytes during inbound JNI access, plus the original max-sized
short-read array until GC. It does not guarantee immediate JVM garbage collection.
Per-request metadata, map nodes, typed-array headers, queue overhead and application
buffers are additional. At 64 KiB the old Base64 representation is 87384 characters;
its encoder/decoder and JSON copies are eliminated only from the new bridge.

## Lifetime, concurrency and errors

All QuickJS/attachment-map and JNI calls execute on the owner main thread. Java
workers never receive a JS pointer or Rust borrow. Metadata only crosses the old
queue. Input handles are consumed before submitting IO. The JS promise also
idempotently drops its input handle on rejection, admission failure or completion.
An exception while emitting a request follows the same cleanup path.

Replies hold Java arrays until the main-thread callback, which first checks the
service lifetime. Only then does it allocate a native handle and post a completion.
The event listener consumes byte attachments **before** checking the pending
promise table, including cancelled/late completions. If posting fails, Java drops
the handle. Guest destruction releases queued completions and all native slots.
A full reply table returns `busy`; missing handles return `invalid_argument` on
input or `invalid_bytes` on output. The original stream timeout/cancel/close and
file fencing/quota/hash/durable commit rules still apply. A cancelled mutation
may already have committed, exactly as with the original service transport.

## Native storage

`PodSyncFileReceiver.commandBytes` and its appended C ABI take bounded JSON chunk
metadata and a raw byte[] separately. They call the **same** Rust
`FileReceiver.receive_chunk`, under the **same** process-independent application
quota lock. Hash checks, fsync, restart recovery and manifest validation are shared.
Resumable staging, incoming file storage and native snapshot creation use this
entry point; they no longer encode Java bytes just to decode them in Rust.

`PodSyncFilePump` and `PodSyncOutgoingFiles` retain Base64 at their authenticated
v1 wire boundary. Those are wire codecs, not guest/host byte bridges. Changing
those bytes would break existing phones. The old `PodBluetoothServices` adapter
also remains available; the application's RFCOMM and WebSocket byte IO uses
`PodByteStreamServices`. S1 does not introduce B2 wire negotiation or change any
v1 frame, account rule, business receipt, quota, or production switch.

## Verification and benchmark

Run the framework test script, `cargo test --workspace`, and JVM
`:runtime:testDebugUnitTest` under the repository's flock/capmem limit. The new
Rust and JS tests include 0/1/65536-byte inputs, subviews, invalid types and sizes,
single consumption, capacity, cancellation, late replies, legacy differential
checks, and repeated release. JVM tests exercise actual stream implementations
without Bluetooth, adb or an emulator. FFI differential tests use private temp dirs.

The opt-in `byte_bridge_host_benchmark` uses the real QuickJS byte bindings and the
old native Base64 bindings with JSON envelopes. It measures 2000 64-KiB roundtrips
in alternating order after warmup. It counts cumulative requested allocations
(Rust and QuickJS configured with RustAllocator), including reallocation requests;
it does not report live heap, Android JVM allocations, JNI timing, PSS or radio
throughput. Run it alone with `cargo test -p podjs-runtime
byte_bridge_host_benchmark -- --ignored --nocapture --test-threads=1`.
