/** Serializes bridge writes and exposes the exact tail that exit paths must await. */
export class SerialWriteQueue {
  private tail: Promise<void> = Promise.resolve();

  enqueue(write: () => Promise<void>): Promise<void> {
    const task = this.tail.catch(() => {}).then(write);
    this.tail = task;
    return task;
  }

  flush(): Promise<void> {
    return this.tail;
  }
}

export type FlushOutcome =
  | { status: "saved" }
  | { status: "failed"; error: unknown }
  | { status: "timed-out"; timeoutMs: number };

export interface FlushTimer {
  result: Promise<unknown>;
  cancel(): void;
}

export type FlushTimerFactory = (milliseconds: number) => FlushTimer;

/** Races a final write against a cancellable caller-supplied deadline.
 * Timeout stops waiting; it does not cancel the write. */
export async function waitForFlush(
  flush: () => Promise<void>,
  timerFactory: FlushTimerFactory,
  timeoutMs: number,
): Promise<FlushOutcome> {
  let timer: FlushTimer;
  try {
    timer = timerFactory(timeoutMs);
  } catch (error) {
    return { status: "failed", error };
  }
  const timedOut = timer.result.then<FlushOutcome, FlushOutcome>(
    () => ({ status: "timed-out", timeoutMs }),
    error => ({ status: "failed", error }),
  );
  let writing: Promise<void>;
  try {
    writing = flush();
  } catch (error) {
    timer.cancel();
    return { status: "failed", error };
  }
  try {
    return await Promise.race<FlushOutcome>([
      writing.then<FlushOutcome, FlushOutcome>(() => ({ status: "saved" }), error => ({ status: "failed", error })),
      timedOut,
    ]);
  } finally {
    timer.cancel();
  }
}

/** Deduplicates a Back attempt while the bounded final-write wait is active. */
export class ExitFlushGuard<T extends object> {
  private pending: T | undefined;

  constructor(
    private readonly timerFactory: FlushTimerFactory,
    private readonly timeoutMs: number,
  ) {}

  begin(target: T, flush: () => Promise<void>, complete: (outcome: FlushOutcome) => void): void {
    if (this.pending === target) return;
    this.pending = target;
    void waitForFlush(flush, this.timerFactory, this.timeoutMs).then(complete).finally(() => {
      if (this.pending === target) this.pending = undefined;
    });
  }
}
