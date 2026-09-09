import { CompanionOutgoingFiles } from './CompanionOutgoingFiles';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';
import { CompanionFileRequests } from './CompanionFileRequests';
import { CompanionFileIdentity } from './CompanionFileIdentity';

export interface CompanionOutgoingCleanupClient {
  readonly outgoingFiles: CompanionOutgoingFiles;
  readonly outgoingTransfers: CompanionOutgoingTransfers;
  readonly fileRequests: CompanionFileRequests;
  resolveFileIdentity(id: string): Promise<CompanionFileIdentity | null>;
}
/** Reclaim at most one host snapshot per foreground step. Never remove the
 * guest original, a resumable import, or any journal/transfer identity. Native
 * outgoing removal journals its intent and is restart-idempotent. */
export async function cleanupTerminalOutgoingSource(client: CompanionOutgoingCleanupClient, peer: string,
  cancelled: () => boolean = () => false): Promise<boolean> {
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(peer)) throw new Error('invalid cleanup peer');
  const check = (): void => { if (cancelled()) throw new Error('outgoing cleanup cancelled'); };
  check();
  const pending = await client.fileRequests.next(peer); check();
  const receipts = await client.fileRequests.completed(peer); check();
  if (pending !== null || receipts.length !== 0) return false;
  const records = await client.outgoingTransfers.list(); check();
  const sources = await client.outgoingFiles.list(); check();
  for (const record of records) {
    if (record.peer !== peer || !['complete', 'cancelled'].includes(record.phase)) continue;
    const source = sources.find(value => value.manifest.transfer_id === record.manifest.transfer_id);
    if (source === undefined || source.phase === 'removed') continue;
    const a = source.manifest, b = record.manifest;
    if (a.transfer_id !== b.transfer_id || a.size !== b.size || a.mime !== b.mime || a.sha256 !== b.sha256 ||
      JSON.stringify(a.chunk_hashes) !== JSON.stringify(b.chunk_hashes)) throw new Error('outgoing cleanup manifest mismatch');
    const identity = await client.resolveFileIdentity(record.manifest.transfer_id); check();
    if (identity === null || identity.direction !== 'outgoing' || identity.peer !== peer) throw new Error('outgoing cleanup identity mismatch');
    await client.outgoingFiles.remove(record.manifest.transfer_id); return true;
  }
  return false;
}
