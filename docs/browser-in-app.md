# Embedded browser (Android / Wear OS)

```ts
await browser.open(url, {
  mode: "inApp",
  scaleMode: "standard", // standard -> expanded -> shrunk -> standard
  theme: {dark: true, backgroundColor: "#101010"},
}).result;
```

`browser.open(url)` remains the HTTP(S) external-browser API. Passing options
uses `browser.openInApp`, requiring the separate `system.browser.inApp`
capability. Older hosts fail with `unsupported`; there is no external fallback.
The promise resolves when the browser has opened, not when the page has loaded
or the user has closed it. Cancellation follows HostOperation semantics: it
stops pending delivery but does not undo an already opened browser.

Embedded URLs may be HTTP(S), `file:///` URLs, or absolute filesystem paths.
Surrounding whitespace is trimmed; Android converts paths with `Uri.fromFile`
so spaces, # and non-ASCII names are encoded. Local paths retain Android process
permissions. Executable/data/content URLs are rejected. Remote-page navigation
cannot switch to local files or external apps. HTTP(S) opening checks INTERNET
and VALIDATED network capabilities first (`offline`, with the native Chinese
message). Missing/broken WebView returns `webview_unavailable` and a Chinese
provider-install/enable message. A second active embedded browser returns `busy`.

The owner Activity hosts a fullscreen dialog. System back, predictive back,
watch STEM_1/STEM_2 keys and native-style right swipe first navigate WebView
history; with no history they dismiss. Horizontal start/commit thresholds match
WatchRSS BaseWatchActivity. Three scale modes change the centered viewport,
not CSS zoom: 18 dp horizontal padding, 18 dp round / 8 dp rectangular vertical
padding; expanded fills width, shrunk fits the viewport diagonal to screen width.
The scale button and animated progress ring are native Views.

Theme defaults to dark black. `backgroundColor` accepts Android `#RRGGBB` or
`#AARRGGBB`. Supported WebKit force-dark strategy, force-dark and algorithmic
darkening follow the native implementation. Optional `textZoom` (integer 50–200)
and `fontFamily` (`sans-serif`, `serif`, `monospace`) set WebSettings defaults;
site CSS can override its font. Omitting them preserves site fonts, as native
WatchRSS WebView does. This is a theme snapshot, not a live reader-preset binding.
No custom app font asset injection is performed.

Owner pause/resume is forwarded to WebView. Dismissal, service close and owner
destruction stop loading, cancel progress animation, detach and destroy the
WebView, and unregister callbacks. Cookies/site storage are preserved. Owner
recreation closes this transient dialog; the caller may reopen it with the
chosen options. Browser history and scale are not persisted across recreation.

Local tests cover protocol, capability gating, options and geometry; Android
instrumentation tests cover real settings, history and disposal. Compilation
alone is not proof of on-device rendering, gesture or provider behavior.
