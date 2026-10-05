import { test, expect } from 'bun:test';
import { CompanionChunkWriter } from '../platforms/harmony/companion/src/main/ets/CompanionChunkWriter';
import { CompanionSyncFraming, encodeSyncPacket } from '../platforms/harmony/companion/src/main/ets/CompanionSyncFraming';

test('1024-byte system submissions reconstruct a full 2 MiB framed payload', async () => {
  const payload = new Uint8Array(2097152).fill(173), decoder = new CompanionSyncFraming();
  let delivered: Uint8Array | null = null, sends = 0, yields = 0;
  const writer = new CompanionChunkWriter({
    sendChunk(bytes) { expect(bytes.length).toBeLessThanOrEqual(1024); sends++; decoder.feed(bytes, frame => { delivered = frame; }); },
    async yieldTurn() { yields++; }, close() {}
  });
  await writer.send(encodeSyncPacket(payload)); decoder.finish();
  expect(delivered as Uint8Array | null).toEqual(payload); expect(sends).toBe(2049); expect(yields).toBe(257);
});
test('caller mutation and a concurrent writer cannot change an in-flight frame', async () => {
  const chunks: Uint8Array[] = []; let finish!: () => void;
  const bytes = new Uint8Array(9000).fill(1);
  const writer = new CompanionChunkWriter({ sendChunk(chunk) { chunks.push(chunk); },
    yieldTurn() { return new Promise(resolve => { finish = resolve; }); }, close() {} });
  const pending = writer.send(bytes); bytes.fill(9);
  await expect(writer.send(new Uint8Array([2]))).rejects.toThrow('concurrent');
  finish(); await Promise.resolve(); await Promise.resolve(); finish(); await pending;
  expect(chunks.length).toBe(9); expect(chunks.every(chunk => chunk.every(byte => byte === 1))).toBe(true);
});
test('close during the event-loop yield prevents any further system submissions', async () => {
  let sends = 0, closed = false;
  const writer = new CompanionChunkWriter({ sendChunk() { sends++; },
    yieldTurn() { return new Promise(resolve => { setTimeout(resolve, 0); }); }, close() { closed = true; } });
  const stop = setTimeout(() => writer.close(), 0);
  await expect(writer.send(new Uint8Array(65536))).rejects.toThrow('closed'); clearTimeout(stop);
  expect(sends).toBe(8); expect(closed).toBe(true);
});
test('OS submission failure closes rather than silently retrying part of a frame', async () => {
  let sends = 0, closed = false;
  const writer = new CompanionChunkWriter({ sendChunk() { if (++sends === 3) throw Error('OS refused chunk'); },
    async yieldTurn() {}, close() { closed = true; } });
  await expect(writer.send(new Uint8Array(9000))).rejects.toThrow('OS refused chunk');
  expect(sends).toBe(3); expect(closed).toBe(true);
  await expect(writer.send(new Uint8Array([1]))).rejects.toThrow('closed');
});
