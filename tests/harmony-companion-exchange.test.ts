import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionSyncExchange, decodeSyncObject, type CompanionSyncChallengeCrypto, type CompanionSyncPacketStream } from '../platforms/harmony/companion/src/main/ets/CompanionSyncExchange';
class Link implements CompanionSyncPacketStream {
  peer!: Link; closed = false; sent: Uint8Array[] = [];
  queue: Uint8Array[] = []; waiting: ((packet: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> {
    if (this.closed) return null;
    if (this.queue.length) return this.queue.shift()!;
    return new Promise(resolve => { this.waiting = resolve; });
  }
  async write(packet: Uint8Array) {
    if (this.closed || this.peer.closed) throw Error('link closed');
    this.sent.push(packet.slice()); const copy = packet.slice();
    if (this.peer.waiting) { const resolve = this.peer.waiting; this.peer.waiting = null; resolve(copy); }
    else this.peer.queue.push(copy);
  }
  close() {
    this.closed = true; this.waiting?.(null); this.waiting = null;
    this.peer.closed = true; this.peer.waiting?.(null); this.peer.waiting = null;
  }
}
function links() { const a = new Link(), b = new Link(); a.peer = b; b.peer = a; return { a, b }; }
const crypto = (nonce: number): CompanionSyncChallengeCrypto => ({
  async challenge() { return new Uint8Array(32).fill(nonce); },
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
});
const key = () => new Uint8Array(32).fill(7);
test('Android hello/proof field shapes and role ordering establish mutually authenticated sessions', async () => {
  const { a, b } = links(); const x = new CompanionSyncExchange(a, crypto(1)), y = new CompanionSyncExchange(b, crypto(2));
  const [sa, sb] = await Promise.all([x.establish('app', 'phone', 'watch', key(), true, ['state']), y.establish('app', 'watch', 'phone', key(), false, ['state'])]);
  expect(JSON.parse(new TextDecoder().decode(a.sent[0]))).toEqual({ type: 'hello', protocolVersion: 1, appId: 'app', sender: 'phone', recipient: 'watch', initiator: true, nonce: Array(32).fill(1) });
  expect(Object.keys(JSON.parse(new TextDecoder().decode(a.sent[1]))).sort()).toEqual(['proof', 'type']);
  expect(a.sent.length).toBe(2); expect(b.sent.length).toBe(2);
  expect((await sb.verify(await sa.send('state', 'one', [1]))).delivery).toBe('pending');
  await expect(x.establish('app', 'phone', 'watch', key(), true, ['state'])).rejects.toThrow('used');
  sa.close(); sb.close();
});
test('wrong key closes both peers and responder does not disclose its proof before authenticating', async () => {
  const { a, b } = links();
  const results = await Promise.allSettled([
    new CompanionSyncExchange(a, crypto(1)).establish('app', 'phone', 'watch', key(), true, ['state']),
    new CompanionSyncExchange(b, crypto(2)).establish('app', 'watch', 'phone', new Uint8Array(32).fill(8), false, ['state'])
  ]);
  expect(results.every(r => r.status === 'rejected')).toBe(true);
  expect(a.closed && b.closed).toBe(true); expect(b.sent.length).toBe(1);
});
test('wrong approved identity rejects hello before responder sends anything', async () => {
  const { a, b } = links();
  const results = await Promise.allSettled([
    new CompanionSyncExchange(a, crypto(1)).establish('app', 'imposter', 'watch', key(), true, ['state']),
    new CompanionSyncExchange(b, crypto(2)).establish('app', 'watch', 'phone', key(), false, ['state'])
  ]);
  expect(results.every(r => r.status === 'rejected')).toBe(true); expect(b.sent.length).toBe(0);
});
test('equal challenges fail before proofs; EOF while waiting closes the attempt', async () => {
  const { a, b } = links();
  const results = await Promise.allSettled([
    new CompanionSyncExchange(a, crypto(1)).establish('app', 'phone', 'watch', key(), true, ['state']),
    new CompanionSyncExchange(b, crypto(1)).establish('app', 'watch', 'phone', key(), false, ['state'])
  ]);
  expect(results.every(r => r.status === 'rejected')).toBe(true);
  expect(a.sent.length).toBe(1); expect(b.sent.length).toBe(1);
  const next = links(), exchange = new CompanionSyncExchange(next.a, crypto(1));
  const pending = exchange.establish('app', 'phone', 'watch', key(), true, ['state']);
  await next.b.read(); next.b.close();
  await expect(pending).rejects.toThrow('handshake'); expect(next.a.closed).toBe(true);
});
test('strict object decoder rejects duplicate escaped aliases, malformed UTF-8 and oversized input', () => {
  const encode = (text: string) => new TextEncoder().encode(text);
  for (const text of ['{"type":"hello","type":"hello"}', '{"type":1,"t\\u0079pe":2}', 'null', '[]'])
    expect(() => decodeSyncObject(encode(text), 8192)).toThrow();
  expect(() => decodeSyncObject(new Uint8Array([0xc0, 0x80]), 8192)).toThrow('UTF-8');
  expect(() => decodeSyncObject(encode('{"x":"' + 'a'.repeat(8192) + '"}'), 8192)).toThrow('large');
  expect(decodeSyncObject(encode('{"x":"escaped\\\"key:","y":[1,2]}'), 8192)).toEqual({ x: 'escaped"key:', y: [1, 2] });
});
test('closing while OS random is pending suppresses all later IO and preserves caller key', async () => {
  const { a } = links(); let finish!: (bytes: Uint8Array) => void;
  const port = crypto(1); port.challenge = () => new Promise(resolve => { finish = resolve; });
  const exchange = new CompanionSyncExchange(a, port), original = key();
  const pending = exchange.establish('app', 'phone', 'watch', original, true, ['state']);
  exchange.close(); finish(new Uint8Array(32).fill(1));
  await expect(pending).rejects.toThrow('closed'); expect(a.sent.length).toBe(0); expect(original).toEqual(key());
});
