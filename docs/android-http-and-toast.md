# Android response downloads and transient messages

`http.download` requires `net.download`. Callers that handle JSON API errors can pass `acceptHttpErrors: true`; the result then preserves the response status and reads the error body into the same bounded private output file. Existing callers continue to reject non-success responses by default. `maxBytes`, temporary-file lifecycle, redirect credential checks and cancellation apply to error bodies as well.

`timeoutMs` controls connection establishment and defaults the read timeout (valid range 1–120000ms). Optional `readTimeoutMs` independently permits 1–600000ms for slow API responses. This is a per-read socket timeout, not a whole-request deadline. WatchRSS uses 15000/600000ms to match its native AI summary client.

The default connection/read timeout is 30000ms. `callTimeoutMs` sets the
whole operation deadline (default 120000ms, range 1–600000ms), including manual
redirects. The Android client enables OkHttp fast fallback and inherits its
connection-failure retry default; it does not add application-level retries.
A read timeout alone is not evidence that these defaults are too short.

The convenience `http.download(url, path, maxBytes)` uses host defaults and adds
no application request headers. For source-specific request policies, use
`hostOperation<DownloadResult>("http.download", {url, path, maxBytes, headers,
timeoutMs, readTimeoutMs, callTimeoutMs})`. User-Agent and Referer belong to the
application policy. Preserve the returned operation's cancellation and remove
scratch files in `finally`. An HTML access-denied/challenge response must not be
made into a feed by relaxing the XML parser: check HTTP status, response type
and request policy before changing parsing semantics.

`ui.toast` requires the separately advertised `system.toast` capability. `hostOperation("ui.toast", {message: "…"})` displays an Android short Toast on the main thread. Android-watch and Wear OS advertise it through their shared Android runtime; other hosts do not advertise support. Pending requests can be cancelled before dispatch. Once a Toast has been shown, cancelling the completed request does not retract it.

Verification: `PodHttpServicesTest` covers optional error-status/body retrieval, retained default rejection, byte limits, read-timeout bounds and existing cancellation/download behavior. Framework service tests verify Toast capability gating and dispatch; `android-capabilities.test.ts` checks the advertised ABI capability order. WatchRSS's reader-summary device fixture exercises real loopback 401 refresh, 402 handling, retry, fold/unfold and navigation cancellation against these services.

The portable runtime additionally provides optional pure-computation `pod.decodeBase64(input)`. It returns an owned `Uint8Array` for canonical standard Base64 (padded or unpadded), or no value for inputs handled by the SDK's legacy fallback. The shared byte codec probes it at call time, so old hosts keep working. Large image/file-service responses avoid synchronous JavaScript per-byte decoding on the UI thread. This adds no platform permission or C ABI requirement.

### Additional exact UI font sizes

The compiler and core now support 8px and 23px UI fonts in appended slots 29/30 (regular) and 31/32 (bold), raising `MAX_FONT_SLOTS` to 33. Slots 0–28 retain their indices. WatchRSS uses these for the native usage-chart dates and token-total heading. Bundles that use the new slots require the corresponding updated runtime; package the runtime and bundle together.

The runtime also provides optional `pod.encodeBase64(bytes)` for a `Uint8Array`.
It encodes only the view's byte range, returns standard padded Base64, and does
not mutate or retain the input. `@podjs/framework/services.bytesToBase64` selects
this path when present and otherwise uses the portable byte codec. The empty
string is a valid native result. This requires no platform permission or C ABI
change. Runtime tests cover all byte values, view offsets, empty input, padding
and input preservation; framework tests cover native and older-host paths.
