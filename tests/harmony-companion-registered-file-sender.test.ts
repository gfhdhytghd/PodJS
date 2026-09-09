import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionRegisteredFileSender } from '../platforms/harmony/companion/src/main/ets/CompanionRegisteredFileSender';
import { CompanionOutgoingTransfers } from '../platforms/harmony/companion/src/main/ets/CompanionOutgoingTransfers';
import { CompanionFileRequests } from '../platforms/harmony/companion/src/main/ets/CompanionFileRequests';
import { CompanionFileManifest, CompanionFileRequest, decodeFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { CompanionFileReply, encodeFileReply } from '../platforms/harmony/companion/src/main/ets/CompanionFileReply';
class Store {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(expected: string | null, desired: string) { if (this.fail || this.raw !== expected) return false; this.raw = desired; return true; }
}
async function fixture() {
  const registered = new Store(), requests = new Store(); let id = 0;
  const crypto = { async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); }, async messageId() { return 'req-' + ++id; } };
  const queue = () => new CompanionFileRequests('app', 'watch', requests, crypto);
  const registry = () => new CompanionOutgoingTransfers('app', 'watch', registered);
  const manifest = new CompanionFileManifest(); manifest.transfer_id = 'file'; manifest.sha256 = createHash('sha256').digest('hex');
  await registry().register('phone', manifest);
  const sender = () => new CompanionRegisteredFileSender(queue(), 'phone', manifest, { async readChunk() { throw Error('unexpected source read'); } }, crypto, registry());
  const reply = async (phase: string, missing?: number[]) => {
    const pending = (await queue().next('phone'))!; const request = decodeFileRequest(pending.payload);
    const value = new CompanionFileReply(); value.request_sha256 = pending.digest; value.value.phase = phase;
    if (missing !== undefined) value.value.missing = missing;
    await queue().receiveAuthenticated('phone', pending.messageId, encodeFileReply(value, request)); return request.method;
  };
  return { registered, requests, registry, queue, sender, reply };
}
test('sender keeps missing observation until progress commit succeeds', async () => {
  const f = await fixture(), missing = new CompanionFileRequest(); missing.method = 'missing'; missing.transfer_id = 'file';
  await f.queue().enqueue('phone', missing); await f.reply('accepted', []);
  const before = f.requests.raw; f.registered.fail = true;
  await expect(f.sender().step()).rejects.toThrow('conflict'); expect(f.requests.raw).toBe(before);
  f.registered.fail = false; await f.sender().step();
  expect((await f.registry().list())[0]).toMatchObject({ phase: 'queued', progressKnown: true });
  expect(decodeFileRequest((await f.queue().next('phone'))!.payload).method).toBe('finish');
});
test('registered completion commits before clearing receipt and restart cannot reoffer', async () => {
  const f = await fixture();
  const finish = new CompanionFileRequest(); finish.method = 'finish'; finish.transfer_id = 'file';
  await f.queue().enqueue('phone', finish); await f.reply('complete');
  f.registered.fail = true; await expect(f.sender().step()).rejects.toThrow('conflict');
  expect(await f.queue().terminal('phone')).not.toBeNull();
  f.registered.fail = false; f.requests.fail = true;
  await expect(f.sender().step()).rejects.toThrow('conflict');
  expect((await f.registry().list())[0].phase).toBe('complete');
  expect(await f.queue().terminal('phone')).not.toBeNull();
  f.requests.fail = false; expect(await f.sender().step()).toBe('complete');
  expect(await f.queue().terminal('phone')).toBeNull();
  expect(await f.sender().step()).toBe('complete'); expect(await f.queue().next('phone')).toBeNull();
});
test('durable cancellation establishes offer when needed, waits in-flight and preserves terminal identity', async () => {
  const f = await fixture(); await f.registry().transition('phone', 'file', 'cancel_requested');
  expect(await f.sender().step()).toBe('queued'); expect(await f.sender().step()).toBe('awaiting_reply');
  expect(await f.reply('offered')).toBe('offer');
  expect(await f.sender().step()).toBe('queued'); expect(await f.reply('cancelled')).toBe('cancel');
  expect(await f.sender().step()).toBe('cancelled');
  expect((await f.registry().list())[0].phase).toBe('cancelled');
  expect(await f.queue().terminal('phone')).toBeNull(); expect(await f.sender().step()).toBe('cancelled');
});
test('registered sender refuses another transfer queue and cancellation before work writes nothing', async () => {
  const f = await fixture(), request = new CompanionFileRequest(); request.method = 'status'; request.transfer_id = 'other';
  await f.queue().enqueue('phone', request); const before = f.requests.raw;
  await expect(f.sender().step()).rejects.toThrow('identity mismatch'); expect(f.requests.raw).toBe(before);
  await expect(f.sender().step(() => true)).rejects.toThrow('cancelled'); expect(f.requests.raw).toBe(before);
});
