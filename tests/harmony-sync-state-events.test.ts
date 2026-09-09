import { expect, test } from 'bun:test';
import { CompanionState, type CompanionStatePort } from '../platforms/harmony/entry/src/main/ets/CompanionState';
import { SyncStateEvents } from '../platforms/harmony/entry/src/main/ets/SyncStateEvents';

class Port implements CompanionStatePort {
  raw: string | null = null;
  async read() { return this.raw; }
  async compareExchange(_app: string, before: string | null, after: string) {
    if (this.raw !== before) return false;
    this.raw = after; return true;
  }
}
function owner() { return new CompanionState('app', 'watch', new Port()); }
test('state events retry rejected frames, deduplicate and preserve tombstones', async () => {
  const state = owner(); await state.set('k', null);
  let accepts = false; const frames: any[] = [];
  const events = new SyncStateEvents(async () => state, () => true, json => {
    if (!accepts) return false; frames.push(JSON.parse(json)); return true;
  });
  await events.pump(); expect(frames).toHaveLength(0);
  accepts = true; await events.pump(); await events.pump();
  expect(frames).toHaveLength(1);
  expect(frames[0]).toMatchObject({ t: 'sync.state.changed', value: { key: 'k', value: null, deleted: false } });
  await state.delete('k'); await events.pump();
  expect(frames[1].value.deleted).toBe(true);
  events.close(); await state.set('k', 3); await events.pump(); expect(frames).toHaveLength(2);
});
test('state event pages are bounded and next pump reaches remaining keys', async () => {
  const state = owner(); for (let i = 0; i < 70; i++) await state.set('k' + i, i);
  const keys: string[] = [];
  const events = new SyncStateEvents(async () => state, () => true, json => { keys.push(JSON.parse(json).value.key); return true; });
  await events.pump(); expect(keys).toHaveLength(64);
  await events.pump(); expect(new Set(keys).size).toBe(70);
  await events.pump(); expect(keys).toHaveLength(70);
});
test('close and revoked capability suppress late owner initialization', async () => {
  const state = owner(); await state.set('k', 1);
  let resolve!: (state: CompanionState) => void;
  const pending = new Promise<CompanionState>(done => { resolve = done; });
  let calls = 0; const events = new SyncStateEvents(() => pending, () => true, () => { calls++; return true; });
  const pumping = events.pump(); await events.pump(); events.close(); resolve(state); await pumping;
  expect(calls).toBe(0);
  let allowed = true;
  const revoked = new SyncStateEvents(async () => { allowed = false; return state; }, () => allowed, () => { calls++; return true; });
  await revoked.pump(); expect(calls).toBe(0);
});
test('failed snapshot can retry without marking entries delivered', async () => {
  const state = owner(); await state.set('k', 2); let attempts = 0, posted = 0;
  const events = new SyncStateEvents(async () => { if (++attempts === 1) throw Error('unavailable'); return state; }, () => true, () => { posted++; return true; });
  await expect(events.pump()).rejects.toThrow('unavailable');
  await events.pump(); expect(posted).toBe(1);
});
