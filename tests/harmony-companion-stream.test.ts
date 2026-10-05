import { test, expect } from 'bun:test';
import { CompanionPacketStream } from '../platforms/harmony/companion/src/main/ets/CompanionPacketStream';
import { encodeSyncPacket } from '../platforms/harmony/companion/src/main/ets/CompanionSyncFraming';
import { createServer, createConnection, type Socket } from 'node:net';
import { createHash, createHmac } from 'node:crypto';
import { CompanionSyncExchange, decodeSyncObject } from '../platforms/harmony/companion/src/main/ets/CompanionSyncExchange';
import { CompanionSyncFrame } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
import { CompanionState } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { CompanionSyncConnection } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
class Timer {
  expire: () => void = () => {}; cancelled = false;
  schedule(_ms: number, expire: () => void) { this.expire = expire; return () => { this.cancelled = true; }; }
}
class Writer {
  sent: Uint8Array[] = []; finishes: (() => void)[] = []; closed = false;
  async send(bytes: Uint8Array) { this.sent.push(bytes.slice()); await new Promise<void>(resolve => this.finishes.push(resolve)); }
  close() { this.closed = true; }
}
test('one reader, fragmented packets and EOF drain preserve wire order', async () => {
  const writer = new Writer(), timer = new Timer(), stream = new CompanionPacketStream(writer, timer, 1000);
  const pending = stream.read(); await expect(stream.read()).rejects.toThrow('already active');
  const packet = encodeSyncPacket(new Uint8Array([1, 2]));
  stream.receive(packet.slice(0, 3)); stream.receive(packet.slice(3));
  expect(await pending).toEqual(new Uint8Array([1, 2]));
  stream.receive(packet); stream.end();
  expect(await stream.read()).toEqual(new Uint8Array([1, 2])); expect(await stream.read()).toBeNull();
  expect(timer.cancelled && writer.closed).toBe(true);
});
test('concurrent writes are copied, serialized and settle promptly on deadline even if OS send hangs', async () => {
  const writer = new Writer(), timer = new Timer(), stream = new CompanionPacketStream(writer, timer, 1000);
  const bytes = new Uint8Array([1]); const first = stream.write(bytes); bytes[0] = 9;
  const second = stream.write(new Uint8Array([2]));
  expect(writer.sent.length).toBe(1); expect(writer.sent[0][4]).toBe(1);
  writer.finishes[0](); await first; expect(writer.sent.length).toBe(2);
  const pendingRead = stream.read();
  const failures = Promise.allSettled([second, pendingRead]);
  timer.expire();
  for (const result of await failures) {
    expect(result.status).toBe('rejected');
    if (result.status === 'rejected') expect(result.reason.message).toContain('deadline');
  }
  writer.finishes[1](); await Promise.resolve(); expect(writer.closed).toBe(true);
});
test('receive queue overflow and truncated EOF fail closed', async () => {
  for (const overflow of [true, false]) {
    const writer = new Writer(), stream = new CompanionPacketStream(writer, new Timer(), 1000);
    if (overflow) for (let i = 0; i < 33; i++) stream.receive(encodeSyncPacket(new Uint8Array([1])));
    else { stream.receive(new Uint8Array([0, 0])); stream.end(); }
    await expect(stream.read()).rejects.toThrow(overflow ? 'overflow' : 'truncated'); expect(writer.closed).toBe(true);
  }
});
test('write queue overload rejects every queued operation and closes without extra sends', async () => {
  const writer = new Writer(), stream = new CompanionPacketStream(writer, new Timer(), 1000);
  const operations: Promise<void>[] = [];
  for (let i = 0; i < 9; i++) operations.push(stream.write(new Uint8Array([i])));
  expect((await Promise.allSettled(operations)).every(r => r.status === 'rejected')).toBe(true);
  expect(writer.sent.length).toBe(1); expect(writer.closed).toBe(true);
});

test('real loopback TCP carries fragmented handshake, binary frame and bidirectional state driver', async () => {
  function wrap(socket: Socket): CompanionPacketStream {
    const stream = new CompanionPacketStream({
      async send(bytes) {
        // Deliberately fragment headers as well as payloads at the writer boundary.
        for (let offset = 0; offset < bytes.length; offset += 17) await new Promise<void>((resolve, reject) =>
          socket.write(bytes.slice(offset, offset + 17), error => error ? reject(error) : resolve()));
      }, close() { socket.destroy(); }
    }, { schedule(ms, expire) { const timer = setTimeout(expire, ms); return () => clearTimeout(timer); } }, 5000);
    socket.on('data', bytes => stream.receive(typeof bytes === 'string' ? Buffer.from(bytes) : bytes)); socket.on('end', () => stream.end());
    socket.on('error', error => stream.fail(error)); return stream;
  }
  let accept!: (stream: CompanionPacketStream) => void;
  const accepted = new Promise<CompanionPacketStream>(resolve => { accept = resolve; });
  const server = createServer(socket => accept(wrap(socket)));
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address(); if (address === null || typeof address === 'string') throw Error('missing port');
  const client = createConnection({ host: '127.0.0.1', port: address.port }), a = wrap(client);
  const b = await accepted;
  const crypto = (nonce: number) => ({
    async challenge() { return new Uint8Array(32).fill(nonce); },
    async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
    async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
  });
  try {
    const [sa, sb] = await Promise.all([
      new CompanionSyncExchange(a, crypto(1)).establish('app', 'phone', 'watch', new Uint8Array(32).fill(7), true, ['state', 'ack']),
      new CompanionSyncExchange(b, crypto(2)).establish('app', 'watch', 'phone', new Uint8Array(32).fill(7), false, ['state', 'ack'])
    ]);
    try {
      const frame = await sa.send('state', 'binary', [0, 127, 128, 255]);
      await a.write(new TextEncoder().encode(JSON.stringify(frame)));
      const received = decodeSyncObject((await b.read())!, 2097152) as CompanionSyncFrame;
      expect((await sb.verify(received)).delivery).toBe('pending');
      expect(await sb.commit(received.sequence)).toBe(1); expect(received.payload).toEqual(frame.payload);
      class Port {
        raw: string | null = null;
        async read() { return this.raw; }
        async compareExchange(_app: string, old: string | null, next: string) { if (old !== this.raw) return false; this.raw = next; return true; }
      }
      let id = 0;
      const stateCrypto = { async sha256(text: string) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return 'batch-' + ++id; } };
      const left = new CompanionState('app', 'phone', new Port(), stateCrypto), right = new CompanionState('app', 'watch', new Port(), stateCrypto);
      for (let i = 0; i < 600; i++) await left.set('key' + i, '中文' + i);
      await right.set('watchOnly', 'watch');
      const timer = { schedule(ms: number, expire: () => void) { const handle = setTimeout(expire, ms); return () => clearTimeout(handle); } };
      const sender = left.synchronize(new CompanionSyncConnection(sa, a), timer, 4000);
      const receiver = right.synchronize(new CompanionSyncConnection(sb, b), timer, 4000);
      try {
        await Promise.all([sender.synchronize(), receiver.synchronize()]);
        expect(await right.get('key599')).toBe('中文599'); expect(await left.get('watchOnly')).toBe('watch');
      } finally { sender.close(); receiver.close(); }
    } finally { sa.close(); sb.close(); }
  } finally { a.close(); b.close(); await new Promise<void>(resolve => server.close(() => resolve())); }
}, 10000);
