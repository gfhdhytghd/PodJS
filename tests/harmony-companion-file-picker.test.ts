import { test, expect } from 'bun:test';
import { resolve } from 'node:path';

test('actual picker adapter retries short reads, seeks for rereads and closes on every exit', async () => {
  const data = Uint8Array.from({ length: 65539 }, (_, i) => i % 251);
  let position = 0, closed = 0, opens = 0, selections: string[] = ['file://selected'];
  let size = data.length, deny = false, truncate = false;
  const fs = {
    OpenMode: { READ_ONLY: 0 }, WhenceType: { SEEK_SET: 0 },
    async open(uri: string, mode: number) { expect(uri).toBe('file://selected'); expect(mode).toBe(0); if (deny) throw Error('permission denied'); opens++; return { fd: 5 }; },
    async stat() { return { size, isFile: () => true }; },
    lseek(fd: number, offset: number, whence: number) { expect(fd).toBe(5); expect(whence).toBe(0); position = offset; return offset; },
    async read(fd: number, buffer: ArrayBuffer) {
      if (truncate) return 0;
      const count = Math.min(997, buffer.byteLength, data.length - position);
      new Uint8Array(buffer).set(data.slice(position, position + count)); position += count; return count;
    },
    async close(file: { fd: number }) { expect(file.fd).toBe(5); closed++; }
  };
  (globalThis as any).__podjsPickerTest = { fs, picker: { DocumentViewPicker: class {
    async select(options: { maxSelectNumber: number }) { expect(options.maxSelectNumber).toBe(1); return selections; }
  } } };
  const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionFilePicker.ets')], target: 'bun',
    plugins: [{ name: 'picker-os', setup(builder) {
      builder.onResolve({ filter: /^@ohos\.file\.(fs|picker)$/ }, args => ({ path: args.path.endsWith('.fs') ? 'fs' : 'picker', namespace: 'picker-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'picker-os' }, args => ({ contents: `export default globalThis.__podjsPickerTest.${args.path};`, loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const { pickCompanionFile } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  const client = { async importFile(id: string, actualSize: number, mime: string, source: any) {
    expect(actualSize).toBe(data.length); expect(mime).toBe('application/octet-stream');
    expect(await source.readChunk(0)).toEqual(data.slice(0, 65536));
    expect(await source.readChunk(1)).toEqual(data.slice(65536));
    expect(await source.readChunk(0)).toEqual(data.slice(0, 65536)); return { transfer_id: id };
  } };
  expect((await pickCompanionFile({}, client, 'selected')).transfer_id).toBe('selected'); expect(closed).toBe(1);
  truncate = true; await expect(pickCompanionFile({}, client, 'truncated')).rejects.toThrow('truncated'); expect(closed).toBe(2); truncate = false;
  size = 16777217; await expect(pickCompanionFile({}, client, 'large')).rejects.toThrow('unsupported'); expect(closed).toBe(3); size = data.length;
  selections = []; expect(await pickCompanionFile({}, client, 'empty')).toBeNull(); expect(opens).toBe(3);
  selections = ['file://selected']; deny = true;
  await expect(pickCompanionFile({}, client, 'denied')).rejects.toThrow('permission denied'); expect(closed).toBe(3); deny = false;
  await expect(pickCompanionFile({}, client, 'cancelled', () => true)).rejects.toThrow('cancelled'); expect(opens).toBe(3);
  const failure = { async importFile() { throw Error('import failed'); } };
  await expect(pickCompanionFile({}, failure, 'failure')).rejects.toThrow('import failed'); expect(closed).toBe(4);
});
