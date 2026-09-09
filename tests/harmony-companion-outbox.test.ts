import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionMessageOutbox, CompanionMessageStore } from '../platforms/harmony/companion/src/main/ets/CompanionMessageOutbox';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
class Store implements CompanionMessageStore {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(expected: string | null, desired: string) {
    if (this.fail || this.raw !== expected) return false; this.raw = desired; return true;
  }
}
const crypto = { async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } };
const envelope = (expiry = 100, high = false, payload = new Uint8Array([1])) => new CompanionMessageEnvelope(expiry, high, payload);
const open = (store: Store) => new CompanionMessageOutbox('app', 'phone', store, crypto);
test('TTL retries retain first expiry across restart and do not recreate ACKed messages', async () => {
  const store = new Store(), queue = open(store), bytes = new Uint8Array([1, 2]);
  expect(await queue.enqueueWithTtl('watch', 'ttl', bytes, 100, false, 10)).toBe(110);
  const before = store.raw;
  expect(await open(store).enqueueWithTtl('watch', 'ttl', bytes, 100, false, 20)).toBe(110);
  expect(store.raw).toBe(before);
  const row = (await queue.pending('watch', 20, 1))[0];
  await queue.acknowledgeAuthenticated('watch', 'ttl', row.digest);
  expect(await open(store).enqueueWithTtl('watch', 'ttl', bytes, 100, false, 30)).toBe(110);
  await queue.enqueue('watch', 'ttl', new CompanionMessageEnvelope(110, false, bytes), 30);
  expect(await queue.pending('watch', 30, 10)).toEqual([]);
  await expect(queue.enqueueWithTtl('watch', 'ttl', bytes, 100, false, 110)).rejects.toThrow('expired');
});
test('TTL identity rejects content, priority, duration changes and absolute-expiry collisions', async () => {
  const store = new Store(), queue = open(store), bytes = new Uint8Array([1]);
  await queue.enqueueWithTtl('watch', 'ttl', bytes, 100, false, 10);
  const before = store.raw;
  await expect(queue.enqueueWithTtl('watch', 'ttl', bytes, 101, false, 20)).rejects.toThrow('identity changed');
  await expect(queue.enqueueWithTtl('watch', 'ttl', bytes, 100, true, 20)).rejects.toThrow('identity changed');
  await expect(queue.enqueueWithTtl('watch', 'ttl', new Uint8Array([2]), 100, false, 20)).rejects.toThrow('identity changed');
  await expect(queue.enqueue('watch', 'ttl', envelope(120), 20)).rejects.toThrow('conflicts');
  expect(store.raw).toBe(before);
  await queue.enqueue('watch', 'absolute', envelope(), 10);
  await expect(queue.enqueueWithTtl('watch', 'absolute', bytes, 90, false, 10)).rejects.toThrow('absolute-expiry');
});
test('TTL queue and retry intent share one CAS and recover uncertain durable completion', async () => {
  const store = new Store(), queue = open(store), bytes = new Uint8Array([1]);
  store.fail = true;
  await expect(queue.enqueueWithTtl('watch', 'ttl', bytes, 100, false, 10)).rejects.toThrow('conflict');
  expect(store.raw).toBeNull(); store.fail = false;
  const original = store.compareExchange.bind(store);
  store.compareExchange = async (before, after) => { await original(before, after); throw Error('lost result'); };
  await expect(queue.enqueueWithTtl('watch', 'ttl', bytes, 100, false, 20)).rejects.toThrow('lost result');
  store.compareExchange = original;
  expect(await open(store).enqueueWithTtl('watch', 'ttl', bytes, 100, false, 30)).toBe(120);
  expect((await queue.pending('watch', 30, 10))).toHaveLength(1);
});
test('legacy snapshot migrates on write and malformed retry ledger fails closed', async () => {
  const store = new Store();
  store.raw = JSON.stringify({ schema: 1, app: 'app', local: 'phone', messages: [] });
  await open(store).enqueueWithTtl('watch', 'ttl', new Uint8Array([1]), 100, false, 1);
  const valid = JSON.parse(store.raw); expect(valid.schema).toBe(2);
  for (const intents of [undefined, null, [...valid.intents, ...valid.intents], [{ ...valid.intents[0], ttl: 0 }]]) {
    store.raw = JSON.stringify({ ...valid, intents });
    const before = store.raw;
    await expect(open(store).pending('watch', 2, 1)).rejects.toThrow(); expect(store.raw).toBe(before);
  }
});
test('TTL retry ledger has a separate quota and never evicts an unexpired identity', async () => {
  const store = new Store(), digest = Buffer.from(await crypto.sha256(new Uint8Array([1]))).toString('hex');
  store.raw = JSON.stringify({ schema: 2, app: 'app', local: 'phone', messages: [],
    intents: Array.from({ length: 10000 }, (_, i) => ({ peer: 'watch', id: 'id' + i, ttl: 100, expires: 110, high: false, digest })) });
  const before = store.raw;
  await expect(open(store).enqueueWithTtl('watch', 'extra', new Uint8Array([1]), 100, false, 20)).rejects.toThrow('ledger full');
  expect(store.raw).toBe(before);
  expect(await open(store).enqueueWithTtl('watch', 'extra', new Uint8Array([1]), 100, false, 110)).toBe(210);
  expect(JSON.parse(store.raw).intents).toHaveLength(1);
});
test('outbox reopens unchanged, preserves priority FIFO and removes only peer/digest-bound ACK', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'normal', envelope(), 1);
  await queue.enqueue('watch', 'first-high', envelope(100, true), 1);
  await queue.enqueue('watch', 'next-high', envelope(100, true), 1);
  await queue.enqueue('other', 'normal', envelope(), 1);
  const before = store.raw, reopened = open(store), messages = await reopened.pending('watch', 2, 10);
  expect(messages.map(m => m.messageId)).toEqual(['first-high', 'next-high', 'normal']);
  expect(store.raw).toBe(before);
  await expect(reopened.acknowledgeAuthenticated('watch', 'normal', new Uint8Array(32))).rejects.toThrow('content mismatch');
  expect(store.raw).toBe(before);
  expect(await reopened.acknowledgeAuthenticated('unknown', 'normal', messages[2].digest)).toBe(false);
  expect(await reopened.acknowledgeAuthenticated('watch', 'normal', messages[2].digest)).toBe(true);
  expect((await open(store).pending('other', 2, 10)).length).toBe(1);
});
test('outbox rejects changed ID and failed durable CAS without losing stored work', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'id', envelope(), 1); const before = store.raw;
  await queue.enqueue('watch', 'id', envelope(), 1); expect(store.raw).toBe(before);
  await expect(queue.enqueue('watch', 'id', envelope(101), 1)).rejects.toThrow('content mismatch');
  store.fail = true;
  await expect(queue.enqueue('watch', 'new', envelope(), 1)).rejects.toThrow('conflict');
  const message = (await queue.pending('watch', 1, 1))[0];
  await expect(queue.acknowledgeAuthenticated('watch', 'id', message.digest)).rejects.toThrow('conflict');
  expect(store.raw).toBe(before); store.fail = false;
  expect(await queue.expire(100)).toBe(1); expect(await open(store).pending('watch', 100, 1)).toEqual([]);
});
test('outbox copies queued input and returned bytes and detects tampered persistent content', async () => {
  const store = new Store(), queue = open(store), item = envelope(100, false, new Uint8Array(262144).fill(255));
  const saving = queue.enqueue('watch', 'large', item, 1); item.payload.fill(0); await saving;
  const row = (await queue.pending('watch', 2, 1))[0]; expect(row.envelope.payload[0]).toBe(255);
  row.envelope.payload.fill(1); row.digest.fill(0);
  expect((await open(store).pending('watch', 2, 1))[0].envelope.payload[0]).toBe(255);
  const snapshot = JSON.parse(store.raw!); snapshot.messages[0].payload = '00'; store.raw = JSON.stringify(snapshot);
  await expect(open(store).pending('watch', 2, 1)).rejects.toThrow('digest mismatch');
});
test('outbox enforces byte capacity without evicting live messages and binds snapshot identity', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'large', envelope(100, false, new Uint8Array(262144)), 1);
  const snapshot = JSON.parse(store.raw!);
  snapshot.messages = Array.from({ length: 31 }, (_, i) => ({ ...snapshot.messages[0], id: 'id' + i }));
  store.raw = JSON.stringify(snapshot); const before = store.raw;
  await expect(queue.enqueue('watch', 'overflow', envelope(100, true, new Uint8Array(262144)), 1)).rejects.toThrow('queue full');
  expect(store.raw).toBe(before);
  await expect(new CompanionMessageOutbox('different', 'phone', store, crypto).pending('watch', 1, 1)).rejects.toThrow('snapshot');
});
