import { test, expect } from 'bun:test';
import { resolve } from 'node:path';

test('installed channels share one lazy SDK owner and retain per-channel gates', async () => {
  const control = { enabled: new Set<string>(), created: 0, reads: 0, fail: true,
    pairingOpens: 0, pairingFail: true, pairingIdentity: [] as string[] };
  (globalThis as any).__installedSyncOwnerTest = control;
  try {
    const build = await Bun.build({
      entrypoints: [resolve('platforms/harmony/entry/src/main/ets/NativeInstalledSync.ets')], target: 'bun', write: false,
      plugins: [{ name: 'installed-owner-stubs', setup(builder) {
        builder.onResolve({ filter: /^(@podjs\/companion$|libpodjs_harmony\.so$|@kit\.|@ohos\.)/ }, args => ({ path: args.path, namespace: 'installed-system' }));
        builder.onLoad({ filter: /.*/, namespace: 'installed-system' }, args => {
          const prelude = 'const control = globalThis.__installedSyncOwnerTest;';
          const contents = args.path === '@podjs/companion' ? `
            export class NativeCompanionStateCas {
              async read() { control.reads++; if (control.fail) throw Error('read failed');
                return JSON.stringify({ schema: 1, appId: 'app', deviceId: 'watch-' + 'a'.repeat(32) }); }
            }
            export class NativeCompanionClient {
              constructor(context, app, local) { control.created++; this.state = { app, local }; this.outbox = {}; this.inbox = {}; this.incomingFiles = {}; }
              appId() { return this.state.app; } localId() { return this.state.local; }
            }
            export async function openCompanionPairings(context, app, local) {
              control.pairingOpens++; control.pairingIdentity = [app, local];
              if (control.pairingFail) throw Error('pairing lease failed');
              return { app, local };
            }`
            : args.path === 'libpodjs_harmony.so' ? `export const hasCapability = value => control.enabled.has(value);`
            : args.path === '@kit.AbilityKit' ? `export const bundleManager = { BundleFlag: { GET_BUNDLE_INFO_DEFAULT: 0 }, async getBundleInfoForSelf() { return { name: 'app' }; } };`
            : `export default {};`;
          return { contents: prelude + contents, loader: 'js' };
        });
        builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
      } }]
    });
    if (!build.success) throw new Error(build.logs.map(String).join('\n'));
    const module = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
    const context = { filesDir: '/unused-owner-test' };
    await expect(module.nativeInstalledSyncState(context)).rejects.toThrow('unavailable');
    await expect(module.nativeInstalledSyncPairings(context)).rejects.toThrow('unavailable');
    expect(control.pairingOpens).toBe(0);
    expect(control.reads).toBe(0); expect(control.created).toBe(0);
    control.enabled.add('companion.sync.state');
    await expect(module.nativeInstalledSyncState(context)).rejects.toThrow('read failed');
    control.fail = false;
    control.enabled.add('companion.sync.message'); control.enabled.add('companion.sync.file');
    const [state, inbox, outbox, incoming, client] = await Promise.all([
      module.nativeInstalledSyncState(context), module.nativeInstalledSyncInbox(context),
      module.nativeInstalledSyncOutbox(context), module.nativeInstalledSyncIncoming(context), module.nativeInstalledSyncFileClient(context)
    ]);
    expect(control.created).toBe(1); expect(control.reads).toBe(2);
    expect(state).toBe(client.state); expect(inbox).toBe(client.inbox);
    expect(outbox).toBe(client.outbox); expect(incoming).toBe(client.incomingFiles);
    expect(control.pairingOpens).toBe(0);
    await expect(module.nativeInstalledSyncPairings(context)).rejects.toThrow('pairing lease failed');
    control.pairingFail = false;
    const pairings = await Promise.all([module.nativeInstalledSyncPairings(context), module.nativeInstalledSyncPairings(context)]);
    expect(pairings[0]).toBe(pairings[1]); expect(control.pairingOpens).toBe(2);
    expect(control.pairingIdentity).toEqual(['app', 'watch-' + 'a'.repeat(32)]);
    await expect(module.nativeInstalledSyncPairings({ filesDir: '/other' })).rejects.toThrow('context mismatch');
    expect(control.pairingOpens).toBe(2);
    await expect(module.nativeInstalledSyncFileClient({ filesDir: '/other' })).rejects.toThrow('context mismatch');
    control.enabled.delete('companion.sync.file');
    await expect(module.nativeInstalledSyncFileClient(context)).rejects.toThrow('unavailable');
    expect(await module.nativeInstalledSyncState(context)).toBe(state);
    control.enabled.clear();
    await expect(module.nativeInstalledSyncPairings(context)).rejects.toThrow('unavailable');
    expect(control.pairingOpens).toBe(2);
  } finally { delete (globalThis as any).__installedSyncOwnerTest; }
});
