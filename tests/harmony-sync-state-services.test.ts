import { expect, test } from 'bun:test';
import { CompanionState, type CompanionStatePort } from '../platforms/harmony/entry/src/main/ets/CompanionState';
import { SyncStateServices } from '../platforms/harmony/entry/src/main/ets/SyncStateServices';
import { CompanionStateWait } from '../platforms/harmony/companion/src/main/ets/CompanionMessageTransport';
import { AuthorizedServices } from '../platforms/harmony/entry/src/main/ets/AuthorizedServices';
import { ServiceRequest, ServiceReply, UnsupportedServices, type ServiceHandler } from '../platforms/harmony/entry/src/main/ets/ServicePump';

class Port implements CompanionStatePort {
  stored: string | null = null;
  async read() { return this.stored; }
  async compareExchange(_app: string, old: string | null, value: string) {
    if (old !== this.stored) return false; this.stored = value; return true;
  }
}
test('guest synchronize awaits cursor barrier, snapshots peer and cancels only its wait handle', async () => {
  let finish!: (cursor: number) => void, reject!: (error: Error) => void, cancelled = 0;
  const peers: string[] = [];
  const handler = new SyncStateServices(new UnsupportedServices(), async () => { throw Error('unexpected state opening'); }, peer => {
    peers.push(peer); return new CompanionStateWait(new Promise((done, fail) => { finish = done; reject = fail; }), () => { cancelled++; reject(Error('cancelled')); });
  });
  const value = request(1, 'sync.state.synchronize', { peerId: 'phone' });
  const reply = new Promise<ServiceReply>(done => handler.handle(value, done));
  value.args = { peerId: 'other' }; await Promise.resolve(); expect(peers).toEqual(['phone']);
  finish(7); expect(await reply).toMatchObject({ ok: true, value: { appliedCursor: 7 } });
  let replies = 0; handler.handle(request(2, 'sync.state.synchronize', { peerId: 'phone' }), () => replies++);
  await Promise.resolve(); handler.cancel(2); await new Promise(done => setTimeout(done, 0));
  expect(cancelled).toBe(1); expect(replies).toBe(0);
  expect(await run(handler, 3, 'sync.state.synchronize', { peerId: '../bad' })).toMatchObject({ code: 'invalid_argument' });
});
test('Harmony save uses the file capability gate without granting it from args', async () => {
  let calls = 0;
  const delegate: ServiceHandler = { handle(_request, complete) { calls++; const reply = new ServiceReply();reply.ok = true;complete(reply); }, cancel() {} };
  const allowed = new AuthorizedServices({ hasCapability: cap => cap === 'companion.sync.file' }, delegate);
  expect(await run(allowed, 1, 'sync.files.save', { transferId: 'file', path: 'received.bin' })).toMatchObject({ ok: true });
  const denied = new AuthorizedServices({ hasCapability: () => false }, delegate);
  expect(await run(denied, 2, 'sync.files.save', { capabilities: ['companion.sync.file'] })).toMatchObject({ code: 'unsupported' });
  expect(calls).toBe(1);
});
function request(id: number, method: string, args: Object) {
  const value = new ServiceRequest(); value.id = id; value.method = method; value.args = args; return value;
}
function run(handler: ServiceHandler, id: number, method: string, args: Object) {
  return new Promise<ServiceReply>(resolve => handler.handle(request(id, method, args), resolve));
}
test('Harmony watch state adapter preserves null, tombstones and installed identity', async () => {
  const port = new Port(), state = new CompanionState('installed-app', 'installed-watch', port);
  const handler = new AuthorizedServices({ hasCapability: cap => cap === 'companion.sync.state' }, new SyncStateServices(new UnsupportedServices(), async () => state));
  expect(await run(handler, 1, 'sync.state.set', { key: 'k', value: null, appId: 'forged', deviceId: 'forged' })).toMatchObject({ ok: true, value: { key: 'k', value: null, deleted: false, deviceId: 'installed-watch' } });
  expect(await run(handler, 2, 'sync.state.get', { key: 'k' })).toMatchObject({ ok: true, value: { exists: true, entry: { value: null } } });
  expect(await run(handler, 3, 'sync.state.delete', { key: 'k' })).toMatchObject({ ok: true, value: { deleted: true } });
  expect(await run(handler, 4, 'sync.state.get', { key: 'k' })).toMatchObject({ ok: true, value: { exists: false, entry: { deleted: true } } });
  expect(await new CompanionState('installed-app', 'installed-watch', port).get('k')).toBeUndefined();
});
test('unauthorized, invalid and unsupported network requests do not mutate storage', async () => {
  const port = new Port(), state = new CompanionState('app', 'watch', port);
  const adapter = new SyncStateServices(new UnsupportedServices(), async () => state);
  const denied = new AuthorizedServices({ hasCapability: () => false }, adapter);
  expect(await run(denied, 1, 'sync.state.set', { key: 'k', value: 1, capabilities: ['companion.sync.state'] })).toMatchObject({ ok: false, code: 'unsupported' });
  expect(await run(adapter, 2, 'sync.state.set', { key: 'k' })).toMatchObject({ code: 'invalid_argument' });
  expect(await run(adapter, 3, 'sync.state.get', { key: '../escape' })).toMatchObject({ code: 'invalid_argument' });
  expect(await run(adapter, 4, 'sync.state.synchronize', { peerId: 'phone' })).toMatchObject({ code: 'unsupported' });
  expect(port.stored).toBeNull();
});
test('queued cancellation and request snapshot isolate reused IDs and mutation', async () => {
  const port = new Port(), state = new CompanionState('app', 'watch', port);
  const adapter = new SyncStateServices(new UnsupportedServices(), async () => state); const replies: ServiceReply[] = [];
  adapter.handle(request(1, 'sync.state.set', { key: 'cancelled', value: 1 }), reply => replies.push(reply));adapter.cancel(1);
  const args = { key: 'kept', value: { count: 2 } };
  const pending = run(adapter, 1, 'sync.state.set', args);args.key = 'redirected';args.value.count = 9;
  expect(await pending).toMatchObject({ ok: true, value: { key: 'kept', value: { count: 2 } } });
  expect(replies).toHaveLength(0);expect(await state.get('cancelled')).toBeUndefined();expect(await state.get('redirected')).toBeUndefined();
});
test('reusing the same request object after cancel cannot revive its old operation', async () => {
  const port = new Port(), state = new CompanionState('app', 'watch', port);
  const adapter = new SyncStateServices(new UnsupportedServices(), async () => state);
  const shared = request(7, 'sync.state.set', { key: 'k', value: 1 });
  const obsolete: ServiceReply[] = [];
  adapter.handle(shared, reply => obsolete.push(reply));adapter.cancel(7);
  const result = new Promise<ServiceReply>(resolve => adapter.handle(shared, resolve));
  expect(await result).toMatchObject({ ok: true, value: { counter: 1 } });
  expect(obsolete).toHaveLength(0);
  expect((await state.snapshot()).clock).toBe(1);
});
test('cancellation while installed owner opens prevents deferred state mutation', async () => {
  const port = new Port(), state = new CompanionState('app', 'watch', port);
  let release!: (state: CompanionState) => void;
  let entered!: () => void;
  const opening = new Promise<CompanionState>(resolve => { release = resolve; });
  const started = new Promise<void>(resolve => { entered = resolve; });
  const adapter = new SyncStateServices(new UnsupportedServices(), () => { entered();return opening; });
  const replies: ServiceReply[] = [];
  adapter.handle(request(11, 'sync.state.set', { key: 'cancelled', value: 1 }), reply => replies.push(reply));
  await started;adapter.cancel(11);release(state);
  await Promise.resolve();await Promise.resolve();
  expect(port.stored).toBeNull();expect(replies).toHaveLength(0);
  expect(await run(adapter, 11, 'sync.state.set', { key: 'fresh', value: 2 })).toMatchObject({ ok: true, value: { key: 'fresh', counter: 1 } });
  expect(await state.get('cancelled')).toBeUndefined();
});
test('failed installed owner opening emits a bounded error and permits a later retry', async () => {
  const state = new CompanionState('app', 'watch', new Port());let attempts = 0;
  const adapter = new SyncStateServices(new UnsupportedServices(), async () => {
    if (++attempts === 1) throw Error('private storage failure detail');return state;
  });
  expect(await run(adapter, 1, 'sync.state.get', { key: 'k' })).toMatchObject({ ok: false, code: 'host_error', message: 'Sync state operation failed' });
  expect(await run(adapter, 1, 'sync.state.get', { key: 'k' })).toMatchObject({ ok: true, value: { exists: false } });
});
