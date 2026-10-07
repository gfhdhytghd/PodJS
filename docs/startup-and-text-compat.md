# Startup gates and Compose font compatibility

`@podjs/framework/startup` has no process-wide gate or phase registry. Create
instances per application mount (a bundle-lifetime singleton is also supported).

```ts
import {createStartupTrace, createFirstScreenGate} from '@podjs/framework/startup';
const trace = createStartupTrace({sink: message => logger.debug(message)});
const gate = createFirstScreenGate();
trace.mark('content.begin'); // once per phase per trace
await trace.step('load', loadContent); // logs in finally, preserves result/error
// Content is loaded, not necessarily rendered or presented:
gate.ready();
await gate.wait(3000);
// On unmount:
gate.dispose();
```

The trace defaults to `performance.now()` with `Date.now()` fallback; an optional
`clock` returns milliseconds. Its default sink is silent. Each mark rounds elapsed
milliseconds; each step logs duration and total elapsed with separate clock reads.
Phase names, log tags, localized text and logging/redaction policy belong to the
application.

The gate defaults to `services.delay`, a host wall-clock timer, not QuickJS's
zero-delay `setTimeout` polyfill. `wait(maxMs = 3000)` races content readiness with
that timer and resolves on timer success **or rejection**. A timeout does not mark
the content ready. Every wait still creates a timer even after ready, and ordinary
readiness does not cancel losing timers; this preserves existing scheduling.
`onReady(alreadyDelivered)` runs synchronously before delivery, including repeated
ready calls. `onResolved` runs once in a microtask after delivery. Neither callback
is evidence of GPU presentation. Explicit `dispose()` is idempotent, suppresses
future callbacks, resolves outstanding waits and cancels outstanding timers;
subsequent waits resolve without creating timers. The optional `delay` port returns
a `HostOperation<void>` and supports isolated tests.

`@podjs/framework/text-compat` exports `createComposeFontScale()` and the shared
`composeSpToPx`, `composeDpToSp`, `composeLineHeightPx` convenience functions. This
is specifically **AndroidX Compose 1.7.1 on Android** compatibility, not a universal
SP conversion. Density is PX/DP; fontScale is the configuration's dimensionless
Float. The converter preserves Float32 operations, cache-key collisions (notably
1.04/1.05), interpolation history and the line-height threshold. Create a separate
instance to model a fresh process; convenience functions share one cache. Source
provenance and the Apache-2.0 notice are retained in `src/text-compat.ts`.

Tests include fixed raw Float-bit results from the real 1.7.1 AARs. Those fixtures
are offline host evidence, not live font rendering acceptance.

Identical KV checkpoints compare canonical serialized bytes to the authoritative
file read under the existing cross-process lock. If those bytes are identical,
they preserve the file instead of repeating its write, fsync, rename and directory
fsync. Missing files and changed/noncanonical bytes retain the original durable
commit path. Other handles' changes are still reloaded on every operation;
there is no delayed write or process-local stale-value shortcut.
