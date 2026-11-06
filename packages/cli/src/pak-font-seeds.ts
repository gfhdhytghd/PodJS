import { deflateRawSync, inflateRawSync } from "node:zlib";
import { pack, unpack } from "../../../vendor/pocketjs/framework/compiler/pak.ts";
import { buildPakIntegrity } from "./pak-integrity.ts";

/** Recompress v6 seed cells without changing their cmap, codec or decoded bytes.
 * Each cell still inflates independently in the existing bounded glyph cache.
 * No runtime change, whole-entry decompression, or persistent cache is needed.
 */
export function optimizePakFontSeeds(pak: Uint8Array): Uint8Array {
  buildPakIntegrity(pak); // Never canonicalize corrupt input into a trusted pack.
  let changed = false;
  const blobs = unpack(pak).map(blob => {
    const bytes = blob.data;
    if (!blob.key.startsWith("ui:font.") || bytes.length < 64) return blob;
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (view.getUint32(0, true) !== 0x41464344 || view.getUint16(4, true) !== 6) return blob;
    const count = view.getUint16(6, true);
    const start = 64 + count * 16;
    const cellBytes = bytes[8] * bytes[9] * bytes[14] ** 2;
    if (start > bytes.length || cellBytes < 1 || bytes[14] > 4) throw Error("Invalid v6 font seed header");
    const records: Uint8Array[] = [bytes.subarray(0, start)];
    let cursor = start;
    for (let i = 0; i < view.getUint32(56, true); i++) {
      if (cursor + 8 > bytes.length) throw Error("Truncated v6 font seed record");
      const header = Uint8Array.from(bytes.subarray(cursor, cursor + 8));
      const codec = view.getUint16(cursor + 2, true);
      const length = view.getUint32(cursor + 4, true);
      cursor += 8;
      if (cursor + length > bytes.length || codec > 1) throw Error("Invalid v6 font seed payload");
      let data = bytes.subarray(cursor, cursor + length);
      cursor += length;
      if (codec === 1) {
        const decoded = inflateRawSync(data, { maxOutputLength: cellBytes });
        if (decoded.length !== cellBytes) throw Error("Invalid v6 font seed cell size");
        const compressed = deflateRawSync(decoded, { level: 9 });
        if (compressed.length < data.length) {
          data = compressed;
          new DataView(header.buffer).setUint32(4, data.length, true);
          changed = true;
        }
      } else if (data.length !== cellBytes) throw Error("Invalid raw v6 font seed cell size");
      records.push(header, data);
    }
    if (cursor !== bytes.length) throw Error("Trailing v6 font seed bytes");
    return { ...blob, data: Buffer.concat(records) };
  });
  if (!changed) return pak;
  const optimized = pack(blobs);
  buildPakIntegrity(optimized);
  return optimized.length < pak.length ? optimized : pak;
}
