# Android staged file publication

The Android watch/Wear OS `data.files.chunks` service now supports bounded writes and atomic publication through `fileChunks` from `@podjs/framework/services`.

1. `createTemp(directory)` exclusively creates a `.pod-write-*.part` file in a private relative directory and returns `{path}`. At most eight owned temporary files are open for a service instance.
2. `write(path, dataBase64)` resets/replaces a file with one chunk. `append(path, dataBase64, expectedOffset)` requires an existing file with exactly that byte length. Chunks are bounded to 256 KiB. Appends use the `file.write` method with `append: true`; they cannot request `atomicReplace: true`.
3. `hash(path)` streams SHA-256 with a 64 KiB buffer and returns `{size,sha256}` without transporting file contents to JavaScript.
4. `commit(path, target, expectedSize, expectedSha256)` rechecks the source length and hash, syncs it, and uses an atomic filesystem move to publish the target. An identical existing target is reused and the staged file removed. A mismatched staged file leaves the old target intact. There is no non-atomic move fallback. It returns `{path,size,sha256,reused}`. Source and target must be different private paths; expected size is an exact nonnegative safe integer and hash is 64 hexadecimal characters.
5. Remove the staging path in a `finally` block. Committing or removing it releases its tracked slot. Clean service shutdown removes owned temporary files; an undelivered cancelled `file.createTemp` result is discarded. Process death can leave temporary files, so this is not a crash garbage collector.

The caller must publish database references only after commit succeeds. Cancellation after the filesystem commit may leave a valid unreferenced file; deleting it without checking other references is unsafe. These APIs do not make filesystem publication and a later SQLite transaction one atomic transaction.

Evidence: four OWW242 device tests cover a file exceeding 2 MiB, identical target reuse, failed append/commit preservation, changed staging content, empty content, staging limits and shutdown/cancelled-result cleanup. WatchRSS uses an independent unchanged Kotlin reference to compare its v2 key/content hashes and Java UTF-8 encoding. This does not qualify phone transport, all filesystems, power-loss recovery or large-file performance.


## Gzip and incremental UTF-8 text reading

`fileChunks.gzip(path,maxOutputBytes)` and `gunzip(path,maxOutputBytes,allowPlain)` produce an owned temporary file and return `{path,size,sha256}`. The output bound must be an exact nonnegative integer no greater than 32 MiB; the SDK defaults to that bound. Both operations stream through bounded buffers. The bound applies to compressed output for gzip and decompressed output for gunzip. A failed operation deletes its output and preserves its input. Remove delivered output explicitly; normal shutdown and cancelled undelivered results also clean it up.

Gunzip defaults to `allowPlain: false`. With true, it reopens the original file as plain bytes only if the GZIPInputStream constructor cannot read a gzip header. A later CRC/decompression error is still an error. This matches the native WatchRSS body decoder's compatibility behavior.

`openText(path)` returns `{id}` for a buffered Android UTF-8 reader. `readTextChunk(id,maxChars)` returns `{text,eof}`; maxChars is 1–32768 and defaults to 16384. A high surrogate at the end is completed with one extra UTF-16 code unit so a code point is not split across bridge replies. EOF is reported by an empty final read and closes the reader. `closeText(id)` explicitly closes a remaining handle and returns `{closed}`. There are at most eight readers per service instance. Closing the service or cancelling an undelivered open result closes owned readers. A caller abandoning a read must close the reader rather than treating a lost reply as an unchanged stream position.

Device evidence: eight gzip outputs match an unchanged production Android reference byte for byte; generic tests cover CRC failure, exact output limits, plain fallback, malformed UTF-8 replacement, split-pair prevention and reader cleanup. The captured Android and desktop JVM corpus differs at gzip header byte 9 (00 vs ff). The remaining bytes match in these eight cases; this observation is not a cross-platform gzip canonicalization rule. WatchRSS's separate installed verification application exercises the real JS→SDK→host path through encoding, rebuilding, atomic content publication and reader readback. It does not qualify production phone transfer or reading performance.
