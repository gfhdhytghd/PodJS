# Browser authorization status

`browserAuth.authorize(options)` still resolves `{cookie, origin}` only when the
user presses Done and every required cookie is nonempty. Status observations do
not establish authentication and never trigger automatic cookie completion.

```ts
import {browserAuth, DOUYIN_AUTH_STATUS_SCRIPT} from "@podjs/framework/services";

const operation = browserAuth.authorize({
  url: "https://www.douyin.com/chat?isPopup=1",
  cookieOrigin: "https://www.douyin.com",
  requiredCookieNames: ["sessionid"],
  qrExtractionScript: DOUYIN_AUTH_STATUS_SCRIPT,
  onStatus(event) {
    // Update app state; the host dialog also presents QR and verification hints.
    // qr.imageBase64 is bare base64, without a data-URL prefix.
  },
});
const {cookie, origin} = await operation.result;
```

Use the same user agent as the native app if the site requires its desktop page.
The framework does not inject a service-specific user agent.

With `onStatus`, the SDK requires `system.browser.auth.status` and sends
`browser.authorizeWithStatus`. An older host rejects before any request is sent;
the distinct wire method also prevents silent downgrade. Without `onStatus`, the
legacy `browser.authorize` request, image-string extraction, and cookie result
remain compatible.

Events are scoped to the operation's request ID:

- `qr`: `kind: 'login' | 'secondVerify'`, `mime: 'image/png' | 'image/jpeg'`, and
  `imageBase64`. PNG login and JPEG second verification are distinct.
- `qrCleared`: discard the previous code, including when entering webpage verification.
- `pageState`: `loading`, and optional `loginPanelVisible` from the extraction script.
- `verifying`: `active` indicates that the verification controls need the WebView.
- `cancelled`: delivered before cancellation rejection, including local `cancel()`.

Success/error still settles the promise. No event arrives after settlement or
local cancellation; repeated cancellation is inert. Listener exceptions cannot
prevent settlement. Host snapshots deduplicate unchanged state, including QR
payloads. The runtime service pump delivers these events before their terminal
result even without a UI frame. Navigation invalidates pending extraction and QR decoding. Cancellation
of a queued/busy request cannot cancel another authorization dialog.

An optional extraction script runs every 750 ms and returns either a legacy
PNG/JPEG data URL/null or this object (not a JSON-encoded string):

```ts
({
  qr: {kind: "secondVerify", mime: "image/jpeg", imageBase64: "..."}, // or null
  verifying: false,
  loading: false,
  loginPanelVisible: true,
})
```

The bundled Douyin script reads the native login-panel selectors, prioritizes
JPEG over PNG, and returns `qr: null, verifying: true` when a `div` with ID
`uc-second-verify` exists. That state reveals the interactive WebView. A
malformed/absent QR clears the old overlay; status images are bounded to 256 KiB of base64, within the runtime's 1 MiB
single-event limit even with JSON escaping. Legacy image-only extraction retains
its existing 4 MiB data-URL bound.
The host additionally decodes and re-encodes valid QR content for its overlay;
an event describes the extracted image, not proof that its pixels rendered or
that the user scanned it. No page text, cookies or navigation URLs enter events.

Validation for F4 covers TS transport/script fixtures and compiled Android
instrumentation tests. Live Douyin DOM, rendering, touch interaction and physical
second-verification acceptance require device testing.
