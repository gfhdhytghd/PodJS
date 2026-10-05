import { describe, expect, test } from "bun:test";
import { pack } from "../vendor/pocketjs/framework/compiler/pak.ts";
import { PAK_DTYPE } from "../vendor/pocketjs/contracts/spec/spec.ts";
import { buildPakIntegrity, PAK_INTEGRITY_FORMAT } from "../packages/cli/src/pak-integrity.ts";

const fixture = () => pack([
  { key: "ui:font.7", dtype: PAK_DTYPE.u8, data: new Uint8Array([1, 2, 3]) },
  { key: "ui:img.logo", dtype: PAK_DTYPE.u8, data: new Uint8Array([5, 7, 11, 13]) },
  { key: "ui:styles", dtype: PAK_DTYPE.u8, data: new Uint8Array([17, 19]) },
]);

describe("pak entry integrity manifest", () => {
  test("binds the exact canonical directory and every sorted entry", () => {
    const pak = fixture();
    const receipt = buildPakIntegrity(pak);
    expect(receipt.format).toBe(PAK_INTEGRITY_FORMAT);
    expect(receipt.directoryBytes).toBe(144);
    expect(receipt.directorySha256).toHaveLength(64);
    expect(receipt.entries.map(entry => entry.key)).toEqual(["ui:font.7", "ui:img.logo", "ui:styles"]);
    expect(receipt.entries.map(entry => [entry.offset, entry.bytes])).toEqual([[144, 3], [160, 4], [176, 2]]);
    expect(new Set(receipt.entries.map(entry => entry.sha256)).size).toBe(3);
  });

  test("entry mutation changes only that entry digest while directory mutation is rejected", () => {
    const pak = fixture();
    const first = buildPakIntegrity(pak);
    const changed = pak.slice();
    changed[first.entries[1]!.offset] ^= 0xff;
    const second = buildPakIntegrity(changed);
    expect(second.directorySha256).toBe(first.directorySha256);
    expect(second.entries[0]!.sha256).toBe(first.entries[0]!.sha256);
    expect(second.entries[1]!.sha256).not.toBe(first.entries[1]!.sha256);

    const badDirectory = pak.slice();
    new DataView(badDirectory.buffer).setUint32(32 + 4, 161, true);
    expect(() => buildPakIntegrity(badDirectory)).toThrow("noncanonical blob offset");
    const badKeyHash = pak.slice();
    new DataView(badKeyHash.buffer).setUint32(32, 0, true);
    expect(() => buildPakIntegrity(badKeyHash)).toThrow("entry key hash mismatch");
  });

  test("rejects truncation, out-of-range entries, nonzero padding, and trailing bytes", () => {
    const pak = fixture();
    expect(() => buildPakIntegrity(pak.subarray(0, pak.length - 1))).toThrow("declared file length mismatch");
    const outOfRange = pak.slice();
    new DataView(outOfRange.buffer).setUint32(32 + 8, 0xffff_ffff, true);
    expect(() => buildPakIntegrity(outOfRange)).toThrow("blob range is out of bounds");
    const padding = pak.slice();
    const first = buildPakIntegrity(pak).entries[0]!;
    padding[first.offset + first.bytes] = 1;
    expect(() => buildPakIntegrity(padding)).toThrow("blob padding is nonzero");
    const trailing = new Uint8Array(pak.length + 16);
    trailing.set(pak);
    new DataView(trailing.buffer).setUint32(24, trailing.length, true);
    expect(() => buildPakIntegrity(trailing)).toThrow("unclaimed trailing bytes");
  });
});
