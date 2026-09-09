import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionState, type CompanionStatePort } from '../platforms/harmony/entry/src/main/ets/CompanionState';
import { SyncStateStore, type StateSnapshot } from '../packages/framework/src/sync-state';
class Port implements CompanionStatePort {
  values = new Map<string, string>();
  failure: 'before' | 'after' | null = null;
  conflict = false;
  async read(app: string) { return this.values.get(app) ?? null; }
  async compareExchange(app: string, old: string | null, next: string) {
    if (this.conflict) return false;
    if ((this.values.get(app) ?? null) !== old) return false;
    const failure = this.failure; this.failure = null;
    if (failure === 'before') throw Error('before commit');
    this.values.set(app, next);
    if (failure === 'after') throw Error('after commit');
    return true;
  }
}
test('state acknowledgement query is read-only, excludes unacknowledged changes and returns own peer cursor', async () => {
  const port = new Port(); let id = 0;
  const sdk = new CompanionState('app', 'phone', port, {
    async sha256(text) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return 'id-' + ++id; }
  });
  await sdk.set('key', 1); const before = port.values.get('app');
  expect(await sdk.acknowledgement('__proto__')).toEqual({ synchronized: false, appliedCursor: 0 });
  expect(port.values.get('app')).toBe(before);
  const batch = (await sdk.prepare('__proto__'))!;
  expect((await sdk.acknowledgement('__proto__')).synchronized).toBe(false);
  await sdk.acknowledgeAuthenticated('__proto__', batch.messageId, batch.to, batch.digest);
  expect((await sdk.acknowledgement('__proto__')).synchronized).toBe(true);
  await sdk.receiveAuthenticated('__proto__', 0, 1, []);
  expect(await sdk.acknowledgement('__proto__')).toEqual({ synchronized: true, appliedCursor: 1 });
  await sdk.delete('key'); expect((await sdk.acknowledgement('__proto__')).synchronized).toBe(false);
});
test('Harmony SDK merges the same entries and tombstones as the reference engine', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  let saved: StateSnapshot | undefined;
  const reference = new SyncStateStore('phone', { load: () => saved, commit: state => { saved = state; } });
  expect(await sdk.set('key', { a: [1, '中文'], b: true })).toEqual(reference.set('key', { a: [1, '中文'], b: true }));
  const entries = [{ key: 'key', value: null, deleted: true, counter: 1, deviceId: 'watch' }];
  expect(await sdk.receiveAuthenticated('watch', 0, 1, entries)).toBe(reference.receive('watch', 0, 1, entries));
  expect(await sdk.snapshot()).toEqual(reference.export()); expect(await sdk.get('key')).toBeUndefined();
  expect(await sdk.set('new', null)).toEqual(reference.set('new', null));
  expect(await sdk.get('new')).toBeNull();
  expect(await sdk.snapshot()).toEqual(reference.export());
});
test('failed or uncertain persistence never returns an ACK and reloads on retry', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  const entries = [{ key: 'remote', value: 1, deleted: false, counter: 9, deviceId: 'watch' }];
  let notifications = 0; sdk.subscribe(() => { notifications++; });
  port.failure = 'before'; await expect(sdk.receiveAuthenticated('watch', 0, 1, entries)).rejects.toThrow();
  expect((await sdk.snapshot()).cursors).toEqual({});
  port.failure = 'after'; await expect(sdk.receiveAuthenticated('watch', 0, 1, entries)).rejects.toThrow();
  expect(notifications).toBe(0);
  expect(await sdk.receiveAuthenticated('watch', 0, 1, entries)).toBe(1);
  expect(await sdk.get('remote')).toBe(1);
  await expect(sdk.receiveAuthenticated('watch', 2, 3, entries)).rejects.toThrow('gap');
});
test('storage binds app and device identities and refuses corrupt counters', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  await sdk.set('key', true);
  await expect(new CompanionState('app', 'different', port).get('key')).rejects.toThrow('identity');
  expect(await new CompanionState('other', 'phone', port).get('key')).toBeUndefined();
  const original = port.values.get('app')!; const stored = JSON.parse(original); stored.state.clock = -1;
  port.values.set('app', JSON.stringify(stored));
  await expect(sdk.set('key', false)).rejects.toThrow('counter');
  expect(port.values.get('app')).toBe(JSON.stringify(stored));
});
test('callbacks and callers cannot mutate persisted or queued values, CAS conflicts are explicit', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  const value = { a: [1] }; const pending = sdk.set('key', value); value.a[0] = 9;
  sdk.subscribe(state => { state.entries[0].value = 'changed'; throw Error('observer'); });
  await pending; expect(await sdk.get('key')).toEqual({ a: [1] });
  const returned = await sdk.snapshot(); returned.entries[0].value = 'changed';
  expect(await sdk.get('key')).toEqual({ a: [1] });
  port.conflict = true; await expect(sdk.delete('key')).rejects.toThrow('conflict');
  expect(await sdk.get('key')).toEqual({ a: [1] });
});
test('prototype-named peer identities are own cursors and same revision conflicts reject atomically', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  const entries = [{ key: 'key', value: { b: 2, a: 1 }, deleted: false, counter: 1, deviceId: 'watch' }];
  await sdk.receiveAuthenticated('__proto__', 0, 1, entries);
  const snapshot = await sdk.snapshot(); expect(Object.keys(snapshot.cursors)).toEqual(['__proto__']);
  expect(snapshot.cursors['__proto__']).toBe(1);
  entries[0].value = { a: 1, b: 2 }; expect(await sdk.receiveAuthenticated('__proto__', 1, 2, entries)).toBe(2);
  entries[0].value.b = 3;
  await expect(sdk.receiveAuthenticated('__proto__', 2, 3, entries)).rejects.toThrow('Conflicting');
  expect((await sdk.snapshot()).cursors['__proto__']).toBe(2);
});
test('invalid JSON, unsafe counters and oversized peer sets fail closed', async () => {
  const port = new Port(), sdk = new CompanionState('app', 'phone', port);
  for (const value of [NaN, Infinity, new Date(), [undefined], { x: undefined }]) {
    await expect(sdk.set('key', value)).rejects.toThrow();
  }
  expect(port.values.size).toBe(0);
  await sdk.set('key', 1);
  const raw = JSON.parse(port.values.get('app')!); raw.state.clock = Number.MAX_SAFE_INTEGER;
  port.values.set('app', JSON.stringify(raw)); await expect(sdk.set('key', 2)).rejects.toThrow('counter');
  raw.state.clock = 1; raw.state.cursors = Object.fromEntries(Array.from({ length: 128 }, (_, i) => ['p' + i, 1]));
  port.values.set('app', JSON.stringify(raw));
  await expect(sdk.receiveAuthenticated('extra', 0, 1, [])).rejects.toThrow('quota');
  expect(Object.keys((await sdk.snapshot()).cursors)).toHaveLength(128);
});
