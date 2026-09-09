import { expect, test } from 'bun:test';
import { CompanionRegisteredFileDriver } from '../platforms/harmony/companion/src/main/ets/CompanionRegisteredFileDriver';
import { CompanionFileRequest, encodeFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
function fixture() {
  let pending: any = null, status = 'idle'; const created: string[] = [], driven: any[] = [];
  const records = ['a', 'b'].map(id => ({ peer: 'phone', manifest: { transfer_id: id }, phase: 'queued' }));
  const client: any = {
    fileRequests: { next: async () => pending, completed: async () => [] }, outgoingTransfers: { list: async () => records },
    createRegisteredFileSender: async (_peer: string, id: string) => { created.push(id); return { id }; }
  };
  const transport: any = { fileStatus: () => status, driveFile: async (sender: any) => { driven.push(sender); return status = 'waiting_consent'; } };
  return { client, transport, records, created, driven, driver: new CompanionRegisteredFileDriver(client, transport, 'phone'),
    pending: (id: string) => { const request = new CompanionFileRequest(); request.method = 'status'; request.transfer_id = id; pending = { payload: encodeFileRequest(request) }; },
    status: (value: string) => { status = value; } };
}
test('registered driver restores in-flight ID before queued order and reuses sender while waiting', async () => {
  const f = fixture(); f.pending('b');
  expect(await f.driver.step()).toBe('waiting_consent'); expect(await f.driver.step()).toBe('waiting_consent');
  expect(f.created).toEqual(['b']); expect(f.driven[0]).toBe(f.driven[1]);
});
test('registered driver moves to next task after asynchronous terminal completion and leaves other peers untouched', async () => {
  const f = fixture(); await f.driver.step();
  f.records[0].phase = 'complete'; f.status('complete'); await f.driver.step(); expect(f.created).toEqual(['a', 'b']);
  f.records[1].phase = 'cancelled'; f.status('cancelled'); expect(await f.driver.step()).toBe('idle');
  f.records.push({ peer: 'other', manifest: { transfer_id: 'c' }, phase: 'queued' });
  expect(await f.driver.step()).toBe('idle'); expect(f.driven).toHaveLength(2);
});
test('registered driver refuses unregistered queue and close during selection prevents late driving', async () => {
  const f = fixture(); f.pending('unknown'); await expect(f.driver.step()).rejects.toThrow('not registered'); expect(f.driven).toHaveLength(0);
  const late = fixture(); let release!: (sender: any) => void;
  late.client.createRegisteredFileSender = () => new Promise(resolve => { release = resolve; });
  const step = late.driver.step();
  while (!release) await Promise.resolve();
  await expect(late.driver.step()).rejects.toThrow('busy');
  late.driver.close(); release({ id: 'a' }); await expect(step).rejects.toThrow('closed'); expect(late.driven).toHaveLength(0);
});
test('cleanup runs only with an empty request queue and closing during cleanup prevents a new sender', async () => {
  const f = fixture(); let cleanups = 0;
  const driver = new CompanionRegisteredFileDriver(f.client, f.transport, 'phone', async () => { cleanups++; return false; });
  f.pending('a'); await driver.step(); expect(cleanups).toBe(0);
  const g = fixture(); let release!: (value: boolean) => void;
  const late = new CompanionRegisteredFileDriver(g.client, g.transport, 'phone', () => new Promise(done => { release = done; }));
  const step = late.step(); while (!release) await Promise.resolve();
  late.close(); release(true); await expect(step).rejects.toThrow('closed'); expect(g.created).toEqual([]);
});
