import { test, expect } from 'bun:test';
import { resolve } from 'node:path';
import { encodeSyncPacket } from '../platforms/harmony/companion/src/main/ets/CompanionSyncFraming';

class Connection {
  peer = 'AA:BB:CC:DD:EE:FF'; closed = false; connects = 0; sent: Uint8Array[] = [];
  listeners = new Map<string, (value: any) => void>();
  on(event: string, callback: (value: any) => void) { this.listeners.set(event, callback); }
  off(event: string) { this.listeners.delete(event); }
  emit(event: string, value: any) { this.listeners.get(event)?.(value); }
  connect() { this.connects++; }
  getPeerDeviceId() { return this.peer; }
  sendData(bytes: ArrayBuffer) { this.sent.push(new Uint8Array(bytes).slice()); }
  close() { this.closed = true; }
}
let moduleId = 0;
async function nativeModule(createConnection: (device: string, name: string) => Connection, createServer?: (name: string) => Server) {
  // Execute the actual native adapter source, substituting only its OS module.
  (globalThis as any).__podjsLinkEnhanceTest = { createConnection, createServer };
  const build = await Bun.build({
    entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionDistributed.ets')], target: 'bun',
    plugins: [{ name: 'mock-system-link', setup(builder) {
      builder.onResolve({ filter: /^@ohos\.distributedsched\.linkEnhance$/ }, () => ({ path: 'system-link', namespace: 'mock-system-link' }));
      builder.onLoad({ filter: /.*/, namespace: 'mock-system-link' }, () => ({ contents: 'export default globalThis.__podjsLinkEnhanceTest;', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }]
  });
  expect(build.success).toBe(true);
  const source = await build.outputs[0].text();
  return import('data:text/javascript;base64,' + Buffer.from(source + '\n//' + ++moduleId).toString('base64'));
}
test('native connector binds approved device and service then adapts system chunks', async () => {
  const connection = new Connection(); let target = '';
  const module = await nativeModule((device, service) => { target = device + '/' + service; return connection; });
  const connector = new module.NativeCompanionDistributedConnector('aa:bb:cc:dd:ee:ff', 'podjs.app', 1000);
  const pending = connector.connect(); expect(target).toBe('AA:BB:CC:DD:EE:FF/podjs.app');
  connection.emit('connectResult', { success: true, reason: 0, deviceId: connection.peer });
  const stream = await pending;
  await stream.write(new Uint8Array(2048).fill(7)); expect(connection.sent.map(bytes => bytes.length)).toEqual([1024, 1024, 4]);
  const incoming = stream.read(); connection.emit('dataReceived', encodeSyncPacket(new Uint8Array([9])).buffer);
  expect(await incoming).toEqual(new Uint8Array([9]));
  stream.close(); expect(connection.closed).toBe(true); expect(connection.listeners.size).toBe(0);
});
class Server extends Connection {
  starts = 0; stops = 0;
  start() { this.starts++; }
  stop() { this.stops++; this.emit('serverStopped', 0); }
}
test('native acceptor ignores other peers, stops after acceptance and owns server cleanup', async () => {
  const server = new Server(); let name = '';
  const module = await nativeModule(() => { throw new Error('unexpected client'); }, service => { name = service; return server; });
  const acceptor = new module.NativeCompanionDistributedAcceptor(server.peer, 'podjs.app', 1000);
  const pending = acceptor.connect(); expect(name).toBe('podjs.app'); expect(server.starts).toBe(1);
  const wrong = new Connection(); wrong.peer = '00:11:22:33:44:55';
  server.emit('connectionAccepted', wrong); expect(wrong.closed).toBe(true); expect(server.stops).toBe(0);
  const peer = new Connection(); server.emit('connectionAccepted', peer);
  const stream = await pending; expect(server.stops).toBe(1); expect(server.closed).toBe(false);
  const extra = new Connection(); server.emit('connectionAccepted', extra); expect(extra.closed).toBe(true);
  const read = stream.read(); peer.emit('dataReceived', encodeSyncPacket(new Uint8Array([3])).buffer);
  expect(await read).toEqual(new Uint8Array([3]));
  stream.close(); expect(peer.closed).toBe(true); expect(server.closed).toBe(true); expect(server.listeners.size).toBe(0);
});
test('native acceptor cancellation rejects pending wait and closes queued late connections', async () => {
  const server = new Server();
  const module = await nativeModule(() => new Connection(), () => server);
  const acceptor = new module.NativeCompanionDistributedAcceptor(server.peer, 'podjs.app', 1000);
  const pending = acceptor.connect(), accepted = server.listeners.get('connectionAccepted')!;
  acceptor.cancel(); await expect(pending).rejects.toThrow('cancelled');
  const late = new Connection(); accepted(late); expect(late.closed).toBe(true);
  expect(server.closed).toBe(true); await expect(acceptor.connect()).rejects.toThrow('used');
});
test('native acceptor surfaces system stop and start failure', async () => {
  const server = new Server();
  const module = await nativeModule(() => new Connection(), () => server);
  const acceptor = new module.NativeCompanionDistributedAcceptor(server.peer, 'podjs.app', 1000);
  const pending = acceptor.connect(); server.emit('serverStopped', 7);
  await expect(pending).rejects.toThrow('stopped: 7'); expect(server.closed).toBe(true);
  const failed = new Server(); failed.start = () => { throw new Error('permission denied'); };
  const other = await nativeModule(() => new Connection(), () => failed);
  await expect(new other.NativeCompanionDistributedAcceptor(failed.peer, 'podjs.app', 1000).connect()).rejects.toThrow('permission denied');
  expect(failed.closed).toBe(true);
});
test('accepted channel expiry and remote disconnect release server without caller cleanup', async () => {
  for (const expires of [false, true]) {
    const server = new Server(), peer = new Connection();
    const module = await nativeModule(() => new Connection(), () => server);
    const acceptor = new module.NativeCompanionDistributedAcceptor(server.peer, 'podjs.app', expires ? 10 : 1000);
    const pending = acceptor.connect(); server.emit('connectionAccepted', peer);
    const stream = await pending, read = stream.read();
    const rejection = read.catch((error: Error) => error.message);
    if (!expires) peer.emit('disconnected', 8);
    expect(await rejection).toContain(expires ? 'deadline' : 'ended: 8');
    expect(server.closed).toBe(true); expect(peer.closed).toBe(true); expect(server.listeners.size).toBe(0);
  }
});
test('failure to stop listening refuses the accepted channel and releases both resources', async () => {
  const server = new Server(), peer = new Connection();
  server.stop = () => { throw new Error('stop failed'); };
  const module = await nativeModule(() => new Connection(), () => server);
  const acceptor = new module.NativeCompanionDistributedAcceptor(server.peer, 'podjs.app', 1000);
  const pending = acceptor.connect(); server.emit('connectionAccepted', peer);
  await expect(pending).rejects.toThrow('stop failed');
  expect(server.closed).toBe(true); expect(peer.closed).toBe(true);
});
test('native connector rejects routing mismatch and cancellation cannot accept late events', async () => {
  const wrong = new Connection(); wrong.peer = '00:11:22:33:44:55';
  const module = await nativeModule(() => wrong);
  const connector = new module.NativeCompanionDistributedConnector('AA:BB:CC:DD:EE:FF', 'podjs.app', 1000);
  const pending = connector.connect();
  wrong.emit('connectResult', { success: true, reason: 0, deviceId: 'AA:BB:CC:DD:EE:FF' });
  await expect(pending).rejects.toThrow('peer mismatch'); expect(wrong.closed).toBe(true);
  const late = new Connection(), other = await nativeModule(() => late);
  const cancelled = new other.NativeCompanionDistributedConnector(late.peer, 'podjs.app', 1000);
  const waiting = cancelled.connect(), callback = late.listeners.get('connectResult')!;
  cancelled.cancel(); await expect(waiting).rejects.toThrow('cancelled');
  callback({ success: true, reason: 0, deviceId: late.peer });
  expect(late.closed).toBe(true); expect(late.sent.length).toBe(0);
});
test('native permission failure remains an error and does not select another transport', async () => {
  let creates = 0;
  const module = await nativeModule(() => { creates++; throw new Error('permission denied (201)'); });
  const connector = new module.NativeCompanionDistributedConnector('AA:BB:CC:DD:EE:FF', 'podjs.app', 1000);
  await expect(connector.connect()).rejects.toThrow('permission denied'); expect(creates).toBe(1);
  await expect(connector.connect()).rejects.toThrow('used');
});
