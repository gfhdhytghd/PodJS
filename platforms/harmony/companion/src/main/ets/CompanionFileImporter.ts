import { CompanionFileManifest, validateFileManifest } from './CompanionFileWire';
import { CompanionFileSource } from './CompanionFileSender';
import { CompanionOutgoingFiles } from './CompanionOutgoingFiles';
import { CompanionMessageDigest } from './CompanionMessageOutbox';

export interface CompanionStreamingDigest {
  update(bytes: Uint8Array): Promise<void>;
  finish(): Promise<Uint8Array>;
}
export interface CompanionImportCrypto extends CompanionMessageDigest {
  streamingSha256(): CompanionStreamingDigest;
}
function hex(bytes: Uint8Array): string {
  if (bytes.length !== 32) throw new Error('invalid import digest');
  let result = ''; for (const byte of bytes) result += byte.toString(16).padStart(2, '0'); return result;
}
/** Two bounded passes: compute immutable manifest, then stage only missing
 * chunks. Backend verifies the second pass against the first. Source must allow
 * indexed rereads; cancellation retains staged data for explicit resume/remove. */
export async function importCompanionFile(files: CompanionOutgoingFiles, source: CompanionFileSource,
  crypto: CompanionImportCrypto, transferId: string, size: number, mime: string,
  cancelled: () => boolean = () => false, fresh: boolean = false, peer: string = ''): Promise<CompanionFileManifest> {
  const manifest = new CompanionFileManifest(); manifest.transfer_id = transferId; manifest.size = size; manifest.mime = mime;
  // Validate all user metadata before touching the source or allocating storage.
  if (!Number.isSafeInteger(size) || size < 0 || size > 16777216) throw new Error('invalid import size');
  manifest.sha256 = '0'.repeat(64); manifest.chunk_hashes = Array(Math.ceil(size / 65536)).fill(manifest.sha256);
  validateFileManifest(manifest);
  const check = (): void => { if (cancelled()) throw new Error('file import cancelled'); };
  const read = async (index: number): Promise<Uint8Array> => {
    check(); const bytes = (await source.readChunk(index)).slice(); check();
    if (bytes.length !== Math.min(65536, size - index * 65536)) throw new Error('import source size changed'); return bytes;
  };
  check(); const whole = crypto.streamingSha256();
  for (let index = 0; index < manifest.chunk_hashes.length; index++) {
    const bytes = await read(index); await whole.update(bytes);
    manifest.chunk_hashes[index] = hex(await crypto.sha256(bytes)); check();
  }
  manifest.sha256 = hex(await whole.finish()); check();
  if (fresh) {
    await files.importFresh(manifest, async (put: (index: number, bytes: Uint8Array) => Promise<void>) => {
      for (let index = 0; index < manifest.chunk_hashes.length; index++) {
        const bytes = await read(index);
        if (hex(await crypto.sha256(bytes)) !== manifest.chunk_hashes[index]) throw new Error('import source content changed');
        check(); await put(index, bytes);
      }
    }, cancelled, peer);
    check(); return manifest;
  }
  await files.prepare(manifest); check();
  for (const index of await files.missing(transferId)) {
    if (!Number.isInteger(index) || index < 0 || index >= manifest.chunk_hashes.length) throw new Error('invalid import missing index');
    const bytes = await read(index);
    if (hex(await crypto.sha256(bytes)) !== manifest.chunk_hashes[index]) throw new Error('import source content changed');
    check(); await files.writeChunk(transferId, index, bytes);
  }
  check(); await files.finish(transferId); check(); return manifest;
}
