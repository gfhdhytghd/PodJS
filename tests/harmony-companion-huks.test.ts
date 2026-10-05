import { test, expect } from 'bun:test';
import { createHmac } from 'node:crypto';
import { resolve } from 'node:path';

test('actual HUKS adapter imports privately, streams HMAC, aborts errors and never replaces keys', async () => {
  const keys = new Map<string, Uint8Array>();
  let imported: Uint8Array | undefined, aborts = 0, failUpdate = false, badOutput = false;
  let chunks: Uint8Array[] = [], activeKey: Uint8Array = new Uint8Array();
  const tags = { HUKS_TAG_ALGORITHM: 1, HUKS_TAG_PURPOSE: 2, HUKS_TAG_KEY_SIZE: 3, HUKS_TAG_DIGEST: 4 };
  function check(options: any) { expect(options.properties).toEqual([
    { tag: 1, value: 50 }, { tag: 2, value: 128 }, { tag: 3, value: 256 }, { tag: 4, value: 12 }
  ]); }
  const huks = {
    HuksTag: tags, HuksKeyAlg: { HUKS_ALG_HMAC: 50 }, HuksKeyPurpose: { HUKS_KEY_PURPOSE_MAC: 128 }, HuksKeyDigest: { HUKS_DIGEST_SHA256: 12 },
    async isKeyItemExist(alias: string) { return keys.has(alias); },
    async importKeyItem(alias: string, options: any) { check(options); imported = options.inData; keys.set(alias, options.inData.slice()); },
    async deleteKeyItem(alias: string) { keys.delete(alias); },
    async initSession(alias: string, options: any) { check(options); if (!keys.has(alias)) throw Error('missing key'); activeKey = keys.get(alias)!; chunks = []; return { handle: 42 }; },
    async updateSession(handle: number, options: any) { expect(handle).toBe(42); check(options); if (failUpdate) throw Error('update failed'); expect(options.inData.length).toBeLessThanOrEqual(65536); chunks.push(options.inData); },
    async finishSession(handle: number, options: any) { expect(handle).toBe(42); check(options); expect(options.inData.length).toBe(0); return { outData: badOutput ? new Uint8Array(1) : new Uint8Array(createHmac('sha256', activeKey).update(Buffer.concat(chunks)).digest()) }; },
    async abortSession(handle: number) { expect(handle).toBe(42); aborts++; }
  };
  (globalThis as any).__podjsHuksTest = huks;
  const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionHuks.ets')], target: 'bun',
    plugins: [{ name: 'huks-os', setup(builder) {
      builder.onResolve({ filter: /^@ohos\.security\.huks$/ }, () => ({ path: 'huks', namespace: 'huks-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'huks-os' }, () => ({ contents: 'export default globalThis.__podjsHuksTest;', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const { NativeCompanionHuks } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  const store = new NativeCompanionHuks(), id = 'ab'.repeat(32), key = new Uint8Array(32).fill(7);
  await store.importKey(id, key);
  expect(imported).toEqual(new Uint8Array(32)); expect(key[0]).toBe(7);
  await expect(store.importKey(id, key)).rejects.toThrow('already exists');
  const input = new Uint8Array(150000).fill(3), pending = store.sign(id, input); input.fill(9);
  expect(await pending).toEqual(new Uint8Array(createHmac('sha256', key).update(new Uint8Array(150000).fill(3)).digest()));
  expect(chunks.map(chunk => chunk.length)).toEqual([65536, 65536, 18928]);
  failUpdate = true; await expect(store.sign(id, key)).rejects.toThrow('update failed'); expect(aborts).toBe(1); failUpdate = false;
  badOutput = true; await expect(store.sign(id, key)).rejects.toThrow('invalid HUKS'); expect(aborts).toBe(1); badOutput = false;
  await store.remove(id); expect(await store.exists(id)).toBe(false); await store.remove(id);
  await expect(store.sign(id, key)).rejects.toThrow('missing key');
  await expect(store.importKey('other-alias', key)).rejects.toThrow('invalid pairing key ID');
  await expect(store.importKey(id, new Uint8Array(32))).rejects.toThrow('invalid pairing key');
  let release!: () => void;
  const originalImport = huks.importKeyItem;
  huks.importKeyItem = async (_alias: string, options: any) => {
    imported = options.inData; await new Promise<void>(resolve => { release = resolve; }); throw Error('import failed');
  };
  const importing = store.importKey(id, key);
  await Promise.resolve();
  await expect(new NativeCompanionHuks().importKey(id, key)).rejects.toThrow('busy');
  await expect(store.remove(id)).rejects.toThrow('busy');
  release(); await expect(importing).rejects.toThrow('import failed'); expect(imported).toEqual(new Uint8Array(32));
  huks.importKeyItem = originalImport;
  await store.importKey(id, key); expect(await store.exists(id)).toBe(true);
});
