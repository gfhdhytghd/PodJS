# Watch Notebook

A standalone PodJS demo with 100 bundled sample notes. It uses no WatchRSS code,
accounts, signing material or takeover configuration. Notes and logs belong to
`dev.podjs.notebook`; removing a note changes only this demo's KV data.

After bootstrapping framework dependencies, run here:

```sh
bun install --offline
bun run typecheck
bun run test
bun run build
```

The example is deliberately small and uses the framework's existing presets:

| Feature | Wiring |
| --- | --- |
| Round layout | Host metrics/safe insets intersect a centered inscribed square; all page backgrounds are black. |
| Windowed list | `createWindowedList` mounts visible/overscan rows over a kinetic scroller. Vertical touch and rotary scroll. |
| Swipe action | Swipe a card left or long-press it to remove a note; `SwipeActionCard` owns horizontal arbitration. |
| Confirmation | `WatchConfirmDialog` cancels/confirms removal; writes KV only on confirmation. |
| Scroll restore | `ScrollRestoration` reads a validated KV anchor and saves when opening detail or entering background. |
| Detail/Back | Local selection opens a detail layer; `BackMotion` animates both the Back button and system Back. |
| Image/cache | Press “Load online image” on detail to request an HTTPS sample image through `RemoteImage` and a 4 MiB file cache. Errors show “Image unavailable”. |
| Startup | `createStartupTrace` records JS mount/content-ready phases through the logger. These are not GPU timings. |
| Logging | `FileLogger` redacts and serializes a small persistent KV-backed log, with trimming at 8 KiB. |
| KV | Validated note records, scroll anchor and the demo log use namespaced keys. |

The image endpoint is `https://picsum.photos/seed/podjs/128/128`; it is optional,
requires network and host image decoding, and may be unavailable. No image is
requested during build/tests or before the user presses the load button. Tests
cover round geometry, BackMotion timing, saved-data validation, window bounds,
scroll anchors and production-default resource construction with a synthetic KV
host. They do not prove physical gestures, service decoding or visual behavior.

The CLI wrapper supplies this project's absolute path. Build outputs are in
`dist/projects/dev.podjs.notebook/android-watch/` in the framework checkout.
See [getting started](../../docs/getting-started.md) for native packaging/signing.
`bun run test:scaffold` from the framework root verifies both this app and a
fresh generated app offline. No command in that script installs to a device.
