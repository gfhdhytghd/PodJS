# Site challenge cookies

`system.browser.challenge` is an optional Android/WearOS capability.
`browserChallenge.cookies(options)` returns only the selected cookie names from
CookieManager when their token and user agent match the previous acquisition.
Expired cookies disappear through CookieManager; no cookie value is copied into
app KV. SharedPreferences keeps only a token/user-agent fingerprint by origin
and cookie-name set. `acquire(options)` always loads the supplied URL in a hidden
WebView and requires new selected cookies before completing.

```ts
import {requestWithSiteChallenge, isScriptCookieChallenge} from '@podjs/framework/site-challenge';
const operation = requestWithSiteChallenge(
  {url, userAgent, cookieNames: ['site_challenge_token'], timeoutMs: 60000},
  cookie => downloadWithHeaders({url, headers: {'User-Agent': userAgent, ...(cookie ? {Cookie: cookie} : {})}}),
  response => isScriptCookieChallenge(response.body),
);
const response = await operation.result;
```

The application supplies the site's public URL, cookie names, HTTP operation and
response classifier. **WebView and HTTP must use the same user agent**: some
sites bind tokens to it. A mobile user agent may also redirect desktop feed URLs.
The framework contains no business endpoint or site-specific user agent.

The operation performs a cached-cookie lookup and ordinary HTTP request, then
at most one browser acquisition and HTTP retry if the response is a challenge.
On older hosts, unsupported cookie lookup allows the ordinary HTTP attempt;
acquisition still rejects if required. Leave the capability out of an app's
mandatory capability list when this compatibility behavior is desired.

Cookie extraction is restricted to the requested names, including HttpOnly
cookies; unrelated login cookies stay in CookieManager. The browser executes the
site's own JavaScript, has no native JS bridge, cannot access local files/content,
rejects cross-origin main-frame navigation and TLS failures, and accepts only
HTTPS URLs or loopback HTTP test fixtures. Existing browser data is not cleared.
Acquisitions are serialized in a bounded eight-operation queue. A 60-second
default deadline includes time in the queue; callers can use at most 120 seconds.
Cancellation removes only its request and destroys the active WebView when owned.

`isInteractiveSiteChallenge(body)` recognizes common CAPTCHA HTML. Callers may
return `'interactive'` from their response classifier to reject immediately with
`interaction_required`. Visible common CAPTCHA controls appearing during WebView
loading also reject. Other unsatisfied challenges time out. No CAPTCHA interaction
or solver is provided; the HTTP retry remains authoritative about success.

Tests use a local fake server and cover cache reuse, expired tokens, one retry,
source errors, unsupported hosts and cancellation. The production default-port
test exercises native service messages. Android instrumentation includes a real
hidden WebView script-cookie/reload fixture, then ordinary HTTP retrieval, cache
recreation, timeout and queued cancellation. Compiling those tests establishes
build coverage; a device run is required to establish WebView execution.
