# Bounded UTF-8 decoding and remote images

Import these opt-in APIs from `@podjs/framework/bytes` and `@podjs/framework/image`.

`decodeUtf8Chunk(bytes, start, {maxBytes?, fallback?})` returns `{text, next}`.
The default 16KiB budget backs off at most three continuation bytes to avoid
splitting a valid sequence. Call it repeatedly and yield between calls as your
scheduler requires. It tries `pod.decodeUtf8` when present, then the strict
portable decoder (malformed UTF-8 throws). `maxBytes` must be an integer >= 4.
A compatibility `fallback(bytes, start)` replaces the portable path when the
host is missing or rejects the slice. That callback owns its chunk size and
legacy malformed/truncated-byte semantics, including a next offset past EOF.
The framework does not name application dialects. `utf8ToString` remains strict.

```ts
import {decodeUtf8Chunk} from '@podjs/framework/bytes';
const part = decodeUtf8Chunk(bytes, offset);
consume(part.text);
offset = part.next;
// Await the application's scheduler before decoding another piece if needed.
```

`createImageLoader({sourceProvider, logger?, messages?, maxConcurrent?,
retainedBytes?, maxDimension?})` manages shared loads and GPU texture ownership.
Defaults are three concurrent acquire/decode/upload pipelines, an 8MiB retained
texture budget, and a maximum 512px power-of-two decode request. The budget
covers only completed, unbound textures; active textures are not included.
Accounting deliberately estimates `maxDimension * maxDimension * 4`, not actual
GPU memory. A zero retention budget disables texture retention. Dimension limits
must be powers of two in 1..512. Log output and error messages can be customized.

```tsx
import {createImageLoader, RemoteImage} from '@podjs/framework/image';
const loader = createImageLoader({
  sourceProvider: {
    acquire(source) {
      // Return {result: Promise<{path, release()}>, cancel()} from your cache.
      // The provider owns directories, billing, authentication and downloads.
      return cache.acquire(source.url, source.headers);
    },
  },
});
<RemoteImage loader={loader} source={{url, headers, namespace: 'thumbnails'}}
  width={100} height={80} fit="cover" onSize={(w,h) => recordSize(w,h)} />;
```

The provider is required; default limits, display metrics, host services and
texture upload use production implementations without optional injection.
Sources can add provider-specific fields through the generic source type.
`namespace` must identify every acquisition policy not already captured by URL,
local file path or headers (e.g. cache bucket). Those fields plus requested decode
size form the sharing key. Providers must resolve a lease or reject on cancellation;
late success is released without decoding. A `filePath` bypasses acquisition and
remains caller owned. Decode output is loader owned, except when the native
texture upload API consumes it on success or failure. Byte upload is used only
when native file upload is unavailable.

`loader.bind(props, node, onSize?)` is available for lower-level integrations.
Release functions are idempotent. `releaseRetained()` frees only unused textures;
`stats()` reports active/queued/cached counts. `dispose()` detaches active nodes,
cancels work and frees retained/active textures; subsequent binds throw. Dispose
before replacing the host, then construct a new loader. Pending host operations
must eventually settle for asynchronous cleanup to complete. Image fit and
intrinsic-size handling match the original component; framework geometry uses
inline styles so it does not depend on application class scanning.
