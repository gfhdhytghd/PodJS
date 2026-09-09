import { test, expect } from 'bun:test';
import { InstalledSyncConnection } from '../platforms/harmony/entry/src/main/ets/InstalledSyncConnection';

function fixture() {
  const allowed = new Set(['companion.sync.state']);
  const counts = { opens: 0, starts: 0, closes: 0, drives: 0, fileSteps: 0, fileCloses: 0, acks: 0 };
  let finish!: (value: any) => void, grants: string[] = [];
  const transport = {
    stopped: new Promise<any>(done => { finish = done; }),
    driveOutgoing() { counts.drives++; }, close() { finish({ reason: 'closed' }); },
    synchronizeState: () => ({ done: Promise.resolve(9), cancel() {} }),
    async acknowledge() { counts.acks++; }
  };
  const files = { async step() { counts.fileSteps++; return 'idle'; }, close() { counts.fileCloses++; } };
  const client: any = {
    appId: () => 'app', localId: () => 'watch', attach: () => transport,
    close() { counts.closes++; }, createRegisteredFileDriver: () => files
  };
  const attempt: any = { async start(_app: string, _local: string, _peer: string, _initiator: boolean, channels: string[]) {
    counts.starts++; grants = channels; return { close() {} };
  }, cancel() {} };
  const host = new InstalledSyncConnection(async () => { counts.opens++; return client; }, name => allowed.has(name));
  return { host, client, files, attempt, transport, counts, allowed, grants: () => grants };
}
test('installed ACK routing requires the same foreground peer and message grant', async () => {
  const f = fixture(); f.allowed.add('companion.sync.message'); f.host.setActive(true);
  await f.host.acknowledgeMessage({ peer: 'phone' } as any); expect(f.counts.acks).toBe(0);
  await f.host.connect(f.attempt, 'phone', true);
  await f.host.acknowledgeMessage({ peer: 'other' } as any); expect(f.counts.acks).toBe(0);
  await f.host.acknowledgeMessage({ peer: 'phone' } as any); expect(f.counts.acks).toBe(1);
  f.host.setActive(false); await f.host.acknowledgeMessage({ peer: 'phone' } as any); expect(f.counts.acks).toBe(1);
});
test('installed connection requires foreground and exact capabilities, then background closes without reconnect', async () => {
  const f = fixture();
  await expect(f.host.connect(f.attempt, 'phone', true)).rejects.toThrow('foreground'); expect(f.counts.opens).toBe(0);
  f.host.setActive(true); await f.host.connect(f.attempt, 'phone', true);
  expect(() => f.host.synchronizeState('other')).toThrow('approved');
  expect(await f.host.synchronizeState('phone').done).toBe(9);
  expect(f.grants()).toEqual(['state', 'ack']); expect(f.counts.drives).toBe(1);
  await f.host.pump(); expect(f.counts.drives).toBe(2); expect(f.counts.fileSteps).toBe(0);
  f.host.setActive(false); expect(f.counts.closes).toBe(1);
  f.host.setActive(true); await f.host.pump(); expect(f.counts.starts).toBe(1); expect(f.host.status().phase).toBe('idle');
});
test('late installed client initialization cannot start a background handshake', async () => {
  const f = fixture(); let release!: (client: any) => void;
  const host = new InstalledSyncConnection(() => new Promise(done => { release = done; }), () => true);
  host.setActive(true); const opening = host.connect(f.attempt, 'phone', true);
  await expect(host.connect(f.attempt, 'phone', true)).rejects.toThrow('already active');
  host.setActive(false); release(f.client);
  await expect(opening).rejects.toThrow('cancelled'); expect(f.counts.starts).toBe(0); expect(f.counts.closes).toBe(0);
});
test('file pumping is serialized and revoking a grant disconnects existing ownership', async () => {
  const f = fixture(); f.allowed.add('companion.sync.file'); f.host.setActive(true);
  await f.host.connect(f.attempt, 'phone', true); expect(f.grants()).toEqual(['state', 'file', 'ack']);
  let release!: (value: string) => void;
  f.files.step = () => { f.counts.fileSteps++; return new Promise(done => { release = done; }); };
  const pumping = f.host.pump(); await f.host.pump(); expect(f.counts.fileSteps).toBe(1);
  f.allowed.delete('companion.sync.file'); await f.host.pump();
  expect(f.counts.fileCloses).toBe(1); expect(f.counts.closes).toBe(1); expect(f.host.status().phase).toBe('idle');
  release('idle'); await pumping; expect(f.counts.starts).toBe(1);
});
