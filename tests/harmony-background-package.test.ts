import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { BackgroundPackage } from '../platforms/harmony/entry/src/main/ets/BackgroundPackage';
const encode = (value: string) => new TextEncoder().encode(value);
const hash = (value: Uint8Array) => createHash('sha256').update(value).digest('hex');
function fixture() {
  let source: Uint8Array = encode("globalThis.backgroundHandler = () => 'success'; // 后台😀");
  const file = 'background/' + 'a'.repeat(64) + '.js';
  const manifest = { schema: 1, target: 'harmonyos-watch', background: {
    refresh: { file, bytes: source.length, sha256: hash(source) }
  }, backgroundServices: ['kv.get'] };
  const paths: string[] = [];
  const assets = {
    async read(path: string) { paths.push(path); if (path === 'pod.manifest.json') return encode(JSON.stringify(manifest));
      if (path !== file) throw Error('unexpected path'); return source; },
    decode(bytes: Uint8Array) { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); },
    async sha256(bytes: Uint8Array) { return hash(bytes); }
  };
  return { manifest, assets, paths, replace(value: Uint8Array) { source = value; } };
}
test('installed source is identity bound, strictly verified and result grants are isolated', async () => {
  const f = fixture(); const p = await BackgroundPackage.installed('dev.podjs.watch', f.assets);
  const approved = await p.resolve('refresh'); expect(approved.source).toContain('后台😀');
  expect(approved.appId).toBe('dev.podjs.watch'); approved.grants.push('kv.set');
  expect((await p.resolve('refresh')).grants).toEqual(['kv.get']);
  expect(p.permits(approved.sha256, ['kv.set'])).toBe(false);
  expect(p.permits(approved.sha256, ['kv.get'])).toBe(true);
});
test('unknown handlers and untrusted paths cannot read source', async () => {
  const f = fixture(); const p = await BackgroundPackage.installed('app', f.assets);
  await expect(p.resolve('toString')).rejects.toThrow('Undeclared');
  await expect(p.resolve('../main.js')).rejects.toThrow('Invalid');
  expect(f.paths).toEqual(['pod.manifest.json']);
  f.manifest.background.refresh.file = '../main.js';
  await expect(BackgroundPackage.installed('app', f.assets)).rejects.toThrow('Invalid');
});
test('modified source and malformed UTF-8 fail before execution', async () => {
  const f = fixture(); const p = await BackgroundPackage.installed('app', f.assets);
  f.replace(encode('tampered')); await expect(p.resolve('refresh')).rejects.toThrow('integrity');
  const invalid = new Uint8Array([0xc0, 0xaf]); f.replace(invalid);
  f.manifest.background.refresh.bytes = 2; f.manifest.background.refresh.sha256 = hash(invalid);
  const current = await BackgroundPackage.installed('app', f.assets);
  await expect(current.resolve('refresh')).rejects.toThrow();
});
test('installed update revokes old hashes and grants', async () => {
  const f = fixture(); const old = await BackgroundPackage.installed('app', f.assets);
  const approved = await old.resolve('refresh');
  f.manifest.backgroundServices = []; f.manifest.background.refresh.sha256 = '0'.repeat(64);
  const current = await BackgroundPackage.installed('app', f.assets);
  expect(current.permits(approved.sha256, approved.grants)).toBe(false);
  f.manifest.target = 'android-watch';
  await expect(BackgroundPackage.installed('app', f.assets)).rejects.toThrow('target');
});
test('invalid and duplicate method grants fail closed', async () => {
  const f = fixture();
  for (const grants of [['kv.get', 'kv.get'], ['http.request'], ['notification.schedule']]) {
    f.manifest.backgroundServices = grants;
    await expect(BackgroundPackage.installed('app', f.assets)).rejects.toThrow('grant');
  }
});
