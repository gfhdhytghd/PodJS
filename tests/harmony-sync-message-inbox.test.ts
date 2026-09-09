import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionMessageInbox } from '../platforms/harmony/entry/src/main/ets/CompanionMessages';
import { CompanionMessageEnvelope, encodeMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
import { SyncMessageInbox } from '../platforms/harmony/entry/src/main/ets/SyncMessageInbox';
import { SyncMessageServices } from '../platforms/harmony/entry/src/main/ets/SyncMessageServices';
import { ServiceRequest, ServiceReply, UnsupportedServices } from '../platforms/harmony/entry/src/main/ets/ServicePump';
function fixture() {
  let raw: string | null = null, fail = false;
  const inbox = new CompanionMessageInbox('app', 'watch', {
    async read() { return raw; }, async compareExchange(before, after) {
      if (fail || raw !== before) return false; raw = after; return true;
    }
  }, { async sha256(bytes) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } });
  return { inbox, fail(value: boolean) { fail = value; } };
}
function receive(inbox: CompanionMessageInbox, id = 'id', text = '{"n":1}', expires = 1000) {
  return inbox.receiveAuthenticated('phone', id, encodeMessageEnvelope(new CompanionMessageEnvelope(expires, false, new TextEncoder().encode(text))), 1);
}
test('instant ACK notification follows durable commit and its failure cannot undo guest acknowledgement', async () => {
  const { inbox, fail } = fixture(); await receive(inbox); let notifications = 0;
  const statuses: string[] = [], pendingCounts: number[] = [];
  const delivery = new SyncMessageInbox(async () => inbox, () => 10, () => true, async applied => {
    notifications++; statuses.push(applied.status); pendingCounts.push((await inbox.pending(10, 100)).length);
    throw Error('transport stopped');
  });
  delivery.setActive(true); await delivery.pump(); fail(true);
  await expect(delivery.acknowledge('phone', 'id', () => true)).rejects.toThrow('conflict'); expect(notifications).toBe(0);
  fail(false); await delivery.acknowledge('phone', 'id', () => true);
  await new Promise(done => setTimeout(done, 0)); expect(notifications).toBe(1); expect(await inbox.pending(10, 100)).toHaveLength(0);
  expect(statuses).toEqual(['applied']); expect(pendingCounts).toEqual([0]);
});
test('receipt is registered only after accepted event and ACK retry stays durable', async () => {
  const { inbox, fail } = fixture(); await receive(inbox);
  let accepts = false; const frames: any[] = [];
  const delivery = new SyncMessageInbox(async () => inbox, () => 10, json => { if (!accepts) return false; frames.push(JSON.parse(json)); return true; });
  delivery.setActive(true); await delivery.pump();
  await expect(delivery.acknowledge('phone', 'id', () => true)).rejects.toThrow('not exposed');
  accepts = true; await delivery.pump(); await delivery.pump();
  expect(frames).toHaveLength(2);
  expect(frames[0]).toEqual({ t: 'sync.message.received', value: { peerId: 'phone', message: { messageId: 'id', payload: { n: 1 }, ttlMs: 990, priority: 'normal' } } });
  fail(true); await expect(delivery.acknowledge('phone', 'id', () => true)).rejects.toThrow('conflict');
  expect(await inbox.pending(10, 100)).toHaveLength(1);
  fail(false); await delivery.acknowledge('phone', 'id', () => true); await delivery.acknowledge('phone', 'id', () => true);
  expect(await inbox.pending(10, 100)).toEqual([]);
});
test('malformed and duplicate JSON stay pending without blocking later pages', async () => {
  const { inbox } = fixture();
  for (let i = 0; i < 100; i++) await receive(inbox, 'bad' + i, i % 2 ? '{"a":1,"\\u0061":2}' : 'not-json');
  await receive(inbox, 'good', 'null');
  const ids: string[] = [];
  const delivery = new SyncMessageInbox(async () => inbox, () => 10, json => { ids.push(JSON.parse(json).value.message.messageId); return true; });
  delivery.setActive(true); await delivery.pump(); expect(ids).toEqual([]);
  await delivery.pump(); expect(ids).toEqual(['good']);
  await expect(delivery.acknowledge('phone', 'bad0', () => true)).rejects.toThrow('not exposed');
  await delivery.acknowledge('phone', 'good', () => true);
  expect(await inbox.pending(10, 100)).toHaveLength(100);
});
test('background transition invalidates late pumps and clears old guest receipts', async () => {
  const { inbox } = fixture(); await receive(inbox); let finish!: (value: CompanionMessageInbox) => void;
  const pending = new Promise<CompanionMessageInbox>(done => { finish = done; }); let calls = 0;
  const delivery = new SyncMessageInbox(() => pending, () => 10, () => { calls++; return true; });
  delivery.setActive(true); const pumping = delivery.pump();
  delivery.setActive(false); delivery.setActive(true); finish(inbox); await pumping; expect(calls).toBe(0);
  await delivery.pump(); expect(calls).toBe(1);
  delivery.setActive(false); delivery.setActive(true);
  await expect(delivery.acknowledge('phone', 'id', () => true)).rejects.toThrow('not exposed');
  expect(await inbox.pending(10, 100)).toHaveLength(1);
});
test('guest ACK is explicit, cancellable, and only applies an exposed delivery', async () => {
  const { inbox } = fixture(); await receive(inbox);
  const delivery = new SyncMessageInbox(async () => inbox, () => 10, () => true); delivery.setActive(true);
  const handler = new SyncMessageServices(new UnsupportedServices(), async () => { throw Error('send unused'); }, () => 10, delivery);
  const request = new ServiceRequest(); request.id = 1; request.method = 'sync.messages.ack'; request.args = { peerId: 'phone', messageId: 'id' };
  const run = () => new Promise<ServiceReply>(done => handler.handle(request, done));
  expect(await run()).toMatchObject({ code: 'host_error' }); await delivery.pump();
  const cancelled: ServiceReply[] = []; handler.handle(request, reply => cancelled.push(reply)); handler.cancel(1);
  await Promise.resolve(); expect(cancelled).toEqual([]); expect(await inbox.pending(10, 100)).toHaveLength(1);
  expect(await run()).toMatchObject({ ok: true, value: null });
  expect(await inbox.pending(10, 100)).toEqual([]); expect(await run()).toMatchObject({ ok: true });
});
