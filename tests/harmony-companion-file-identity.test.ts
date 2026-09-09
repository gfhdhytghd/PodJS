import { test, expect } from 'bun:test';
import { resolveCompanionFileIdentity } from '../platforms/harmony/companion/src/main/ets/CompanionFileIdentity';

test('file identity resolves registered directions and refuses cross-direction or peer conflicts', async () => {
  let received: any[] = [], sent: any[] = [], wire: any[] = [];
  const incoming = { listLocal: async () => received } as any;
  const outgoing = { list: async () => sent } as any;
  const requests = { recordsForTransfer: async () => wire } as any;
  const resolve = () => resolveCompanionFileIdentity('file', incoming, outgoing, requests);
  const record = { peer: 'phone', manifest: { transfer_id: 'file' }, phase: 'complete' };
  expect(await resolve()).toBeNull();
  received = [record]; expect(await resolve()).toEqual({ peer: 'phone', direction: 'incoming' });
  sent = [record]; await expect(resolve()).rejects.toThrow('ambiguous');
  received = []; expect(await resolve()).toEqual({ peer: 'phone', direction: 'outgoing' });
  wire = [{ peer: 'phone' }]; expect(await resolve()).toEqual({ peer: 'phone', direction: 'outgoing' });
  wire.push({ peer: 'other' }); await expect(resolve()).rejects.toThrow('ambiguous');
  sent = []; await expect(resolve()).rejects.toThrow('unregistered');
  received = [record]; await expect(resolve()).rejects.toThrow('ambiguous');
  wire = []; received.push({ ...record, peer: 'other' }); await expect(resolve()).rejects.toThrow('ambiguous');
});
test('file identity validates before reading and propagates journal failures', async () => {
  let reads = 0;
  const incoming = { listLocal: async () => { reads++; throw Error('unreadable'); } } as any;
  await expect(resolveCompanionFileIdentity('../bad', incoming, {} as any, {} as any)).rejects.toThrow('invalid');
  expect(reads).toBe(0);
  await expect(resolveCompanionFileIdentity('file', incoming, {} as any, {} as any)).rejects.toThrow('unreadable');
});
