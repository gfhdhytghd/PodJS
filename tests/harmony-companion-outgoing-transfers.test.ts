import { expect, test } from 'bun:test';
import { CompanionOutgoingTransfers } from '../platforms/harmony/companion/src/main/ets/CompanionOutgoingTransfers';
import { CompanionFileManifest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
class Store {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(expected: string | null, desired: string) {
    if (this.fail || expected !== this.raw) return false;
    this.raw = desired; return true;
  }
}
function manifest(id = 'file') {
  const result = new CompanionFileManifest(); result.transfer_id = id;
  result.size = 1; result.sha256 = 'a'.repeat(64); result.chunk_hashes = ['b'.repeat(64)]; result.mime = 'text/plain'; return result;
}
const open = (store: Store) => new CompanionOutgoingTransfers('app', 'watch', store);
test('durable missing snapshots and chunk ACKs retain exact indexes without implying completion', async () => {
  const store = new Store(), registry = open(store), file = manifest();
  file.size = 65539; file.chunk_hashes.push('c'.repeat(64)); await registry.register('phone', file);
  await registry.observeMissing('phone', 'file', [0]);
  expect((await open(store).list())[0]).toMatchObject({ phase: 'queued', progressKnown: true, acknowledgedChunks: [1] });
  await registry.observeChunk('phone', 'file', 0); await registry.observeChunk('phone', 'file', 0);
  expect((await open(store).list())[0]).toMatchObject({ phase: 'queued', acknowledgedChunks: [0, 1] });
  const before = store.raw; store.fail = true;
  await expect(registry.observeMissing('phone', 'file', [0, 1])).rejects.toThrow('conflict'); expect(store.raw).toBe(before);
  store.fail = false;
  await expect(registry.observeMissing('phone', 'file', [1, 1])).rejects.toThrow('invalid');
  await expect(registry.observeChunk('phone', 'file', 2)).rejects.toThrow('invalid');
  await registry.transition('phone', 'file', 'cancel_requested');
  expect((await registry.list())[0].acknowledgedChunks).toEqual([0, 1]);
  await registry.transition('phone', 'file', 'complete');
  expect((await registry.list())[0]).toMatchObject({ phase: 'complete', progressKnown: true, acknowledgedChunks: [0, 1] });
});
test('schema one progress starts unknown and schema two rejects malformed acknowledgement state', async () => {
  const store = new Store(), registry = open(store); await registry.register('phone', manifest());
  const old = JSON.parse(store.raw!); old.schema = 1; delete old.records[0].progressKnown; delete old.records[0].acknowledgedChunks;
  store.raw = JSON.stringify(old);
  expect((await registry.list())[0].progressKnown).toBe(false);
  await registry.observeChunk('phone', 'file', 0); expect((await registry.list())[0].progressKnown).toBe(false);
  await registry.observeMissing('phone', 'file', []); expect(JSON.parse(store.raw!).schema).toBe(2);
  const corrupt = JSON.parse(store.raw!); corrupt.records[0].acknowledgedChunks = [0, 0]; store.raw = JSON.stringify(corrupt);
  await expect(registry.list()).rejects.toThrow('progress');
});
test('outgoing registration survives reopen and terminal retry without identity reuse', async () => {
  const store = new Store(), registry = open(store), source = manifest();
  const registering = registry.register('phone', source); source.mime = 'changed';
  const record = await registering; expect(record.manifest.mime).toBe('text/plain');
  record.manifest.chunk_hashes[0] = 'changed';
  expect((await open(store).list())[0].manifest).toEqual(manifest());
  await expect(registry.register('other', manifest())).rejects.toThrow('identity conflict');
  const changed = manifest(); changed.mime = 'changed';
  await expect(registry.register('phone', changed)).rejects.toThrow('identity conflict');
  expect((await registry.transition('phone', 'file', 'cancel_requested')).phase).toBe('cancel_requested');
  expect((await open(store).transition('phone', 'file', 'complete')).phase).toBe('complete');
  expect((await open(store).register('phone', manifest())).phase).toBe('complete');
  await expect(registry.transition('phone', 'file', 'cancelled')).rejects.toThrow('terminal');
  expect((await registry.list())).toHaveLength(1);
});
test('failed CAS and cancelled calls preserve durable intent', async () => {
  const store = new Store(), registry = open(store);
  store.fail = true; await expect(registry.register('phone', manifest())).rejects.toThrow('conflict');
  expect(store.raw).toBeNull(); store.fail = false;
  await registry.register('phone', manifest()); const before = store.raw;
  await expect(registry.transition('phone', 'file', 'cancel_requested', () => true)).rejects.toThrow('cancelled');
  store.fail = true; await expect(registry.transition('phone', 'file', 'complete')).rejects.toThrow('conflict');
  expect(store.raw).toBe(before);
  await expect(registry.transition('other', 'file', 'complete')).rejects.toThrow('unknown');
  expect(() => registry.register('watch', manifest())).toThrow('peer');
});
test('registry capacity retains terminal identities and corruption fails closed', async () => {
  const store = new Store(), registry = open(store);
  await registry.register('phone', manifest());
  const snapshot = JSON.parse(store.raw!);
  snapshot.schema = 1;
  snapshot.records = Array.from({ length: 128 }, (_, i) => ({ peer: 'phone', manifest: manifest('file-' + i), phase: 'complete' }));
  store.raw = JSON.stringify(snapshot); const before = store.raw;
  await expect(registry.register('phone', manifest('extra'))).rejects.toThrow('full');
  expect(store.raw).toBe(before); expect((await registry.register('phone', manifest('file-0'))).phase).toBe('complete');
  snapshot.records[1].manifest.transfer_id = 'file-0'; store.raw = JSON.stringify(snapshot);
  await expect(registry.list()).rejects.toThrow('record');
  snapshot.records = []; snapshot.local = 'other'; store.raw = JSON.stringify(snapshot);
  await expect(registry.list()).rejects.toThrow('snapshot');
});
test('two registry instances cannot silently overwrite one another', async () => {
  const store = new Store(); let reads = 0, release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  store.read = async () => { const raw = store.raw; if (++reads === 2) release(); await gate; return raw; };
  const results = await Promise.allSettled([open(store).register('phone', manifest('a')), open(store).register('phone', manifest('b'))]);
  expect(results.filter(result => result.status === 'fulfilled')).toHaveLength(1);
  expect(results.filter(result => result.status === 'rejected')).toHaveLength(1);
  expect(await open(store).list()).toHaveLength(1);
});
