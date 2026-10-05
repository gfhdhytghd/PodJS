import { test, expect } from 'bun:test';
import { resolve } from 'node:path';
import { CompanionConnectionOwner } from '../platforms/harmony/companion/src/main/ets/CompanionConnectionOwner';

let moduleId = 0;
test('example identity is durable before client construction, shared concurrently and fails closed', async () => {
  let saved = '', flushFail = true, generated = 0, constructed = 0, closes = 0;
  let pairingOpens = 0, pairingFail = true;
  const pairingOwner = { list: async () => [] };
  const store = {
    async get() { return saved; },
    async put(_key: string, value: string) { saved = value; },
    async flush() { if (flushFail) throw Error('flush failed'); }
  };
  (globalThis as any).__exampleIdentity = {
    CompanionConnectionOwner,
    preferences: { async getPreferences() { return store; } },
    crypto: { createRandom() { return { async generateRandom() { generated++; return { data: new Uint8Array(16).fill(3) }; } }; } },
    NativeCompanionClient: class { constructor() { constructed++; } close() { closes++; } },
    async openCompanionPairings(_context: unknown, app: string, local: string) {
      expect(app).toBe('podjs.companion.demo'); expect(local).toBe(saved);
      pairingOpens++; if (pairingFail) throw Error('pairing unavailable'); return pairingOwner;
    }
  };
  const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion_example/src/main/ets/ExampleClient.ets')], target: 'bun',
    plugins: [{ name: 'identity-os', setup(builder) {
      builder.onResolve({ filter: /^(@ohos\.|@podjs\/companion)/ }, args => ({ path: args.path, namespace: 'identity-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'identity-os' }, args => ({ contents: args.path === '@podjs/companion'
        ? 'export const NativeCompanionClient = globalThis.__exampleIdentity.NativeCompanionClient; export const openCompanionPairings = globalThis.__exampleIdentity.openCompanionPairings; export const CompanionConnectionOwner = globalThis.__exampleIdentity.CompanionConnectionOwner;'
        : `export default globalThis.__exampleIdentity.${args.path.includes('preferences') ? 'preferences' : 'crypto'};`, loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const source = await build.outputs[0].text();
  const load = () => import('data:text/javascript;base64,' + Buffer.from(source + '\n//' + ++moduleId).toString('base64'));
  const module = await load();
  await expect(module.exampleClient({})).rejects.toThrow('flush failed');
  await expect(module.exampleClient({})).rejects.toThrow('flush failed');
  expect(constructed).toBe(0);
  flushFail = false;
  const [left, right] = await Promise.all([module.exampleClient({}), module.exampleClient({})]);
  expect(left).toBe(right); expect(constructed).toBe(1); expect(generated).toBe(1);
  module.closeExampleConnection(); expect(closes).toBe(1);
  const failedPairings = await Promise.allSettled([module.examplePairings({}), module.examplePairings({})]);
  expect(failedPairings.every(result => result.status === 'rejected')).toBe(true); expect(pairingOpens).toBe(1);
  pairingFail = false;
  const [pairedA, pairedB] = await Promise.all([module.examplePairings({}), module.examplePairings({})]);
  expect(pairedA).toBe(pairingOwner); expect(pairedB).toBe(pairedA); expect(pairingOpens).toBe(2);
  expect(await module.examplePairings({})).toBe(pairedA); expect(pairingOpens).toBe(2);
  const restarted = await load(); expect((await restarted.exampleClient({})).deviceId).toBe(left.deviceId);
  saved = 'corrupt'; const invalid = await load();
  await expect(invalid.exampleClient({})).rejects.toThrow('invalid saved'); expect(generated).toBe(1);
});
