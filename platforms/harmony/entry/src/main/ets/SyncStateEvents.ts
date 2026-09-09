import { CompanionState, CompanionStateEntry } from './CompanionState';

class StateEvent {
  t: string = 'sync.state.changed';
  value: CompanionStateEntry;
  constructor(entry: CompanionStateEntry) { this.value = entry; }
}

/** Foreground snapshot delivery. Coalesces changes, including tombstones; this
 * is not a history stream or proof that guest business processing completed.
 * The caller owns the timer and must close this instance when its page closes.
 */
export class SyncStateEvents {
  private closed: boolean = false;
  private pumping: boolean = false;
  private emitted: Map<string, string> = new Map();
  constructor(private state: () => Promise<CompanionState>,
    private allowed: () => boolean, private post: (json: string) => boolean) {}
  close(): void { this.closed = true; this.emitted.clear(); }
  async pump(): Promise<void> {
    if (this.closed || this.pumping || !this.allowed()) return;
    this.pumping = true;
    try {
      const state = await this.state();
      if (this.closed || !this.allowed()) return;
      const snapshot = await state.snapshot();
      if (this.closed || !this.allowed()) return;
      let count = 0;
      for (const entry of snapshot.entries) {
        if (this.closed || !this.allowed()) return;
        const json = JSON.stringify(new StateEvent(entry));
        if (this.emitted.get(entry.key) === json) continue;
        if (!this.post(json)) return;
        if (this.closed) return;
        this.emitted.set(entry.key, json);
        if (++count === 64) return;
      }
    } finally { this.pumping = false; }
  }
}
