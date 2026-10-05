import { createHash } from "node:crypto";
import {
  PAK_ALIGN,
  PAK_ENTRY_SIZE,
  PAK_FNV1A_OFFSET_BASIS,
  PAK_FNV1A_PRIME,
  PAK_HEADER_SIZE,
  PAK_MAGIC,
  PAK_VERSION,
} from "../../../vendor/pocketjs/contracts/spec/spec.ts";

export const PAK_INTEGRITY_FORMAT = "sha256-per-entry-v1";

export interface PakIntegrityEntry {
  key: string;
  offset: number;
  bytes: number;
  sha256: string;
}

export interface PakIntegrityManifest {
  format: typeof PAK_INTEGRITY_FORMAT;
  directoryBytes: number;
  directorySha256: string;
  entries: PakIntegrityEntry[];
}

const align = (value: number): number =>
  (value + (PAK_ALIGN - 1)) & ~(PAK_ALIGN - 1);

const sha256 = (bytes: Uint8Array): string =>
  createHash("sha256").update(bytes).digest("hex");

const keyHash = (key: string): number => {
  let hash = PAK_FNV1A_OFFSET_BASIS;
  for (let index = 0; index < key.length; index++) {
    hash ^= key.charCodeAt(index);
    hash = Math.imul(hash, PAK_FNV1A_PRIME);
  }
  return hash >>> 0;
};

const fail = (message: string): never => {
  throw new Error(`pak integrity: ${message}`);
};

/**
 * Decode exactly the canonical v1 layout emitted by PocketJS' pack().
 * Runtime validation mirrors these rules, so the integrity manifest cannot
 * bless overlapping, aliased, omitted, reordered, or out-of-range entries.
 */
export function buildPakIntegrity(pak: Uint8Array): PakIntegrityManifest {
  if (pak.byteLength < PAK_HEADER_SIZE) fail("truncated header");
  const view = new DataView(pak.buffer, pak.byteOffset, pak.byteLength);
  const u16 = (offset: number): number => view.getUint16(offset, true);
  const u32 = (offset: number): number => view.getUint32(offset, true);
  if (u32(0) !== PAK_MAGIC || u16(4) !== PAK_VERSION) fail("magic or version mismatch");
  if (u16(6) !== 0 || u32(28) !== 0) fail("reserved header bytes are nonzero");
  const count = u32(8);
  const directoryOffset = u32(12);
  const namesOffset = u32(16);
  const directoryBytes = u32(20);
  if (u32(24) !== pak.byteLength) fail("declared file length mismatch");
  if (directoryOffset !== PAK_HEADER_SIZE) fail("noncanonical directory offset");
  const expectedNamesOffset = directoryOffset + count * PAK_ENTRY_SIZE;
  if (!Number.isSafeInteger(expectedNamesOffset) || namesOffset !== expectedNamesOffset) {
    fail("noncanonical names offset");
  }
  if (directoryBytes !== align(directoryBytes) || directoryBytes < namesOffset || directoryBytes > pak.byteLength) {
    fail("invalid directory byte range");
  }

  const decoder = new TextDecoder("utf-8", { fatal: true });
  const entries: PakIntegrityEntry[] = [];
  let nameCursor = 0;
  let blobCursor = directoryBytes;
  let previousKey: string | undefined;
  for (let index = 0; index < count; index++) {
    const record = directoryOffset + index * PAK_ENTRY_SIZE;
    if (record + PAK_ENTRY_SIZE > namesOffset) fail("truncated directory record");
    const storedKeyHash = u32(record);
    const offset = u32(record + 4);
    const bytes = u32(record + 8);
    const nameOffset = u32(record + 12);
    const nameBytes = u16(record + 16);
    if (pak[record + 19] !== 0 || u32(record + 20) !== 0) fail("reserved entry bytes are nonzero");
    if (nameOffset !== nameCursor) fail("noncanonical name ordering");
    const nameStart = namesOffset + nameOffset;
    const nameEnd = nameStart + nameBytes;
    if (nameEnd > directoryBytes) fail("name range is out of bounds");
    let key: string;
    try {
      key = decoder.decode(pak.subarray(nameStart, nameEnd));
    } catch {
      fail("entry name is not UTF-8");
    }
    if (storedKeyHash !== keyHash(key)) fail("entry key hash mismatch");
    if (!key || previousKey !== undefined && key <= previousKey) fail("keys are empty, duplicated, or unsorted");
    if (offset !== blobCursor || offset !== align(offset)) fail("noncanonical blob offset");
    const end = offset + bytes;
    if (!Number.isSafeInteger(end) || end > pak.byteLength) fail("blob range is out of bounds");
    const paddedEnd = align(end);
    if (paddedEnd > pak.byteLength) fail("blob padding is truncated");
    for (let cursor = end; cursor < paddedEnd; cursor++) {
      if (pak[cursor] !== 0) fail("blob padding is nonzero");
    }
    entries.push({ key, offset, bytes, sha256: sha256(pak.subarray(offset, end)) });
    previousKey = key;
    nameCursor += nameBytes;
    blobCursor = paddedEnd;
  }
  if (namesOffset + nameCursor > directoryBytes) fail("names overlap blob data");
  for (let cursor = namesOffset + nameCursor; cursor < directoryBytes; cursor++) {
    if (pak[cursor] !== 0) fail("directory padding is nonzero");
  }
  if (blobCursor !== pak.byteLength) fail("unclaimed trailing bytes");
  return {
    format: PAK_INTEGRITY_FORMAT,
    directoryBytes,
    directorySha256: sha256(pak.subarray(0, directoryBytes)),
    entries,
  };
}
