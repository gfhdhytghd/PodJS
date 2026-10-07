`@podjs/framework/bytes` provides `encodeUtf8`, `encodeBase64`, `decodeBase64`
and their piece generators. These pure APIs are also safe in background bundles.
UTF-8 accepts `strict` (default), `question` (Java-compatible lone-surrogate
replacement), and `replacement` (U+FFFD). Basic Base64 rejects whitespace,
URL-safe symbols and malformed padding, accepts complete omitted padding, and
ignores unused trailing bits. This differs from the legacy permissive
`base64ToBytes`; that API retains its contract.

`@podjs/framework/async-bytes` provides `encodeUtf8Async`, `decodeUtf8Async`,
`encodeBase64Async`, and `decodeBase64Async` also reexported by `bytes`, for cancellable jobs. More than one
piece yields through the realm scheduler installed by `services` (`runtime.delay`),
or a portable timer. The codec modules have no service imports.
Callers can pass `{cancelled: () => closed, yield: scheduler}`. Cancellation is
checked before work and after each yield. A single piece completes without a
timer. Returned values own their storage; callers should keep input stable while
an asynchronous job runs.

SDK pieces are at most 4096 UTF-16 units, 3072 Base64 source bytes, or 4096 Base64/
UTF-8 source bytes. UTF-16/UTF-8 boundaries preserve pairs/code points; Base64
boundaries preserve groups. New optional `pod.encodeUtf8Chunk`,
`pod.encodeBase64Chunk`, and `pod.decodeBase64Chunk` functions accelerate each
piece in Rust. Presence detection is independent of manifest permissions; an
absent/rejecting bridge uses the identical JS codec. The async decoder reuses
`pod.decodeUtf8`. Direct bridge functions reject oversized inputs, returning an
absent value; SDK users should use the bounded public APIs.

The synchronous convenience functions collect all pieces in the caller's turn.
Use asynchronous APIs or consume generators with a scheduler for large data.
Final output assembly still copies the result, and JSON parse/stringify are
separate work; these APIs do not claim constant total memory or preempt those
operations. Background handlers use the same pure module; import policy remains enforced by
the background builder. Callers may supply their own scheduler where no timer
pump is available.
