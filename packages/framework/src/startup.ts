import { delay as hostDelay, type HostOperation } from "./services.ts";

export interface StartupTraceOptions {
  clock?: () => number;
  /** Receives formatted timing messages. Phase names and log tags belong to the caller. */
  sink?: (message: string) => void;
}

/** Instance-local elapsed timing; this measures JS work, not GPU presentation. */
export function createStartupTrace(options: StartupTraceOptions = {}) {
  const now = options.clock ?? (() => globalThis.performance?.now?.() ?? Date.now());
  const sink = options.sink ?? (() => {});
  const origin = now();
  const seen = new Set<string>();
  function mark(phase: string): void {
    if (seen.has(phase)) return;
    seen.add(phase);
    sink(`${phase} +${Math.round(now() - origin)}ms`);
  }
  async function step<T>(phase: string, run: () => Promise<T>): Promise<T> {
    const began = now();
    try { return await run(); }
    finally { sink(`${phase} took ${Math.round(now() - began)}ms (+${Math.round(now() - origin)}ms)`); }
  }
  return { mark, step };
}

export interface FirstScreenGateOptions {
  delay?: (milliseconds: number) => HostOperation<void>;
  /** Called synchronously before delivery, including repeat ready() calls. */
  onReady?: (alreadyDelivered: boolean) => void;
  /** Called in a microtask following the first ready(). */
  onResolved?: () => void;
}

/** A content-ready gate, not a rendered/presented-frame signal. Create per app mount. */
export function createFirstScreenGate(options: FirstScreenGateOptions = {}) {
  const delay = options.delay ?? hostDelay;
  let resolve: (() => void) | undefined;
  const firstScreen = new Promise<void>(done => { resolve = done; });
  let disposed = false;
  const timers = new Set<HostOperation<void>>();
  void firstScreen.then(() => { if (!disposed) options.onResolved?.(); });
  function ready(): void {
    if (disposed) return;
    options.onReady?.(!resolve);
    resolve?.();
    resolve = undefined;
  }
  function wait(maxMs = 3000): Promise<void> {
    if (disposed) return Promise.resolve();
    // Preserve the legacy race, including creating a timer after ready and
    // swallowing timeout rejection. Do not cancel losing timers on ready().
    const timer = delay(maxMs);
    timers.add(timer);
    const timeout = timer.result.then(
      () => { timers.delete(timer); },
      () => { timers.delete(timer); },
    );
    return Promise.race([firstScreen, timeout]);
  }
  /** Explicit unmount cleanup: release waiters, suppress callbacks, cancel timers.
   * Ordinary ready()/timeout use does not cancel or dispose this gate. */
  function dispose(): void {
    if (disposed) return;
    disposed = true;
    resolve?.();
    resolve = undefined;
    for (const timer of timers) timer.cancel();
    timers.clear();
  }
  return { ready, wait, dispose };
}
