# Android Activity state

The bundled Android and Wear OS Activities expose `application.activityState` through `host.lifecycle`:

```ts
const prior = await application.activityState.read();
await application.activityState.write({
  saved: { ...prior.saved, screenDraft: draft },
  retained: { ...prior.retained, screenPage: page },
});
```

`write` atomically replaces both JSON object channels. A single application owner should coordinate updates and preserve unrelated keys. The host snapshots objects as JSON strings, so modifying a returned object does not change stored state. Each channel has an independent limit on its serialized JSON UTF-8 size: `saved` is limited to 64 KiB and `retained` to 896 KiB. Invalid or oversized writes leave both prior channels unchanged; unused capacity in one channel cannot increase the other channel's limit.

The retained limit leaves room for the saved channel and service reply envelope within the runtime's 1 MiB host-event limit. The reply embeds both channels as JSON objects, without another layer of string escaping. The limits include keys, punctuation, and escaped characters, not just the original text's bytes; callers retaining large text should account for its encoded JSON size.

`saved` is included in Android's saved-instance Bundle. `retained` is passed only through `onRetainNonConfigurationInstance`; it is not included in the Bundle. A normal fresh launch begins with empty objects. Neither channel writes application KV, databases, or files. Persistent settings and confirmed consent belong in their existing stores.

The Activity restores both channels before booting the guest. Before capturing them, the runtime pumps pending service work and drains host effects. Malformed restored JSON produces an error on `read`; it is not silently treated as valid draft data.

The independent 64 KiB / 896 KiB boundaries are covered by SDK state tests, including exact-size writes, one-byte UTF-8 overflows, JSON escaping, snapshot isolation, and the combined service reply size. These tests compile against Android API 36 and have run on the JVM using the SDK's Android JSON implementation. An isolated Android API 30 application also exercised the public JavaScript API at both exact limits together, compared every ASCII payload character, recreated its real Activity, read the restored payloads before any reseeding, and rejected each one-byte overflow without changing either channel. Its saved Bundle contained only the 64 KiB saved channel. This proves that device round trip and configuration restoration; it does not establish process-death recovery or coverage on other Android versions.

Verified on the Android watch with an isolated test package: public JS read/write, real `Activity.recreate()`, recovery of both channels, exclusion of retained state from the Bundle, and empty state on a subsequent fresh launch. Bundle-only restoration was checked at the host state boundary, not by killing the device process. Wear OS Java compilation passed; a Wear OS device was not exercised. WatchRSS onboarding now uses these channels for agreement/error/warning drafts and retained page/network state. Its isolated device tests cover Activity recreation and fresh-launch reset without persisting consent. Customization demonstration values and their scroll offset are also covered by isolated Activity recreation tests; ordinary branch exit resets them. Pending Bundle-only customization restoration is model-tested, not verified with actual process death. Legal navigation restoration remains incomplete.
