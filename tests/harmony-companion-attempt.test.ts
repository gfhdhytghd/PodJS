import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionSyncAttempt } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
import { CompanionSyncExchange, type CompanionSyncChallengeCrypto, type CompanionSyncPacketStream } from '../platforms/harmony/companion/src/main/ets/CompanionSyncExchange';
class Timer {
  expire: () => void = () => {}; cancelled = false;
  schedule(_ms: number, expire: () => void) { this.expire = expire; return () => { this.cancelled = true; }; }
}
class Link implements CompanionSyncPacketStream {
  closed = false; sent: Uint8Array[] = []; peer: Link | null = null;
  queue: Uint8Array[] = []; waiting: ((packet: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> {
    if (this.closed) return null;
    if (this.queue.length) return this.queue.shift()!;
    return new Promise(resolve => { this.waiting = resolve; });
  }
  async write(packet: Uint8Array) {
    if (this.closed) throw Error('closed'); this.sent.push(packet.slice());
    if (this.peer !== null) {
      if (this.peer.waiting) { const resolve = this.peer.waiting; this.peer.waiting = null; resolve(packet.slice()); }
      else this.peer.queue.push(packet.slice());
    }
  }
  close() { this.closed = true; this.waiting?.(null); this.waiting = null; }
}
const crypto = (nonce: number): CompanionSyncChallengeCrypto => ({
  async challenge() { return new Uint8Array(32).fill(nonce); },
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
});
const key = () => new Uint8Array(32).fill(7);
test('deadline settles even when connect never settles; a late connection is closed', async () => {
  let finish!: (link: Link) => void; let cancelled = false;
  const timer = new Timer();
  const attempt = new CompanionSyncAttempt({ connect: () => new Promise(resolve => { finish = resolve; }), cancel() { cancelled = true; } }, crypto(1), timer, 1000);
  const pending = attempt.start('app', 'phone', 'watch', key(), true, ['state']);
  const result = Promise.allSettled([pending]); timer.expire();
  expect((await result)[0].status).toBe('rejected'); expect(cancelled).toBe(true);
  const late = new Link(); finish(late); await Promise.resolve(); await Promise.resolve();
  expect(late.closed).toBe(true); expect(late.sent.length).toBe(0);
});
test('deadline interrupts stalled random operation and suppresses late crypto IO', async () => {
  let finish!: (bytes: Uint8Array) => void;
  const port = crypto(1); port.challenge = () => new Promise(resolve => { finish = resolve; });
  const timer = new Timer(), link = new Link();
  const attempt = new CompanionSyncAttempt({ async connect() { return link; }, cancel() {} }, port, timer, 1000);
  const original = key(), pending = attempt.start('app', 'phone', 'watch', original, true, ['state']);
  await Promise.resolve();
  const result = Promise.allSettled([pending]); timer.expire();
  expect((await result)[0].status).toBe('rejected'); expect(link.closed).toBe(true);
  finish(new Uint8Array(32).fill(1)); await Promise.resolve(); await Promise.resolve();
  expect(link.sent.length).toBe(0); expect(original).toEqual(key());
});
test('cancellation before start avoids connect and cleanup exceptions cannot strand caller', async () => {
  let connects = 0; const timer = new Timer();
  const attempt = new CompanionSyncAttempt({ async connect() { connects++; return new Link(); }, cancel() { throw Error('OS cleanup'); } }, crypto(1), timer, 1000);
  attempt.cancel(); await expect(attempt.start('app', 'phone', 'watch', key(), true, ['state'])).rejects.toThrow('used');
  expect(connects).toBe(0);
});
test('stalled HMAC is bounded even when connector and link cleanup throw', async () => {
  const link = new Link();
  link.queue.push(new TextEncoder().encode(JSON.stringify({ type: 'hello', protocolVersion: 1,
    appId: 'app', sender: 'watch', recipient: 'phone', initiator: false, nonce: Array(32).fill(2) })));
  let started!: () => void, finish!: (bytes: Uint8Array) => void;
  const entered = new Promise<void>(resolve => { started = resolve; });
  const port = crypto(1);
  port.hmacSha256 = () => { started(); return new Promise(resolve => { finish = resolve; }); };
  link.close = () => { link.closed = true; throw Error('link cleanup'); };
  const timer = new Timer();
  const attempt = new CompanionSyncAttempt({ async connect() { return link; }, cancel() { throw Error('connector cleanup'); } }, port, timer, 1000);
  const pending = attempt.start('app', 'phone', 'watch', key(), true, ['state']);
  await entered; const result = Promise.allSettled([pending]); timer.expire();
  const settled = (await result)[0]; expect(settled.status).toBe('rejected');
  if (settled.status === 'rejected') expect(settled.reason.message).toBe('sync attempt deadline');
  expect(link.closed).toBe(true); expect(link.sent.length).toBe(1);
  finish(new Uint8Array(32)); await Promise.resolve(); await Promise.resolve();
  expect(link.sent.length).toBe(1);
});
test('successful handoff cancels attempt timer and later cancellation does not close caller connection', async () => {
  const left = new Link(), right = new Link(); left.peer = right; right.peer = left;
  const timer = new Timer(); let cancelled = false;
  const attempt = new CompanionSyncAttempt({ async connect() { return left; }, cancel() { cancelled = true; } }, crypto(1), timer, 1000);
  const peer = new CompanionSyncExchange(right, crypto(2));
  const [connection, remote] = await Promise.all([
    attempt.start('app', 'phone', 'watch', key(), true, ['state']),
    peer.establish('app', 'watch', 'phone', key(), false, ['state'])
  ]);
  expect(timer.cancelled).toBe(true); attempt.cancel(); timer.expire();
  expect(left.closed || cancelled).toBe(false);
  expect((await remote.verify(await connection.session.send('state', 'one', [1]))).delivery).toBe('pending');
  connection.close(); expect(left.closed).toBe(true); remote.close(); right.close();
});
