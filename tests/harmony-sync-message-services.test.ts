import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionMessageOutbox } from '../platforms/harmony/entry/src/main/ets/CompanionMessages';
import { SyncMessageServices } from '../platforms/harmony/entry/src/main/ets/SyncMessageServices';
import { AuthorizedServices } from '../platforms/harmony/entry/src/main/ets/AuthorizedServices';
import { ServiceRequest, ServiceReply, UnsupportedServices, type ServiceHandler } from '../platforms/harmony/entry/src/main/ets/ServicePump';
function fixture() {
  let raw: string | null = null;
  const store = { async read() { return raw; }, async compareExchange(before: string | null, after: string) {
    if (raw !== before) return false; raw = after; return true;
  } };
  const outbox = new CompanionMessageOutbox('app', 'watch', store, {
    async sha256(bytes) { return new Uint8Array(createHash('sha256').update(bytes).digest()); }
  });
  return { outbox, store };
}
function request(id: number, args: Object, method = 'sync.messages.send') {
  const result = new ServiceRequest(); result.id = id; result.method = method; result.args = args; return result;
}
function run(handler: ServiceHandler, args: Object, method = 'sync.messages.send') {
  return new Promise<ServiceReply>(done => handler.handle(request(1, args, method), done));
}
function args(payload: Object | null = null) {
  return { peerId: 'phone', message: { messageId: 'id', ttlMs: 1000, priority: 'normal', payload } };
}
test('installed message service queues stable JSON and keeps TTL through ACK retries', async () => {
  const { outbox } = fixture(); let now = 10;
  const handler = new AuthorizedServices({ hasCapability: cap => cap === 'companion.sync.message' },
    new SyncMessageServices(new UnsupportedServices(), async () => outbox, () => now));
  expect(await run(handler, args({ z: 1, a: null }))).toMatchObject({ ok: true, value: { state: 'queued', messageId: 'id' } });
  const row = (await outbox.pending('phone', 20, 1))[0];
  expect(new TextDecoder().decode(row.envelope.payload)).toBe('{"a":null,"z":1}');
  expect(row.envelope.expiresAt).toBe(1010);
  await outbox.acknowledgeAuthenticated('phone', 'id', row.digest); now = 40;
  expect(await run(handler, args({ a: null, z: 1 }))).toMatchObject({ ok: true });
  expect(await outbox.pending('phone', 40, 1)).toEqual([]);
  expect(await run(handler, args({ a: null, z: 2 }))).toMatchObject({ code: 'host_error' });
});
test('unauthorized, invalid JSON and unseen ACK do not open or modify owner', async () => {
  const { outbox, store } = fixture(); let opens = 0;
  const adapter = new SyncMessageServices(new UnsupportedServices(), async () => { opens++; return outbox; }, () => 10);
  const denied = new AuthorizedServices({ hasCapability: () => false }, adapter);
  expect(await run(denied, args(null))).toMatchObject({ code: 'unsupported' });
  for (const payload of [NaN, Infinity, undefined, { x: undefined }, new Date(), 'x'.repeat(262145)]) {
    expect(await run(adapter, { ...args(), message: { ...args().message, payload } })).toMatchObject({ code: 'invalid_argument' });
  }
  expect(await run(adapter, { peerId: 'phone', messageId: 'id' }, 'sync.messages.ack')).toMatchObject({ code: 'unsupported' });
  expect(opens).toBe(0); expect(await store.read()).toBeNull();
});
test('queued send snapshots input and cancellation isolates reused request objects', async () => {
  const { outbox } = fixture(); const handler = new SyncMessageServices(new UnsupportedServices(), async () => outbox, () => 10);
  const input = args({ n: 1 }), shared = request(3, input); const old: ServiceReply[] = [];
  handler.handle(shared, reply => old.push(reply)); handler.cancel(3);
  const pending = new Promise<ServiceReply>(done => handler.handle(shared, done));
  input.peerId = 'redirected'; input.message.payload = { n: 2 }; input.message.ttlMs = 2;
  expect(await pending).toMatchObject({ ok: true }); expect(old).toEqual([]);
  const row = (await outbox.pending('phone', 10, 1))[0];
  expect(new TextDecoder().decode(row.envelope.payload)).toBe('{"n":1}');
  expect(row.envelope.expiresAt).toBe(1010); expect(await outbox.pending('redirected', 10, 1)).toEqual([]);
});
test('cancellation while owner opens suppresses deferred enqueue and failure permits retry', async () => {
  const { outbox, store } = fixture(); let resolve!: (value: CompanionMessageOutbox) => void;
  const owner = new Promise<CompanionMessageOutbox>(done => { resolve = done; }); let opens = 0;
  const handler = new SyncMessageServices(new UnsupportedServices(), () => { opens++; return owner; }, () => 10);
  const replies: ServiceReply[] = [];
  handler.handle(request(1, args()), reply => replies.push(reply)); await Promise.resolve();
  expect(opens).toBe(1); handler.cancel(1); resolve(outbox); await owner; await Promise.resolve();
  expect(await store.read()).toBeNull(); expect(replies).toEqual([]);
  let fail = true;
  const retry = new SyncMessageServices(new UnsupportedServices(), async () => { if (fail) throw Error('private failure'); return outbox; }, () => 10);
  expect(await run(retry, args())).toMatchObject({ code: 'host_error', message: 'Sync message operation failed' });
  fail = false; expect(await run(retry, args())).toMatchObject({ ok: true });
});
