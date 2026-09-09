import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionIncomingFiles, CompanionIncomingFilePort } from '../platforms/harmony/companion/src/main/ets/CompanionIncomingFiles';
import { CompanionFileRequest, CompanionFileManifest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { SyncIncomingFileServices } from '../platforms/harmony/entry/src/main/ets/SyncIncomingFileServices';
import { AuthorizedServices } from '../platforms/harmony/entry/src/main/ets/AuthorizedServices';
import { ServiceRequest, ServiceReply, UnsupportedServices, type ServiceHandler } from '../platforms/harmony/entry/src/main/ets/ServicePump';
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
class Port implements CompanionIncomingFilePort {
  raw: string | null = null; journalFail = false; reserveFail = false; removeFail = false;
  allocations = 0; removals = 0; chunks = new Map<number, Uint8Array>();
  saved: string[] = [];
  async saveComplete(_peer: string, _manifest: CompanionFileManifest, path: string) { this.saved.push(path); }
  async exclusive<T>(work: () => Promise<T>) { return work(); }
  async readJournal() { return this.raw; }
  async writeJournal(text: string) { if (this.journalFail) throw new Error('journal failed'); this.raw = text; }
  async reserve() { if (this.reserveFail) throw new Error('reserve failed'); this.allocations++; }
  async remove() { if (this.removeFail) throw new Error('remove failed'); this.removals++; this.chunks.clear(); }
  async writeChunk(_peer: string, _manifest: CompanionFileManifest, index: number, bytes: Uint8Array) { this.chunks.set(index, bytes.slice()); }
  async readCompleteChunk(_peer: string, _manifest: CompanionFileManifest, index: number) { return this.chunks.get(index)!.slice(); }
  async missing(_peer: string, manifest: CompanionFileManifest) { return manifest.chunk_hashes.map((_, i) => i).filter(i => !this.chunks.has(i) || hash(this.chunks.get(i)!) !== manifest.chunk_hashes[i]); }
  async finish(peer: string, manifest: CompanionFileManifest) {
    if ((await this.missing(peer, manifest)).length) throw new Error('missing');
    const bytes = Buffer.concat([...this.chunks.keys()].sort((a, b) => a - b).map(i => this.chunks.get(i)!));
    if (hash(bytes) !== manifest.sha256) throw new Error('whole file hash mismatch');
  }
}
const open = (port: Port) => new CompanionIncomingFiles('app', 'watch', port, { async sha256(bytes) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } });
function guestRequest(method: string) {
  const request = new ServiceRequest(); request.id = 1; request.method = method; request.args = { transferId: 'file' }; return request;
}
function guest(handler: ServiceHandler, method: string) {
  return new Promise<ServiceReply>(done => handler.handle(guestRequest(method), done));
}
test('incoming identity guard blocks events and mutations even after prior exposure', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  let conflict = false;
  const handler = new SyncIncomingFileServices(new UnsupportedServices(), async () => files, async (id, peer) => {
    expect(id).toBe('file'); expect(peer).toBe('phone'); if (conflict) throw Error('ambiguous');
  });
  expect(await guest(handler, 'sync.files.status')).toMatchObject({ ok: true });
  conflict = true; handler.setEventsActive(true);
  let posted = 0; await handler.pumpEvents(() => { posted++; return true; }); expect(posted).toBe(0);
  for (const method of ['sync.files.status', 'sync.files.accept', 'sync.files.cancel']) {
    expect(await guest(handler, method)).toMatchObject({ code: 'host_error' });
  }
  expect(port.allocations).toBe(0); expect(port.removals).toBe(0);
});
test('file events deduplicate but rejected events do not authorize acceptance', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  const handler = new SyncIncomingFileServices(new UnsupportedServices(), async () => files);
  handler.setEventsActive(true); await handler.pumpEvents(() => false);
  expect(await guest(handler, 'sync.files.accept')).toMatchObject({ code: 'host_error' });
  const events: any[] = []; const post = (json: string) => { events.push(JSON.parse(json)); return true; };
  await handler.pumpEvents(post); await handler.pumpEvents(post); expect(events).toHaveLength(1);
  expect(events[0]).toMatchObject({ t: 'sync.file.changed', value: { transferId: 'file', state: 'offered', totalBytes: 3 } });
  expect(await guest(handler, 'sync.files.accept')).toMatchObject({ ok: true });
  await handler.pumpEvents(post); expect(events[1].value.state).toBe('transferring');
  handler.setEventsActive(false); await handler.pumpEvents(post); expect(events).toHaveLength(2);
  handler.setEventsActive(true); await handler.pumpEvents(post); expect(events).toHaveLength(3);
});
test('file event paging progresses beyond 64 IDs and hides ambiguous or obsolete observations', async () => {
  const port = new Port(), files = open(port);
  for (let i = 0; i < 70; i++) { const request = offer(); request.manifest!.transfer_id = 'id' + i.toString().padStart(2, '0'); await files.executeAuthenticated('phone', request); }
  const ambiguous = offer(); ambiguous.manifest!.transfer_id = 'id00'; await files.executeAuthenticated('other', ambiguous);
  const handler = new SyncIncomingFileServices(new UnsupportedServices(), async () => files); handler.setEventsActive(true);
  const ids: string[] = []; const post = (json: string) => { ids.push(JSON.parse(json).value.transferId); return true; };
  await handler.pumpEvents(post); expect(ids).toHaveLength(63); await handler.pumpEvents(post); expect(ids).toHaveLength(69);
  expect(ids.includes('id00')).toBe(false); expect(new Set(ids).size).toBe(69);
  let resolve!: (value: CompanionIncomingFiles) => void;
  const pending = new Promise<CompanionIncomingFiles>(done => { resolve = done; });
  const late = new SyncIncomingFileServices(new UnsupportedServices(), () => pending); late.setEventsActive(true);
  const pumping = late.pumpEvents(post); late.setEventsActive(false); late.setEventsActive(true); resolve(files); await pumping;
  expect(ids).toHaveLength(69);
});
test('incoming guest service requires exposure and reports durable progress without deleting completion', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  const handler = new SyncIncomingFileServices(new UnsupportedServices(), async () => files);
  expect(await guest(handler, 'sync.files.accept')).toMatchObject({ code: 'host_error' }); expect(port.allocations).toBe(0);
  expect(await guest(handler, 'sync.files.status')).toMatchObject({ ok: true, value: { state: 'offered', receivedBytes: 0, totalBytes: 3 } });
  const saveRequest = guestRequest('sync.files.save'); saveRequest.args = { transferId: 'file', path: 'received.bin' };
  const save = () => new Promise<ServiceReply>(done => handler.handle(saveRequest, done));
  expect(await save()).toMatchObject({ code: 'host_error' }); expect(port.saved).toEqual([]);
  expect(await guest(handler, 'sync.files.accept')).toMatchObject({ ok: true, value: { state: 'transferring', receivedBytes: 0 } });
  const chunk = command('chunk'); chunk.data = new Uint8Array([1, 2, 3]); await files.executeAuthenticated('phone', chunk);
  await files.executeAuthenticated('phone', command('finish'));
  expect(await guest(handler, 'sync.files.cancel')).toMatchObject({ ok: true }); expect(port.removals).toBe(0);
  expect(await guest(handler, 'sync.files.status')).toMatchObject({ ok: true, value: { state: 'complete', receivedBytes: 3 } });
  expect(await save()).toMatchObject({ ok: true, value: { path: 'received.bin', size: 3 } }); expect(port.saved).toEqual(['received.bin']);
});
test('incoming guest service rejects ambiguity, unauthorized IO and deferred cancellation', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer()); let opens = 0;
  const adapter = new SyncIncomingFileServices(new UnsupportedServices(), async () => { opens++; return files; });
  const denied = new AuthorizedServices({ hasCapability: () => false }, adapter);
  expect(await guest(denied, 'sync.files.status')).toMatchObject({ code: 'unsupported' }); expect(opens).toBe(0);
  await files.executeAuthenticated('other', offer());
  expect(await guest(adapter, 'sync.files.status')).toMatchObject({ code: 'host_error' }); expect(port.allocations).toBe(0);
  let resolve!: (value: CompanionIncomingFiles) => void;
  const pending = new Promise<CompanionIncomingFiles>(done => { resolve = done; });
  const delayed = new SyncIncomingFileServices(new UnsupportedServices(), () => pending); const replies: ServiceReply[] = [];
  delayed.handle(guestRequest('sync.files.status'), reply => replies.push(reply)); await Promise.resolve(); delayed.cancel(1);
  resolve(files); await pending; await Promise.resolve(); expect(replies).toEqual([]);
});
test('guest progress is read-only and unfinished cancellation preserves completed bytes', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  const original = port.raw;
  expect(await files.statusLocal('phone', 'file')).toMatchObject({ phase: 'offered', receivedBytes: 0 });
  expect(port.raw).toBe(original); expect(port.allocations).toBe(0);
  await files.acceptLocal('phone', 'file');
  expect(await files.statusLocal('phone', 'file')).toMatchObject({ phase: 'accepted', receivedBytes: 0 });
  const chunk = command('chunk'); chunk.data = new Uint8Array([1, 2, 3]); await files.executeAuthenticated('phone', chunk);
  expect(await files.statusLocal('phone', 'file')).toMatchObject({ phase: 'accepted', receivedBytes: 3 });
  await files.executeAuthenticated('phone', command('finish'));
  await files.cancelUnfinishedLocal('phone', 'file');
  expect(port.removals).toBe(0); expect(await files.readCompleteChunk('phone', 'file', 0)).toEqual(chunk.data);
  expect(await files.statusLocal('phone', 'file')).toMatchObject({ phase: 'complete', receivedBytes: 3 });
  port.chunks.clear(); const completeJournal = port.raw;
  expect(await files.statusLocal('phone', 'file')).toMatchObject({ phase: 'accepted', receivedBytes: 0 });
  expect(port.raw).toBe(completeJournal);
});
test('guest unfinished cancellation persists cancellation and rejects invalid progress from backend', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  await files.acceptLocal('phone', 'file');
  port.missing = async () => [0, 0];
  await expect(files.statusLocal('phone', 'file')).rejects.toThrow('invalid backend');
  await files.cancelUnfinishedLocal('phone', 'file');
  expect((await files.listLocal())[0].phase).toBe('cancelled'); expect(port.removals).toBe(1);
});
test('local consent UI can list and reject without allocation or remote impersonation', async () => {
  const port = new Port(), files = open(port);
  await files.executeAuthenticated('phone', offer());
  const snapshot = await files.listLocal();
  expect(snapshot[0].manifest.size).toBe(3);
  snapshot[0].manifest.size = 999;
  expect((await files.listLocal())[0].manifest.size).toBe(3);
  await files.cancelLocal('phone', 'file');
  expect(port.allocations).toBe(0);
  expect(port.removals).toBe(0);
  expect((await open(port).listLocal())[0].phase).toBe('cancelled');
  await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('cancelled');
  await files.cancelLocal('phone', 'file');
});
function command(method: string) { const request = new CompanionFileRequest(); request.method = method; request.transfer_id = 'file'; return request; }
function offer() { const request = command('offer'), manifest = new CompanionFileManifest(); manifest.transfer_id = 'file'; manifest.size = 3; manifest.mime = 'application/octet-stream'; manifest.sha256 = hash(new Uint8Array([1, 2, 3])); manifest.chunk_hashes = [manifest.sha256]; request.manifest = manifest; return request; }
test('incoming offer does not allocate or grant consent; accepted chunks verify before publishing', async () => {
  const port = new Port(), files = open(port);
  expect((await files.executeAuthenticated('phone', offer())).phase).toBe('offered'); expect(port.allocations).toBe(0);
  await expect(files.executeAuthenticated('phone', command('missing'))).rejects.toThrow('not accepted');
  expect((await open(port).pendingConsent()).length).toBe(1); await files.acceptLocal('phone', 'file'); expect(port.allocations).toBe(1);
  const chunk = command('chunk'); chunk.data = new Uint8Array([4, 5, 6]);
  await expect(files.executeAuthenticated('phone', chunk)).rejects.toThrow('hash mismatch'); expect(port.chunks.size).toBe(0);
  chunk.data = new Uint8Array([1, 2, 3]); await files.executeAuthenticated('phone', chunk);
  expect((await open(port).executeAuthenticated('phone', command('missing'))).missing).toEqual([]);
  expect((await files.executeAuthenticated('phone', command('finish'))).phase).toBe('complete');
  await expect(files.acceptLocal('other', 'file')).rejects.toThrow('unknown');
});
test('accept and cancel intents persist before side effects and recover after interrupted backend work', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  port.journalFail = true; await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('journal failed'); expect(port.allocations).toBe(0);
  port.journalFail = false; port.reserveFail = true; await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('reserve failed');
  expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('accepting');
  port.reserveFail = false; await open(port).recover(); expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('accepted');
  port.removeFail = true; await expect(files.executeAuthenticated('phone', command('cancel'))).rejects.toThrow('remove failed');
  expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('cancelling');
  port.removeFail = false; await open(port).recover(); expect((await files.executeAuthenticated('phone', command('missing'))).missing).toEqual([]);
  await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('cancelled');
});
