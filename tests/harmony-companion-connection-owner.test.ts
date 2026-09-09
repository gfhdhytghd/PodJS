import { test, expect } from 'bun:test';
import { CompanionConnectionOwner } from '../platforms/harmony/companion/src/main/ets/CompanionConnectionOwner';

function fixture() {
  let resolve!: (value: any) => void;
  let stop!: (value: { reason: string }) => void;
  const counts = { cancel: 0, raw: 0, transport: 0, client: 0, drive: 0, attach: 0 };
  const raw = { close() { counts.raw++; } };
  const transport = {
    stopped: new Promise<{ reason: string }>(r => { stop = r; }),
    close() { counts.transport++; stop({ reason: 'closed' }); },
    driveOutgoing() { counts.drive++; }
  };
  const attempt = { start: () => new Promise(r => { resolve = r; }), cancel() { counts.cancel++; } };
  const client = { attach() { counts.attach++; return transport; }, close() { counts.client++; } };
  const owner = new CompanionConnectionOwner(client as any);
  return { owner, attempt, client, raw, transport, counts, resolve: () => resolve(raw), stop };
}
const connect = (f: ReturnType<typeof fixture>) => f.owner.connect(f.attempt as any, 'app', 'phone', 'watch', true);
test('owner snapshots exact host channel grants and rejects invalid grants before handshake', async () => {
  const f = fixture(), channels = ['state', 'ack']; let observed: string[] = [];
  f.attempt.start = (...args: any[]) => { observed = args[4]; return Promise.resolve(f.raw); };
  f.owner.subscribe(status => { if (status.phase === 'connecting') channels.push('file'); });
  await f.owner.connect(f.attempt as any, 'app', 'phone', 'watch', true, 1000, channels);
  expect(observed).toEqual(['state', 'ack']); f.owner.disconnect();
  for (const invalid of [[], ['state'], ['ack'], ['state', 'state', 'ack'], ['unknown', 'ack']]) {
    const other = fixture(); let starts = 0; other.attempt.start = () => { starts++; return Promise.resolve(other.raw); };
    await expect(other.owner.connect(other.attempt as any, 'app', 'phone', 'watch', true, 1000, invalid)).rejects.toThrow('grants');
    expect(starts).toBe(0); expect(other.counts.attach).toBe(0);
  }
});

test('owner attaches once, drives outgoing and releases all connection resources', async () => {
  const f = fixture(); const phases: string[] = [];
  const unsubscribe = f.owner.subscribe(s => phases.push(s.phase));
  f.owner.subscribe(() => { throw Error('observer'); });
  const pending = connect(f); f.resolve();
  expect(await pending).toBe(f.transport);
  expect(f.owner.activeTransport()).toBe(f.transport);
  expect(f.counts.drive).toBe(1);
  await expect(connect(f)).rejects.toThrow('already active');
  f.owner.disconnect(); await Promise.resolve();
  expect(f.owner.activeTransport()).toBeNull();
  expect(f.counts.raw).toBe(1);
  expect(f.counts.transport).toBe(1);
  expect(phases).toEqual(['idle', 'connecting', 'connected', 'closed']);
  unsubscribe();
});

test('cancelled handshake closes late connection without attaching', async () => {
  const f = fixture(); const pending = connect(f);
  f.owner.disconnect(); f.resolve();
  await expect(pending).rejects.toThrow('late');
  expect(f.counts.cancel).toBe(1);
  expect(f.counts.raw).toBe(1);
  expect(f.counts.attach).toBe(0);
  expect(f.owner.status().phase).toBe('closed');
});

test('attach failure closes raw connection and reports failure', async () => {
  const f = fixture(); f.client.attach = () => { throw Error('attach failed'); };
  const pending = connect(f); f.resolve();
  await expect(pending).rejects.toThrow('attach failed');
  expect(f.counts.raw).toBe(1);
  expect(f.counts.cancel).toBe(1);
  expect(f.owner.status().phase).toBe('failed');
});

test('transport failure releases ownership and preserves terminal reason', async () => {
  const f = fixture(); const pending = connect(f); f.resolve(); await pending;
  f.stop({ reason: 'failed' }); await Promise.resolve();
  expect(f.owner.status().phase).toBe('failed');
  expect(f.owner.status().reason).toBe('failed');
  expect(f.owner.activeTransport()).toBeNull();
  expect(f.counts.raw).toBe(1);
});

test('synchronous observer cancellation never starts handshake', async () => {
  const f = fixture(); let starts = 0;
  f.attempt.start = () => { starts++; return new Promise(() => {}); };
  f.owner.subscribe(s => { if (s.phase === 'connecting') f.owner.disconnect(); });
  await expect(connect(f)).rejects.toThrow('cancelled');
  expect(starts).toBe(0);
  expect(f.owner.status().phase).toBe('closed');
});
