import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionMessageOutbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageOutbox';
import { CompanionMessageInbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageInbox';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
import { CompanionMessageTransport } from '../platforms/harmony/companion/src/main/ets/CompanionMessageTransport';
import { CompanionSyncConnection } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
import { CompanionState } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { CompanionFileChannels } from '../platforms/harmony/companion/src/main/ets/CompanionFilePump';
import { CompanionFileRequests } from '../platforms/harmony/companion/src/main/ets/CompanionFileRequests';
import { CompanionFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { CompanionFileManifest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { CompanionFileSender } from '../platforms/harmony/companion/src/main/ets/CompanionFileSender';
import { CompanionFileValue } from '../platforms/harmony/companion/src/main/ets/CompanionFileReply';
import { CompanionIncomingFiles, CompanionIncomingFilePort } from '../platforms/harmony/companion/src/main/ets/CompanionIncomingFiles';
import { CompanionOutgoingTransfers } from '../platforms/harmony/companion/src/main/ets/CompanionOutgoingTransfers';
import { CompanionRegisteredFileSender } from '../platforms/harmony/companion/src/main/ets/CompanionRegisteredFileSender';
import { CompanionRegisteredFileDriver } from '../platforms/harmony/companion/src/main/ets/CompanionRegisteredFileDriver';
import { SyncMessageInbox } from '../platforms/harmony/entry/src/main/ets/SyncMessageInbox';
class Store {
  raw: string | null = null;
  async read(): Promise<string | null> { return this.raw; }
  async compareExchange(old: string | null, next: string) { if (old !== this.raw) return false; this.raw = next; return true; }
}
class Timer { expire = () => {}; schedule(_ms: number, expire: () => void) { this.expire = expire; return () => {}; } }
class Link {
  peer!: Link; closed = false; failAck = false; sent: number[] = [];
  queue: Uint8Array[] = []; waiter: ((bytes: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> { if (this.closed) return null; if (this.queue.length) return this.queue.shift()!; return new Promise(resolve => { this.waiter = resolve; }); }
  async write(bytes: Uint8Array) {
    if (this.closed) throw new Error('closed');
    const frame = JSON.parse(new TextDecoder().decode(bytes));
    if (this.failAck && frame.channel === 'ack') throw new Error('ACK write failed');
    this.sent.push(frame.sequence);
    if (this.peer.waiter) { const resolve = this.peer.waiter; this.peer.waiter = null; resolve(bytes.slice()); } else this.peer.queue.push(bytes.slice());
  }
  close() { this.closed = true; this.waiter?.(null); this.waiter = null; }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
async function peers(mixed = false, receive?: (request: CompanionFileRequest) => Promise<CompanionFileValue>, approved?: string[]) {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(2));
  const grants = approved ?? (mixed ? ['state', 'message', 'ack', 'file'] : ['message', 'ack']);
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, grants, crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, grants, crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  const left = new Link(), right = new Link(); left.peer = right; right.peer = left;
  const aStore = new Store(), bStore = new Store(), outA = new CompanionMessageOutbox('app', 'phone', aStore, crypto);
  const inA = new CompanionMessageInbox('app', 'phone', new Store(), crypto), outB = new CompanionMessageOutbox('app', 'watch', new Store(), crypto);
  const inB = new CompanionMessageInbox('app', 'watch', bStore, crypto), timer = new Timer();
  function state(local: string) {
    const store = new Store(); let id = 0;
    return new CompanionState('app', local, { read: () => store.read(), compareExchange: (_app, old, next) => store.compareExchange(old, next) },
      { async sha256(text) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return 'state-' + ++id; } });
  }
  const stateA = state('phone'), stateB = state('watch');
  let requestId = 0;
  const requests = new CompanionFileRequests('app', 'phone', new Store(), { ...crypto, async messageId() { return 'file-request-' + ++requestId; } });
  const fileA = new CompanionFileChannels(requests, null, crypto);
  const fileB = new CompanionFileChannels(null, { matchesIdentity: (app, local) => app === 'app' && local === 'watch', async executeAuthenticated(_peer, request) { return receive ? receive(request) : { phase: 'offered' }; } }, crypto);
  const x = new CompanionMessageTransport(new CompanionSyncConnection(a, left), outA, inA, { now: () => 2 }, timer, 1000, mixed ? stateA : null, mixed ? fileA : null);
  const y = new CompanionMessageTransport(new CompanionSyncConnection(b, right), outB, inB, { now: () => 2 }, new Timer(), 1000, mixed ? stateB : null, mixed ? fileB : null);
  return { x, y, outA, inB, aStore, bStore, timer, left, right, stateA, stateB, requests };
}
async function until(check: () => Promise<boolean>) { for (let i = 0; i < 100; i++) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 0)); } throw new Error('test progress timeout'); }
test('guest durable business ACK immediately drains sender over the existing authenticated session', async () => {
  const p = await peers(true);
  const delivery = new SyncMessageInbox(async () => p.inB, () => 2, () => true, applied => p.y.acknowledge(applied));
  try {
    await p.outA.enqueue('watch', 'guest-ack', new CompanionMessageEnvelope(1000, false, new TextEncoder().encode('{"ok":true}')), 2);
    await p.x.sendNext(); await until(async () => (await p.inB.pending(2, 100)).length === 1);
    expect(await p.outA.pending('watch', 2, 100)).toHaveLength(1); expect(p.right.sent).toHaveLength(0);
    delivery.setActive(true); await delivery.pump(); await delivery.acknowledge('phone', 'guest-ack', () => true);
    await until(async () => (await p.outA.pending('watch', 2, 100)).length === 0);
    expect(await p.inB.pending(2, 100)).toHaveLength(0);
    expect(p.left.sent).toEqual([1]); expect(p.right.sent).toEqual([1]);
  } finally { delivery.setActive(false); p.x.close(); p.y.close(); }
});
test('shared state barrier waits for durable ACKs across batches and cancellation leaves other waiters live', async () => {
  const p = await peers(true, undefined, ['state', 'ack']); let release!: () => void;
  const gate = new Promise<void>(done => { release = done; }), write = p.right.write.bind(p.right);
  p.right.write = async bytes => { await gate; await write(bytes); };
  try {
    for (let i = 0; i < 513; i++) await p.stateA.set('key-' + i.toString().padStart(3, '0'), i);
    const first = p.x.synchronizeState(), second = p.x.synchronizeState();
    let completed = false; const done = second.done.then(value => { completed = true; return value; });
    await until(async () => await p.stateB.get('key-000') === 0);
    expect(completed).toBe(false); expect((await p.stateA.acknowledgement('watch')).synchronized).toBe(false);
    first.cancel(); await expect(first.done).rejects.toThrow('cancelled'); expect(p.left.closed).toBe(false);
    release(); expect(await done).toBe(0);
    expect(await p.stateB.get('key-512')).toBe(512);
    expect((await p.stateA.acknowledgement('watch')).synchronized).toBe(true);
    await p.stateA.set('later', true); expect(await p.x.synchronizeState().done).toBe(0);
    expect(await p.stateB.get('later')).toBe(true);
  } finally { release(); p.x.close(); p.y.close(); }
});
test('state wait timeout removes only its waiter and later ACK can complete a new barrier', async () => {
  const p = await peers(true, undefined, ['state', 'ack']); let release!: () => void;
  const gate = new Promise<void>(done => { release = done; }), write = p.right.write.bind(p.right);
  p.right.write = async bytes => { await gate; await write(bytes); };
  try {
    await p.stateA.set('key', 1); const wait = p.x.synchronizeState(100);
    await until(async () => await p.stateB.get('key') === 1);
    p.timer.expire(); await expect(wait.done).rejects.toThrow('deadline'); expect(p.left.closed).toBe(false);
    release(); expect(await p.x.synchronizeState().done).toBe(0);
    const last = p.x.synchronizeState(); p.x.close(); await expect(last.done).rejects.toThrow('closed');
  } finally { release(); p.x.close(); p.y.close(); }
});
test('state wait capacity is bounded and cancelling waiters releases their slots', async () => {
  const p = await peers(true, undefined, ['state', 'ack']);
  try {
    const waits = Array.from({ length: 8 }, () => p.x.synchronizeState());
    expect(() => p.x.synchronizeState()).toThrow('capacity');
    for (const wait of waits) wait.cancel();
    expect((await Promise.allSettled(waits.map(wait => wait.done))).every(result => result.status === 'rejected')).toBe(true);
    expect(await p.x.synchronizeState().done).toBe(0);
  } finally { p.x.close(); p.y.close(); }
});
test('state-only authenticated transport skips other channels without reading their queues', async () => {
  const p = await peers(true, undefined, ['state', 'ack']);
  try {
    let messageReads = 0; p.aStore.read = async () => { messageReads++; throw Error('unauthorized message read'); };
    await p.stateA.set('allowed', 1); p.x.driveOutgoing();
    await until(async () => await p.stateB.get('allowed') === 1);
    expect(messageReads).toBe(0);
    await expect(p.x.sendNext()).rejects.toThrow('not authorized');
    await expect(p.x.sendFile()).rejects.toThrow('not authorized');
    expect(messageReads).toBe(0);
    await p.stateA.set('still-connected', 2);
    await until(async () => await p.stateB.get('still-connected') === 2);
    expect(p.left.closed).toBe(false);
    p.x.close(); await expect(p.x.sendState()).rejects.toThrow('stopped');
  } finally { p.x.close(); p.y.close(); }
});
test('registered driver sends full file after consent then cancels next task over authenticated transport', async () => {
  let journal: string | null = null, tail = Promise.resolve();
  const chunks = new Map<string, Uint8Array>(), allocations: string[] = [];
  const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
  const port: CompanionIncomingFilePort = {
    exclusive<T>(work: () => Promise<T>): Promise<T> { const result = tail.then(work); tail = result.then(() => {}, () => {}); return result; },
    async readJournal() { return journal; }, async writeJournal(value) { journal = value; },
    async reserve(_peer, manifest) { allocations.push(manifest.transfer_id); },
    async remove(_peer, manifest) { for (let i = 0; i < manifest.chunk_hashes.length; i++) chunks.delete(manifest.transfer_id + ':' + i); },
    async writeChunk(_peer, manifest, index, bytes) { chunks.set(manifest.transfer_id + ':' + index, bytes.slice()); },
    async missing(_peer, manifest) { return manifest.chunk_hashes.map((_, i) => i).filter(i => !chunks.has(manifest.transfer_id + ':' + i)); },
    async finish(_peer, manifest) {
      const bytes = Buffer.concat(manifest.chunk_hashes.map((_, i) => chunks.get(manifest.transfer_id + ':' + i)!));
      if (hash(bytes) !== manifest.sha256) throw Error('file bytes differ');
    },
    async readCompleteChunk(_peer, manifest, index) { return chunks.get(manifest.transfer_id + ':' + index)!.slice(); }
  };
  const incoming = new CompanionIncomingFiles('app', 'watch', port, crypto);
  const p = await peers(true, request => incoming.executeAuthenticated('phone', request));
  const registry = new CompanionOutgoingTransfers('app', 'phone', new Store());
  const bytes = Uint8Array.from({ length: 65539 }, (_, i) => i % 251);
  const first = new CompanionFileManifest(); first.transfer_id = 'first'; first.size = bytes.length;
  first.sha256 = hash(bytes); first.chunk_hashes = [hash(bytes.slice(0, 65536)), hash(bytes.slice(65536))];
  const second = new CompanionFileManifest(); second.transfer_id = 'second'; second.sha256 = hash(new Uint8Array());
  await registry.register('watch', first); await registry.register('watch', second);
  await registry.transition('watch', 'second', 'cancel_requested');
  const driver = new CompanionRegisteredFileDriver({
    fileRequests: p.requests, outgoingTransfers: registry,
    async createRegisteredFileSender(peer, id) {
      const manifest = (await registry.list()).find(record => record.manifest.transfer_id === id)!.manifest;
      return new CompanionRegisteredFileSender(p.requests, peer, manifest, {
        async readChunk(index) { expect(id).toBe('first'); return bytes.slice(index * 65536, (index + 1) * 65536); }
      }, crypto, registry);
    }
  }, p.x, 'watch');
  try {
    await driver.step(); await until(async () => p.x.fileStatus() === 'waiting_consent');
    expect(allocations).toEqual([]); expect(chunks.size).toBe(0);
    await incoming.acceptLocal('phone', 'first'); await driver.step();
    await until(async () => p.x.fileStatus() === 'complete');
    expect((await registry.list())[0].phase).toBe('complete');
    expect((await incoming.statusLocal('phone', 'first')).phase).toBe('complete');
    expect(Buffer.concat([chunks.get('first:0')!, chunks.get('first:1')!])).toEqual(Buffer.from(bytes));
    expect(await p.requests.terminal('watch')).toBeNull();
    await driver.step(); await until(async () => p.x.fileStatus() === 'cancelled');
    expect((await registry.list())[1].phase).toBe('cancelled');
    expect((await incoming.statusLocal('phone', 'second')).phase).toBe('cancelled');
    expect(allocations.includes('second')).toBe(false);
    expect(await driver.step()).toBe('idle'); expect(await p.requests.terminal('watch')).toBeNull();
  } finally { driver.close(); p.x.close(); p.y.close(); }
});
test('opt-in outgoing driver advances state batches and queued messages without polling or implicit business ACK', async () => {
  const p = await peers(true);
  try {
    for (let i = 0; i < 300; i++) await p.stateA.set('key-' + i, i);
    await p.outA.enqueue('watch', 'first', new CompanionMessageEnvelope(1000, false, new Uint8Array([1])), 2);
    await p.outA.enqueue('watch', 'second', new CompanionMessageEnvelope(1000, false, new Uint8Array([2])), 2);
    p.x.driveOutgoing(); p.y.driveOutgoing();
    await until(async () => await p.stateB.get('key-299') === 299);
    await until(async () => (await p.inB.pending(2, 10)).length === 1);
    expect((await p.outA.pending('watch', 2, 10)).length).toBe(2);
    await p.stateA.set('after-start', 'new');
    await until(async () => await p.stateB.get('after-start') === 'new');
    await p.y.acknowledge((await p.inB.pending(2, 10))[0]);
    await until(async () => (await p.inB.pending(2, 10)).some(message => message.messageId === 'second'));
    await p.y.acknowledge((await p.inB.pending(2, 10))[0]);
    await until(async () => (await p.outA.pending('watch', 2, 10)).length === 0);
    await p.outA.enqueue('watch', 'later', new CompanionMessageEnvelope(1000, false, new Uint8Array([3])), 2);
    p.x.driveOutgoing(); p.x.driveOutgoing();
    await until(async () => (await p.inB.pending(2, 10)).some(message => message.messageId === 'later'));
    p.x.close(); const sent = p.left.sent.length;
    await p.stateA.set('closed', true); await new Promise(resolve => setTimeout(resolve, 0));
    expect(p.left.sent.length).toBe(sent); expect(() => p.x.driveOutgoing()).toThrow('stopped');
  } finally { p.x.close(); p.y.close(); }
});
test('attached file sender pauses for consent then advances over authenticated shared transport', async () => {
  let accepted = false, received = false;
  const p = await peers(true, async request => {
    if (request.method === 'offer' || request.method === 'status') return { phase: accepted ? 'accepted' : 'offered' };
    if (request.method === 'missing') return { phase: 'accepted', missing: received ? [] : [0] };
    if (request.method === 'chunk') { expect(request.data).toEqual(new Uint8Array([7])); received = true; return { phase: 'accepted' }; }
    return { phase: 'complete' };
  });
  try {
    const manifest = new CompanionFileManifest(); manifest.transfer_id = 'automatic'; manifest.size = 1;
    manifest.sha256 = createHash('sha256').update(new Uint8Array([7])).digest('hex'); manifest.chunk_hashes = [manifest.sha256];
    const sender = new CompanionFileSender(p.requests, 'watch', manifest, { async readChunk() { return new Uint8Array([7]); } }, crypto);
    await p.x.driveFile(sender);
    await until(async () => p.x.fileStatus() === 'waiting_consent');
    expect(p.left.sent.length).toBe(1);
    expect((await p.requests.next('watch'))).not.toBeNull();
    accepted = true; await p.x.driveFile(sender);
    await until(async () => p.x.fileStatus() === 'complete');
    expect(received).toBe(true); expect(await p.requests.next('watch')).toBeNull();
    expect(p.left.sent).toEqual([1, 2, 3, 4, 5, 6]);
    const receipt = (await p.requests.terminal('watch'))!;
    expect(receipt.transferId).toBe('automatic');
    expect(await p.x.consumeFileTerminal(receipt)).toBe(true);
    expect(p.x.fileStatus()).toBe('idle');
    expect(await p.x.consumeFileTerminal(receipt)).toBe(false);
    received = false; manifest.transfer_id = 'second';
    const second = new CompanionFileSender(p.requests, 'watch', manifest, { async readChunk() { return new Uint8Array([7]); } }, crypto);
    await p.x.driveFile(second); await until(async () => p.x.fileStatus() === 'complete');
    expect(received).toBe(true); expect((await p.requests.terminal('watch'))!.transferId).toBe('second');
    expect(await p.x.consumeFileTerminal(receipt)).toBe(false);
    expect((await p.requests.terminal('watch'))!.transferId).toBe('second');
  } finally { p.x.close(); p.y.close(); }
});
test('message transport delivers into inbox, holds one message until explicit business ACK, and orders writes', async () => {
  const p = await peers();
  try {
    await p.outA.enqueue('watch', 'id', new CompanionMessageEnvelope(100, true, new Uint8Array([1, 2])), 1);
    expect(await p.x.sendNext()).toBe(true); expect(await p.x.sendNext()).toBe(false);
    await until(async () => (await p.inB.pending(2, 1)).length === 1);
    const delivery = (await p.inB.pending(2, 1))[0]; expect(p.right.sent.length).toBe(0);
    await p.y.acknowledge(delivery);
    await until(async () => (await p.outA.pending('watch', 2, 1)).length === 0);
    expect(await p.x.sendNext()).toBe(false); expect(p.left.sent).toEqual([1]); expect(p.right.sent).toEqual([1]);
  } finally { p.x.close(); p.y.close(); }
});
test('closing during source IO leaves durable observation intact and does not enqueue a late chunk', async () => {
  const p = await peers(true, async request => request.method === 'missing' ? { phase: 'accepted', missing: [0] } : { phase: 'accepted' });
  let release!: (bytes: Uint8Array) => void, entered!: () => void;
  const ready = new Promise<void>(r => { entered = r; });
  try {
    const manifest = new CompanionFileManifest(); manifest.transfer_id = 'cancelled-source'; manifest.size = 1;
    manifest.sha256 = createHash('sha256').update(new Uint8Array([7])).digest('hex'); manifest.chunk_hashes = [manifest.sha256];
    const sender = new CompanionFileSender(p.requests, 'watch', manifest, { readChunk() {
      entered(); return new Promise(r => { release = r; });
    } }, crypto);
    await p.x.driveFile(sender); await ready;
    const observation = await p.requests.completed('watch'); expect(observation.length).toBe(1);
    p.x.close(); const sent = p.left.sent.length; release(new Uint8Array([7]));
    await new Promise(r => setTimeout(r, 0));
    expect(await p.requests.completed('watch')).toEqual(observation);
    expect(await p.requests.next('watch')).toBeNull(); expect(p.left.sent.length).toBe(sent);
  } finally { p.x.close(); p.y.close(); }
});
test('message transport ACK write failure retains applied inbox and unacknowledged sender', async () => {
  const p = await peers();
  try {
    await p.outA.enqueue('watch', 'id', new CompanionMessageEnvelope(100, false, new Uint8Array()), 1);
    await p.x.sendNext(); await until(async () => (await p.inB.pending(2, 1)).length === 1);
    p.right.failAck = true; await expect(p.y.acknowledge((await p.inB.pending(2, 1))[0])).rejects.toThrow('ACK write failed');
    expect((await p.y.stopped).reason).toBe('failed'); expect((await p.outA.pending('watch', 2, 1)).length).toBe(1);
    expect(await p.inB.pending(2, 1)).toEqual([]);
  } finally { p.x.close(); p.y.close(); }
});
test('deadline rejects stalled storage operation and late return cannot send', async () => {
  const p = await peers(); let enter!: () => void, finish!: (raw: string | null) => void;
  const entered = new Promise<void>(resolve => { enter = resolve; });
  p.aStore.read = () => { enter(); return new Promise(resolve => { finish = resolve; }); };
  const result = p.x.sendNext().catch(error => error.message); await entered; p.timer.expire();
  expect(await result).toContain('deadline'); expect((await p.x.stopped).reason).toBe('deadline');
  finish(null); await new Promise(resolve => setTimeout(resolve, 0)); expect(p.left.sent).toEqual([]); p.y.close();
});
test('shared transport sends state while message awaits application and separates same-ID ACKs', async () => {
  const p = await peers(true);
  try {
    await p.outA.enqueue('watch', 'state-1', new CompanionMessageEnvelope(100, false, new Uint8Array([8])), 1);
    await p.stateA.set('key', 'value');
    expect(await p.x.sendNext()).toBe(true); expect(await p.x.sendState()).toBe(true);
    await until(async () => await p.stateB.get('key') === 'value');
    await until(async () => await p.stateA.prepare('watch') === null);
    expect((await p.outA.pending('watch', 2, 1)).length).toBe(1); expect(await p.x.sendNext()).toBe(false);
    await p.stateA.set('later', true); expect(await p.x.sendState()).toBe(true);
    await until(async () => await p.stateB.get('later') === true);
    const delivery = (await p.inB.pending(2, 1))[0]; await p.y.acknowledge(delivery);
    await until(async () => (await p.outA.pending('watch', 2, 1)).length === 0);
    for (const link of [p.left, p.right]) expect(link.sent).toEqual(link.sent.map((_, i) => i + 1));
  } finally { p.x.close(); p.y.close(); }
});
test('three-channel transport completes file observations while message business ACK is deferred', async () => {
  const p = await peers(true);
  try {
    await p.outA.enqueue('watch', 'file-request-1', new CompanionMessageEnvelope(100, false, new Uint8Array([9])), 1);
    const request = new CompanionFileRequest(); request.method = 'status'; request.transfer_id = 'file';
    await p.requests.enqueue('watch', request); await p.stateA.set('state', 7);
    expect(await p.x.sendNext()).toBe(true); expect(await p.x.sendFile()).toBe(true); expect(await p.x.sendState()).toBe(true);
    await until(async () => (await p.requests.completed('watch')).length === 1);
    await until(async () => await p.stateB.get('state') === 7);
    expect((await p.outA.pending('watch', 2, 1)).length).toBe(1); expect(await p.x.sendNext()).toBe(false);
    await p.requests.enqueue('watch', request); expect(await p.x.sendFile()).toBe(true);
    await until(async () => (await p.requests.completed('watch')).length === 2);
    await p.y.acknowledge((await p.inB.pending(2, 1))[0]);
    await until(async () => (await p.outA.pending('watch', 2, 1)).length === 0);
    for (const link of [p.left, p.right]) expect(link.sent).toEqual(link.sent.map((_, i) => i + 1));
  } finally { p.x.close(); p.y.close(); }
});
