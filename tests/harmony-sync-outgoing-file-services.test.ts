import { test, expect } from 'bun:test';
import { SyncOutgoingFileServices, SyncOutgoingClient } from '../platforms/harmony/entry/src/main/ets/SyncOutgoingFileServices';
import { CompanionOutgoingTransfers } from '../platforms/harmony/companion/src/main/ets/CompanionOutgoingTransfers';
import { CompanionFileManifest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { ServiceReply, ServiceRequest, UnsupportedServices } from '../platforms/harmony/entry/src/main/ets/ServicePump';
function fixture() {
  let raw: string | null = null, queued = 0;
  const outgoingTransfers = new CompanionOutgoingTransfers('app', 'watch', {
    read: async () => raw, compareExchange: async (expected, desired) => { if (raw !== expected) return false; raw = desired; return true; }
  });
  const client: SyncOutgoingClient = {
    outgoingTransfers,
    async resolveFileIdentity(id) { const record = (await outgoingTransfers.list()).find(item => item.manifest.transfer_id === id); return record ? { peer: record.peer, direction: 'outgoing' } : null; },
    async queueGuestFile(peer, id, _path, mime, cancelled) {
      queued++; const manifest = new CompanionFileManifest(); manifest.transfer_id = id; manifest.mime = mime; manifest.sha256 = 'a'.repeat(64);
      return outgoingTransfers.register(peer, manifest, cancelled);
    }
  };
  const handler = new SyncOutgoingFileServices(new UnsupportedServices(), async () => client, async () => 'fresh');
  return { handler, client, queued: () => queued };
}
function request(method: string, args: object) { const value = new ServiceRequest(); value.id = 1; value.method = method; value.args = args; return value; }
function invoke(handler: SyncOutgoingFileServices, method: string, args: object) {
  return new Promise<ServiceReply>(done => handler.handle(request(method, args), done));
}
test('outgoing guest progress counts a short tail exactly and waits for finish receipt', async () => {
  const { handler, client } = fixture(), manifest = new CompanionFileManifest();
  manifest.transfer_id = 'file'; manifest.size = 65539; manifest.sha256 = 'a'.repeat(64); manifest.chunk_hashes = ['b'.repeat(64), 'c'.repeat(64)];
  await client.outgoingTransfers.register('phone', manifest);
  await client.outgoingTransfers.observeMissing('phone', 'file', [0]);
  expect(await invoke(handler, 'sync.files.status', { transferId: 'file' })).toMatchObject({ value: { state: 'transferring', receivedBytes: 3, totalBytes: 65539, progressKnown: true } });
  await client.outgoingTransfers.observeChunk('phone', 'file', 0);
  expect(await invoke(handler, 'sync.files.status', { transferId: 'file' })).toMatchObject({ value: { state: 'transferring', receivedBytes: 65539 } });
  await client.outgoingTransfers.transition('phone', 'file', 'complete');
  expect(await invoke(handler, 'sync.files.status', { transferId: 'file' })).toMatchObject({ value: { state: 'complete', receivedBytes: 65539 } });
});
test('outgoing events deduplicate, retry rejection and expose only accepted observations', async () => {
  const { handler, client } = fixture(); await client.queueGuestFile('phone', 'fresh', '', '', () => false);
  handler.setEventsActive(true); await handler.pumpEvents(() => false);
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ code: 'host_error' });
  const events: any[] = []; const post = (json: string) => { events.push(JSON.parse(json)); return true; };
  await handler.pumpEvents(post); await handler.pumpEvents(post); expect(events).toHaveLength(1);
  expect(events[0]).toMatchObject({ t: 'sync.file.changed', value: { transferId: 'fresh', state: 'offered', progressKnown: false } });
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ ok: true });
  await handler.pumpEvents(post); expect(events[1].value.cancelRequested).toBe(true);
  handler.setEventsActive(false); await handler.pumpEvents(post); expect(events).toHaveLength(2);
  handler.setEventsActive(true); await handler.pumpEvents(post); expect(events).toHaveLength(3);
});
test('outgoing event cursor reaches later records and rejects ambiguous or late observations', async () => {
  const { handler, client } = fixture();
  for (let i = 0; i < 65; i++) await client.queueGuestFile('phone', 'id' + i.toString().padStart(2, '0'), '', '', () => false);
  const resolve = client.resolveFileIdentity.bind(client);
  client.resolveFileIdentity = async id => { if (id === 'id00') throw Error('ambiguous'); return resolve(id); };
  handler.setEventsActive(true); const ids: string[] = [];
  const post = (json: string) => { ids.push(JSON.parse(json).value.transferId); return true; };
  await handler.pumpEvents(post); expect(ids).toHaveLength(63);
  await handler.pumpEvents(post); expect(ids).toHaveLength(64); expect(ids.includes('id64')).toBe(true);
  let release!: (client: SyncOutgoingClient) => void;
  const owner = new Promise<SyncOutgoingClient>(done => { release = done; });
  const late = new SyncOutgoingFileServices(new UnsupportedServices(), () => owner, async () => 'unused');
  late.setEventsActive(true); const pending = late.pumpEvents(post);
  late.setEventsActive(false); late.setEventsActive(true); release(client); await pending;
  expect(ids).toHaveLength(64);
});
test('guest file offer exposes durable outgoing status and cancellation intent without claiming peer completion', async () => {
  const { handler, client } = fixture();
  expect(await invoke(handler, 'sync.files.offer', { peerId: 'phone', path: 'file.bin', mime: '' })).toMatchObject({ ok: true, value: { transferId: 'fresh', state: 'offered', progressKnown: false } });
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ ok: true });
  expect(await invoke(handler, 'sync.files.status', { transferId: 'fresh' })).toMatchObject({ ok: true, value: { state: 'offered', cancelRequested: true } });
  await client.outgoingTransfers.transition('phone', 'fresh', 'complete');
  expect(await invoke(handler, 'sync.files.status', { transferId: 'fresh' })).toMatchObject({ ok: true, value: { state: 'complete', progressKnown: true } });
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ code: 'host_error' });
});
test('outgoing cancellation requires exposure and malformed metadata never queues', async () => {
  const { handler, client, queued } = fixture();
  await client.queueGuestFile('phone', 'fresh', '', '', () => false);
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ code: 'host_error' });
  expect(await invoke(handler, 'sync.files.status', { transferId: 'fresh' })).toMatchObject({ ok: true });
  expect(await invoke(handler, 'sync.files.cancel', { transferId: 'fresh' })).toMatchObject({ ok: true });
  expect(await invoke(handler, 'sync.files.offer', { peerId: 'phone', path: 'file', mime: '\u0000' })).toMatchObject({ code: 'invalid_argument' });
  expect(queued()).toBe(1);
});
test('offer snapshots arguments and cancellation during owner initialization prevents late enqueue', async () => {
  const { client, queued } = fixture(); let release!: (client: SyncOutgoingClient) => void;
  const owner = new Promise<SyncOutgoingClient>(done => { release = done; });
  const handler = new SyncOutgoingFileServices(new UnsupportedServices(), () => owner, async () => 'fresh');
  let replies = 0;
  handler.handle(request('sync.files.offer', { peerId: 'phone', path: 'file.bin', mime: '' }), () => replies++);
  await Promise.resolve(); handler.cancel(1); release(client);
  await new Promise(done => setTimeout(done, 0)); expect(queued()).toBe(0); expect(replies).toBe(0);
  const next = request('sync.files.offer', { peerId: 'phone', path: 'file.bin', mime: 'text/plain' });
  const result = new Promise<ServiceReply>(done => handler.handle(next, done));
  next.args = { peerId: 'other', path: 'other', mime: 'changed' }; next.id = 2;
  expect(await result).toMatchObject({ ok: true });
  expect((await client.outgoingTransfers.list())[0]).toMatchObject({ peer: 'phone', manifest: { mime: 'text/plain' } });
});
