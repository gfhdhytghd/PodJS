import { expect, test } from "bun:test";
import { deflateRawSync, inflateRawSync } from "node:zlib";
import { pack, unpack } from "../vendor/pocketjs/framework/compiler/pak.ts";
import { optimizePakFontSeeds } from "../packages/cli/src/pak-font-seeds.ts";

function fixture() {
  const raw = Buffer.from(Array.from({length: 4096}, (_, i) => (i * i + i % 31) & 255));
  const seed = deflateRawSync(raw, {level: 1});
  const font = Buffer.alloc(64 + 16 + 8 + seed.length);
  font.writeUInt32LE(0x41464344); font.writeUInt16LE(6, 4); font.writeUInt16LE(1, 6);
  font[8] = 64; font[9] = 64; font[14] = 1; font.writeUInt32LE(1, 56);
  font.writeUInt16LE(1, 82); font.writeUInt32LE(seed.length, 84); font.set(seed, 88);
  return {raw, font, pak: pack([{key:'ui:font.1', dtype:1, data:font}, {key:'ui:styles', dtype:1, data:new Uint8Array([4,5,6])}])};
}
test('recompression preserves metadata, raw glyph coverage, unrelated entries and is idempotent', () => {
  const {pak, raw, font} = fixture();
  const optimized = optimizePakFontSeeds(pak);
  expect(optimized.length).toBeLessThan(pak.length);
  const blobs = unpack(optimized), output = Buffer.from(blobs[0].data);
  expect(output.subarray(0, 84)).toEqual(font.subarray(0, 84));
  expect(inflateRawSync(output.subarray(88))).toEqual(raw);
  expect(blobs[1].data).toEqual(new Uint8Array([4,5,6]));
  expect(optimizePakFontSeeds(optimized)).toEqual(optimized);
});
test('rejects damaged directory and truncated seed payload', () => {
  const {pak, font} = fixture();
  const damaged = pak.slice(); damaged[24] ^= 1;
  expect(() => optimizePakFontSeeds(damaged)).toThrow('declared file length');
  font.writeUInt32LE(99999, 84);
  expect(() => optimizePakFontSeeds(pack([{key:'ui:font.1', dtype:1, data:font}]))).toThrow('Invalid v6 font seed payload');
});

test('Buffer input and byte-offset views retain independent record headers and do not mutate the source', () => {
  const {pak, raw} = fixture();
  for (const input of [Buffer.from(pak), Buffer.concat([Buffer.alloc(7),Buffer.from(pak)]).subarray(7)]) {
    const snapshot = Buffer.from(input);
    const result = optimizePakFontSeeds(input);
    const data = Buffer.from(unpack(result)[0].data);
    expect(data.readUInt32LE(84)).toBe(data.length - 88);
    expect(inflateRawSync(data.subarray(88))).toEqual(raw);
    expect(input).toEqual(snapshot);
  }
});
