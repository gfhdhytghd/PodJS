import { CompanionMessageStore } from './CompanionMessageOutbox';
import { CompanionFileManifest, validateFileManifest } from './CompanionFileWire';

export class CompanionOutgoingTransfer {
  progressKnown: boolean = false;
  acknowledgedChunks: number[] = [];
  constructor(readonly peer: string, readonly manifest: CompanionFileManifest, readonly phase: string) {
    if (phase === 'complete') { this.progressKnown = true; this.acknowledgedChunks = manifest.chunk_hashes.map((_, index) => index); }
  }
}
class TransferSnapshot {
  kind: string = 'outgoing-transfers';
  schema: number = 2;
  app: string = '';
  local: string = '';
  records: CompanionOutgoingTransfer[] = [];
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid transfer identity');
}
function copy(manifest: CompanionFileManifest): CompanionFileManifest {
  const result = new CompanionFileManifest();
  result.transfer_id = manifest.transfer_id; result.size = manifest.size;
  result.sha256 = manifest.sha256; result.mime = manifest.mime; result.chunk_hashes = manifest.chunk_hashes.slice();
  return result;
}
function detached(record: CompanionOutgoingTransfer): CompanionOutgoingTransfer {
  const value = new CompanionOutgoingTransfer(record.peer, copy(record.manifest), record.phase);
  value.progressKnown = record.progressKnown; value.acknowledgedChunks = record.acknowledgedChunks.slice(); return value;
}
/** Dedicated durable CAS store, independent of the transient wire request queue.
 * Register only after immutable source import completes, before enqueueing an
 * offer. Retain terminal identities; never evict or silently reuse a transfer ID.
 * This records host intent/observations, not proof of peer file delivery. */
export class CompanionOutgoingTransfers {
  private tail: Promise<void> = Promise.resolve();
  constructor(private app: string, private local: string, private store: CompanionMessageStore) {
    identity(app); identity(local);
  }
  private run<T>(work: () => Promise<T>): Promise<T> {
    const result = this.tail.then(work); this.tail = result.then(() => {}, () => {}); return result;
  }
  private load(raw: string | null): TransferSnapshot {
    if (raw === null) { const value = new TransferSnapshot(); value.app = this.app; value.local = this.local; return value; }
    if (raw.length > 4194304) throw new Error('transfer snapshot too large');
    const value = JSON.parse(raw) as TransferSnapshot;
    if (!value || value.kind !== 'outgoing-transfers' || ![1, 2].includes(value.schema) || value.app !== this.app || value.local !== this.local ||
      !Array.isArray(value.records) || value.records.length > 128) throw new Error('invalid transfer snapshot');
    const ids: string[] = [];
    for (const record of value.records) {
      if (!record) throw new Error('invalid transfer record');
      identity(record.peer); validateFileManifest(record.manifest);
      if (record.peer === this.local || ids.includes(record.manifest.transfer_id) ||
        !['queued', 'cancel_requested', 'complete', 'cancelled'].includes(record.phase)) throw new Error('invalid transfer record');
      if (value.schema === 1) {
        record.progressKnown = record.phase === 'complete';
        record.acknowledgedChunks = record.progressKnown ? record.manifest.chunk_hashes.map((_, index) => index) : [];
      }
      if (typeof record.progressKnown !== 'boolean' || !Array.isArray(record.acknowledgedChunks) ||
        record.acknowledgedChunks.some((index: number, position: number) => !Number.isInteger(index) || index < 0 ||
          index >= record.manifest.chunk_hashes.length || (position > 0 && index <= record.acknowledgedChunks[position - 1])) ||
        (!record.progressKnown && record.acknowledgedChunks.length !== 0) ||
        (record.phase === 'complete' && (!record.progressKnown || record.acknowledgedChunks.length !== record.manifest.chunk_hashes.length))) throw new Error('invalid transfer progress');
      ids.push(record.manifest.transfer_id);
    }
    return value;
  }
  private async save(raw: string | null, snapshot: TransferSnapshot, cancelled: () => boolean): Promise<void> {
    if (cancelled()) throw new Error('transfer operation cancelled');
    snapshot.schema = 2;
    const desired = JSON.stringify(snapshot);
    if (desired.length > 4194304) throw new Error('transfer snapshot too large');
    if (!await this.store.compareExchange(raw, desired)) throw new Error('transfer storage conflict');
  }
  list(): Promise<CompanionOutgoingTransfer[]> {
    return this.run(async () => this.load(await this.store.read()).records.map(detached));
  }
  register(peer: string, manifest: CompanionFileManifest, cancelled: () => boolean = () => false): Promise<CompanionOutgoingTransfer> {
    identity(peer); validateFileManifest(manifest);
    if (peer === this.local) throw new Error('invalid transfer peer');
    const stable = copy(manifest);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = this.load(raw);
      if (cancelled()) throw new Error('transfer operation cancelled');
      const existing = snapshot.records.find((record: CompanionOutgoingTransfer) => record.manifest.transfer_id === stable.transfer_id);
      if (existing !== undefined) {
        if (existing.peer !== peer || JSON.stringify(copy(existing.manifest)) !== JSON.stringify(stable)) throw new Error('transfer identity conflict');
        return detached(existing);
      }
      if (snapshot.records.length >= 128) throw new Error('transfer registry full');
      const record = new CompanionOutgoingTransfer(peer, stable, 'queued'); snapshot.records.push(record);
      await this.save(raw, snapshot, cancelled); return detached(record);
    });
  }
  /** Host-only observations from an authenticated, already durable wire reply.
   * Persist before consuming that reply; repeated snapshots/chunk ACKs are safe. */
  observeMissing(peer: string, id: string, missing: number[], cancelled: () => boolean = () => false): Promise<void> {
    if (!Array.isArray(missing)) throw new Error('invalid missing progress');
    return this.progress(peer, id, missing.slice(), null, cancelled);
  }
  observeChunk(peer: string, id: string, index: number, cancelled: () => boolean = () => false): Promise<void> {
    return this.progress(peer, id, null, index, cancelled);
  }
  private progress(peer: string, id: string, missing: number[] | null, chunk: number | null, cancelled: () => boolean): Promise<void> {
    identity(peer); identity(id);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = this.load(raw);
      if (cancelled()) throw new Error('transfer operation cancelled');
      const record = snapshot.records.find((item: CompanionOutgoingTransfer) => item.peer === peer && item.manifest.transfer_id === id);
      if (record === undefined) throw new Error('unknown transfer');
      const count = record.manifest.chunk_hashes.length;
      if (missing !== null && (missing.length > count || missing.some((index: number, position: number) => !Number.isInteger(index) || index < 0 || index >= count ||
        (position > 0 && index <= missing[position - 1])))) throw new Error('invalid missing progress');
      if (chunk !== null && (!Number.isInteger(chunk) || chunk < 0 || chunk >= count)) throw new Error('invalid chunk progress');
      if (record.phase === 'complete' || record.phase === 'cancelled') throw new Error('terminal transfer progress');
      const known = record.progressKnown, before = JSON.stringify(record.acknowledgedChunks);
      if (missing !== null) {
        record.progressKnown = true;
        record.acknowledgedChunks = record.manifest.chunk_hashes.map((_, index) => index).filter((index: number) => !missing.includes(index));
      } else if (record.progressKnown && chunk !== null && !record.acknowledgedChunks.includes(chunk)) {
        record.acknowledgedChunks.push(chunk); record.acknowledgedChunks.sort((left: number, right: number) => left - right);
      }
      if (known === record.progressKnown && before === JSON.stringify(record.acknowledgedChunks)) return;
      await this.save(raw, snapshot, cancelled);
    });
  }
  /** Host-only: complete/cancelled must come from a validated durable receipt.
   * Cancellation intent never overwrites a terminal observation. */
  transition(peer: string, transferId: string, phase: string, cancelled: () => boolean = () => false): Promise<CompanionOutgoingTransfer> {
    identity(peer); identity(transferId);
    if (!['cancel_requested', 'complete', 'cancelled'].includes(phase)) throw new Error('invalid transfer transition');
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = this.load(raw);
      if (cancelled()) throw new Error('transfer operation cancelled');
      const index = snapshot.records.findIndex((record: CompanionOutgoingTransfer) => record.peer === peer && record.manifest.transfer_id === transferId);
      if (index < 0) throw new Error('unknown transfer');
      const old = snapshot.records[index];
      if (old.phase === phase) return detached(old);
      if (old.phase === 'complete' || old.phase === 'cancelled') throw new Error('transfer already terminal');
      const next = new CompanionOutgoingTransfer(peer, old.manifest, phase); snapshot.records[index] = next;
      if (phase !== 'complete') { next.progressKnown = old.progressKnown; next.acknowledgedChunks = old.acknowledgedChunks.slice(); }
      await this.save(raw, snapshot, cancelled); return detached(next);
    });
  }
}
