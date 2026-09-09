import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionFileRequests, CompanionFileTerminalReceipt } from '../platforms/harmony/companion/src/main/ets/CompanionFileRequests';
import { CompanionFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
class Store { raw: string | null = null; fail = false; async read() { return this.raw; } async compareExchange(old: string | null, next: string) { if (this.fail || this.raw !== old) return false; this.raw = next; return true; } }
let sequence = 0;
const crypto = { async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); }, async messageId() { return 'request-' + ++sequence; } };
const open = (store: Store) => new CompanionFileRequests('app', 'phone', store, crypto);
function request() { const value = new CompanionFileRequest(); value.method = 'status'; value.transfer_id = 'file'; return value; }
function reply(digest: string, phase = 'offered') { return new TextEncoder().encode(JSON.stringify({ version: 1, type: 'reply', request_sha256: digest, value: { phase } })); }
test('transfer identity query spans peers and terminal receipts without exposing mutable storage', async () => {
  const store = new Store(), queue = open(store);
  const first = await queue.enqueue('watch', request());
  await queue.receiveAuthenticated('watch', first.messageId, reply(first.digest, 'complete'));
  const second = await queue.enqueue('other', request());
  const unrelated = request(); unrelated.transfer_id = 'unrelated';
  await queue.enqueue('third', unrelated);
  const before = store.raw, records = await open(store).recordsForTransfer('file');
  expect(records.map(record => record.peer)).toEqual(['watch', 'other']);
  expect(records[0].reply).toEqual(reply(first.digest, 'complete'));
  expect(records[1]).toEqual(second);
  records[0].payload.fill(0); records[0].reply!.fill(0); records[1].peer = 'changed';
  expect((await queue.recordsForTransfer('file'))[1]).toEqual(second);
  expect(await queue.recordsForTransfer('absent')).toEqual([]);
  expect(store.raw).toBe(before);
  expect(() => queue.recordsForTransfer('../invalid')).toThrow('identity');
  const corrupt = JSON.parse(before!); corrupt.records[2].digest = '0'.repeat(64);
  store.raw = JSON.stringify(corrupt);
  await expect(queue.recordsForTransfer('file')).rejects.toThrow('digest mismatch');
});
test('file request exact bytes and identity survive reopen; first reply remains stable until explicit consumption', async () => {
  const store = new Store(), queue = open(store), pending = await queue.enqueue('watch', request());
  expect(await open(store).next('watch')).toEqual(pending);
  await expect(queue.enqueue('watch', request())).rejects.toThrow('pending'); expect(await queue.forgetCompleted('watch', pending.messageId)).toBe(false);
  expect(await queue.receiveAuthenticated('other', pending.messageId, reply(pending.digest))).toBe('stale_reply');
  expect(await queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest))).toBe('reply');
  expect(await open(store).next('watch')).toBeNull();
  expect(await queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest, 'accepted'))).toBe('duplicate_reply');
  expect((await open(store).completed('watch'))[0].reply).toEqual(reply(pending.digest));
  expect(await queue.forgetCompleted('watch', pending.messageId)).toBe(true); expect(await open(store).completed('watch')).toEqual([]);
});
test('failed file reply transaction preserves pending and mismatched digest cannot overwrite progress', async () => {
  const store = new Store(), queue = open(store), pending = await queue.enqueue('watch', request()); const before = store.raw;
  await expect(queue.receiveAuthenticated('watch', pending.messageId, reply('b'.repeat(64)))).rejects.toThrow('mismatch');
  store.fail = true; await expect(queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest))).rejects.toThrow('conflict');
  expect(store.raw).toBe(before); expect(await open(store).next('watch')).toEqual(pending);
});
test('file request quota does not evict completed observations and persisted identity corruption rejects', async () => {
  const store = new Store(), queue = open(store), pending = await queue.enqueue('watch', request());
  await queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest));
  const snapshot = JSON.parse(store.raw!); snapshot.records = Array.from({ length: 128 }, (_, i) => ({ ...snapshot.records[0], id: 'id-' + i }));
  store.raw = JSON.stringify(snapshot); const before = store.raw;
  await expect(queue.enqueue('other', request())).rejects.toThrow('full'); expect(store.raw).toBe(before);
  snapshot.app = 'wrong'; store.raw = JSON.stringify(snapshot); await expect(queue.next('watch')).rejects.toThrow('snapshot');
});
test('terminal receipt survives reopen and only exact explicit consumption releases the peer queue', async () => {
  const store = new Store(), queue = open(store), pending = await queue.enqueue('watch', request());
  expect(await queue.terminal('watch')).toBeNull();
  await queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest, 'complete'));
  const receipt = (await open(store).terminal('watch'))!;
  expect(receipt.phase).toBe('complete'); expect(receipt.transferId).toBe('file');
  const wrong = new CompanionFileTerminalReceipt('watch', receipt.messageId, 'other', receipt.digest, 'complete');
  expect(await queue.consumeTerminal(wrong)).toBe(false);
  store.fail = true; await expect(queue.consumeTerminal(receipt)).rejects.toThrow('conflict');
  store.fail = false; expect(await queue.terminal('watch')).toEqual(receipt);
  expect(await queue.consumeTerminal(receipt)).toBe(true); expect(await queue.consumeTerminal(receipt)).toBe(false);
  const next = request(); next.transfer_id = 'next-file';
  const nextPending = await queue.enqueue('watch', next);
  expect(nextPending.messageId).not.toBe(receipt.messageId);
  expect(await queue.consumeTerminal(receipt)).toBe(false); expect(await queue.next('watch')).toEqual(nextPending);
});
test('nonterminal and mixed peer queues cannot be cleared by terminal receipt consumption', async () => {
  const store = new Store(), queue = open(store), pending = await queue.enqueue('watch', request());
  await queue.receiveAuthenticated('watch', pending.messageId, reply(pending.digest, 'accepted'));
  expect(await queue.terminal('watch')).toBeNull();
  expect(await queue.consumeTerminal(new CompanionFileTerminalReceipt('watch', pending.messageId, 'file', pending.digest, 'accepted'))).toBe(false);
  await queue.forgetCompleted('watch', pending.messageId);
  const terminal = await queue.enqueue('watch', request());
  await queue.receiveAuthenticated('watch', terminal.messageId, reply(terminal.digest, 'cancelled'));
  const receipt = (await queue.terminal('watch'))!; expect(receipt.phase).toBe('cancelled');
  await queue.enqueue('watch', request()); expect(await queue.terminal('watch')).toBeNull();
  expect(await queue.consumeTerminal(receipt)).toBe(false);
  expect((await queue.completed('watch')).length).toBe(1);
});
test('cancellation during request digest or queued observation read prevents a late CAS', async () => {
  const store = new Store(); let cancelled = false, release!: () => void, entered!: () => void;
  const ready = new Promise<void>(r => { entered = r; });
  const queue = new CompanionFileRequests('app', 'phone', store, { ...crypto, async sha256(bytes) {
    entered(); await new Promise<void>(r => { release = r; }); return crypto.sha256(bytes);
  } });
  const pending = queue.enqueue('watch', request(), () => cancelled); await ready;
  cancelled = true; release(); await expect(pending).rejects.toThrow('cancelled'); expect(store.raw).toBeNull();
  const active = open(store), record = await active.enqueue('watch', request());
  await active.receiveAuthenticated('watch', record.messageId, reply(record.digest));
  const before = store.raw, read = store.read.bind(store); let finish!: (raw: string | null) => void;
  store.read = () => new Promise(r => { finish = r; }); cancelled = false;
  const forgetting = active.forgetCompleted('watch', record.messageId, () => cancelled);
  await Promise.resolve(); cancelled = true; finish(before);
  await expect(forgetting).rejects.toThrow('cancelled'); store.read = read; expect(store.raw).toBe(before);
});
