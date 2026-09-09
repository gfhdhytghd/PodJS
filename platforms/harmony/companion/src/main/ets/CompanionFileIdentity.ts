import { CompanionIncomingFiles } from './CompanionIncomingFiles';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';
import { CompanionFileRequests } from './CompanionFileRequests';

export class CompanionFileIdentity {
  constructor(readonly peer: string, readonly direction: string) {}
}
/** Read-only cross-journal resolution. Terminal records still reserve identity.
 * It grants no consent and is not an atomic lock spanning the three journals. */
export async function resolveCompanionFileIdentity(transferId: string, incoming: CompanionIncomingFiles,
  outgoing: CompanionOutgoingTransfers, requests: CompanionFileRequests): Promise<CompanionFileIdentity | null> {
  if (typeof transferId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(transferId)) throw new Error('invalid file identity');
  const received = (await incoming.listLocal()).filter(record => record.manifest.transfer_id === transferId);
  const sent = (await outgoing.list()).filter(record => record.manifest.transfer_id === transferId);
  const wire = await requests.recordsForTransfer(transferId);
  if (received.length + sent.length > 1) throw new Error('ambiguous file identity');
  if (received.length === 1) {
    if (wire.length !== 0) throw new Error('ambiguous file identity');
    return new CompanionFileIdentity(received[0].peer, 'incoming');
  }
  if (sent.length === 1) {
    if (wire.some(record => record.peer !== sent[0].peer)) throw new Error('ambiguous file identity');
    return new CompanionFileIdentity(sent[0].peer, 'outgoing');
  }
  // Legacy or interrupted host work without durable registration cannot safely
  // be treated as a new/unused ID just because no incoming offer exists.
  if (wire.length !== 0) throw new Error('unregistered file identity');
  return null;
}
