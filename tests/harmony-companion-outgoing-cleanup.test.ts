import { test, expect } from 'bun:test';
import { cleanupTerminalOutgoingSource } from '../platforms/harmony/companion/src/main/ets/CompanionOutgoingCleanup';
function fixture() {
  const manifest = { transfer_id: 'file-a' };
  const record = { peer: 'phone', manifest, phase: 'complete' };
  const source = { manifest, phase: 'complete' };
  const removed: string[] = [];
  const client: any = {
    fileRequests: { next: async () => null, completed: async () => [] },
    outgoingTransfers: { list: async () => [record] }, outgoingFiles: { list: async () => [source],
      remove: async (id: string) => { removed.push(id); source.phase = 'removed'; } },
    resolveFileIdentity: async () => ({ direction: 'outgoing', peer: 'phone' })
  };
  return { client, record, source, removed };
}
test('terminal snapshot cleanup retains registry identity and never touches other peers or queued imports', async () => {
  const f = fixture(); expect(await cleanupTerminalOutgoingSource(f.client, 'other')).toBe(false);
  f.record.phase = 'cancel_requested'; expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(false);
  f.record.phase = 'cancelled'; expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(true);
  expect(f.record.phase).toBe('cancelled'); expect(f.removed).toEqual(['file-a']);
  expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(false);
});
test('pending request or receipt, mismatched identity and cancellation all prevent source deletion', async () => {
  const f = fixture(); f.client.fileRequests.next = async () => ({});
  expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(false);
  f.client.fileRequests.next = async () => null; f.client.fileRequests.completed = async () => [{}];
  expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(false);
  f.client.fileRequests.completed = async () => []; f.client.resolveFileIdentity = async () => ({ direction: 'incoming', peer: 'phone' });
  await expect(cleanupTerminalOutgoingSource(f.client, 'phone')).rejects.toThrow('identity mismatch');
  await expect(cleanupTerminalOutgoingSource(f.client, 'phone', () => true)).rejects.toThrow('cancelled');
  expect(f.removed).toEqual([]);
});
test('failed removal is retried from terminal evidence and a changed manifest is refused', async () => {
  const f = fixture(); const remove = f.client.outgoingFiles.remove;
  f.client.outgoingFiles.remove = async () => { throw Error('disk failure'); };
  await expect(cleanupTerminalOutgoingSource(f.client, 'phone')).rejects.toThrow('disk failure');
  f.client.outgoingFiles.remove = remove; expect(await cleanupTerminalOutgoingSource(f.client, 'phone')).toBe(true);
  const g = fixture(); g.source.manifest = { transfer_id: 'file-a', size: 5 } as any;
  await expect(cleanupTerminalOutgoingSource(g.client, 'phone')).rejects.toThrow('manifest mismatch'); expect(g.removed).toEqual([]);
});
