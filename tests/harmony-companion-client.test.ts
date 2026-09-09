import { test, expect } from 'bun:test';
import { resolve } from 'node:path';

test('native client assembles lazy channels and isolates foreground connection lifetimes', async () => {
  const build = await Bun.build({
    entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionClient.ets')], target: 'bun', write: false,
    plugins: [{ name: 'client-system-stubs', setup(builder) {
      builder.onResolve({ filter: /^\.\/NativeCompanion/ }, args => ({ path: resolve(args.resolveDir, args.path + '.ets') }));
      builder.onResolve({ filter: /^(libpodjs_companion\.so|@ohos\.|@kit\.)/ }, args => ({ path: args.path, namespace: 'client-system' }));
      builder.onLoad({ filter: /.*/, namespace: 'client-system' }, args => ({ contents: args.path === 'libpodjs_companion.so'
        ? `export function companionStateRead(){throw Error('unexpected IO')}; export const companionStateCompareExchange=companionStateRead;
          export const companionOutboxRead=companionStateRead, companionOutboxCompareExchange=companionStateRead;
          export const companionInboxRead=companionStateRead, companionInboxCompareExchange=companionStateRead;
          export const companionFileRequestsRead=companionStateRead, companionFileRequestsCompareExchange=companionStateRead;
          export const companionOutgoingTransfersRead=companionStateRead, companionOutgoingTransfersCompareExchange=companionStateRead;
          export const incomingFilesOpen=companionStateRead, outgoingFilesOpen=companionStateRead, incomingFilesClose=companionStateRead, incomingFilesRun=companionStateRead;`
        : 'export default {}; export const util = { TextEncoder };', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }]
  });
  if (!build.success) throw new Error(build.logs.map(String).join('\n'));
  const module = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  const client = new module.NativeCompanionClient({ filesDir: '/unused' }, 'app', 'phone');
  const offerClient = new module.NativeCompanionClient({ filesDir: '/unused' }, 'app', 'phone');
  const order: string[] = []; let cancelled = false, conflict = false, registered = false;
  offerClient.resolveFileIdentity = async () => { order.push('resolve'); return conflict ? { peer: 'other', direction: 'incoming' } : registered ? { peer: 'watch', direction: 'outgoing' } : null; };
  offerClient.outgoingFiles.list = async () => { order.push('sources'); return []; };
  offerClient.importGuestFile = async (_id: string, _path: string, _mime: string, _cancel: () => boolean, fresh: boolean, peer: string) => {
    expect(fresh).toBe(true); expect(peer).toBe('watch'); registered = true; order.push('import'); return { transfer_id: 'fresh' };
  };
  offerClient.outgoingTransfers.register = async (peer: string, manifest: object) => { order.push('register'); return { peer, manifest, phase: 'queued' }; };
  expect((await offerClient.queueGuestFile('watch', 'fresh', 'source.bin', 'text/plain')).phase).toBe('queued');
  expect(order).toEqual(['resolve', 'sources', 'import', 'resolve', 'register']);
  order.length = 0; registered = false;
  await expect(offerClient.queueGuestFile('phone', 'fresh', 'source.bin', '')).rejects.toThrow('peer');
  await expect(offerClient.queueGuestFile('watch', 'fresh', 'source.bin', '\u0000')).rejects.toThrow('MIME');
  expect(order).toEqual([]);
  offerClient.importGuestFile = async () => { order.push('import'); cancelled = true; return { transfer_id: 'fresh' }; };
  await expect(offerClient.queueGuestFile('watch', 'fresh', 'source.bin', '', () => cancelled)).rejects.toThrow('cancelled');
  expect(order.includes('register')).toBe(false);
  order.length = 0; cancelled = false;
  offerClient.importGuestFile = async () => { order.push('import'); conflict = true; return { transfer_id: 'fresh' }; };
  await expect(offerClient.queueGuestFile('watch', 'fresh', 'source.bin', '')).rejects.toThrow('already exists');
  expect(order.includes('register')).toBe(false);
  expect(client.state.matchesIdentity('app', 'phone')).toBe(true);
  expect(client.incomingFiles.matchesIdentity('app', 'phone')).toBe(true);
  let phase = 'staging';
  client.outgoingFiles.list = async () => [{ phase, manifest: { transfer_id: 'saved', size: 0,
    sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', mime: '', chunk_hashes: [] } }];
  await expect(client.createStoredFileSender('watch', 'saved')).rejects.toThrow('not complete');
  phase = 'complete';
  const sender = await client.createStoredFileSender('watch', 'saved');
  expect(sender.matchesQueue(client.fileRequests, 'watch')).toBe(true);
  await expect(client.createStoredFileSender('watch', 'missing')).rejects.toThrow('not complete');
  function connection(app = 'app', local = 'phone') {
    let finish: (value: null) => void = () => {};
    const value = {
      closed: false,
      session: { appId: () => app, localId: () => local, peerId: () => 'watch', allowsChannel: () => true },
      stream: { read: () => new Promise<null>(resolve => { finish = resolve; }), write: async () => {} },
      close() { value.closed = true; finish(null); }
    }; return value;
  }
  const wrong = connection('other');
  expect(() => client.attach(wrong, 1000)).toThrow('identity mismatch');
  expect(wrong.closed).toBe(false);
  const first = connection(), second = connection();
  expect(() => client.createRegisteredFileDriver('watch')).toThrow('attached peer');
  const transport = client.attach(first, 1000);
  const fileDriver = client.createRegisteredFileDriver('watch');
  expect(client.createRegisteredFileDriver('watch')).toBe(fileDriver);
  expect(() => client.createRegisteredFileDriver('other')).toThrow('attached peer');
  expect(() => client.attach(second, 1000)).toThrow('already attached');
  client.close(); expect(first.closed).toBe(true);
  await expect(fileDriver.step()).rejects.toThrow('closed');
  const replacement = client.attach(second, 1000);
  await transport.stopped; await Promise.resolve();
  expect(() => client.attach(connection(), 1000)).toThrow('already attached');
  expect(second.closed).toBe(false);
  client.close(); await replacement.stopped; expect(second.closed).toBe(true);
  client.close();
});
