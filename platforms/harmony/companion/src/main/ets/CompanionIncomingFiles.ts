import { CompanionFileManifest, CompanionFileRequest, validateFileManifest } from './CompanionFileWire';
import { CompanionFileReceiver } from './CompanionFilePump';
import { CompanionFileValue } from './CompanionFileReply';
import { CompanionMessageDigest } from './CompanionMessageOutbox';

/** App-private backend. exclusive must hold a cross-instance/process lock for
 * the entire callback. Metadata writes are atomic and durable. reserve/remove
 * are idempotent; chunk writes are atomic+durable. missing verifies stored bytes;
 * finish verifies all chunks AND whole SHA-256 before atomically publishing the
 * complete file. No caller-supplied filesystem paths enter this interface. */
export interface CompanionIncomingFilePort {
  exclusive<T>(work: () => Promise<T>): Promise<T>;
  readJournal(): Promise<string | null>;
  writeJournal(value: string): Promise<void>;
  reserve(peer: string, manifest: CompanionFileManifest): Promise<void>;
  remove(peer: string, manifest: CompanionFileManifest): Promise<void>;
  writeChunk(peer: string, manifest: CompanionFileManifest, index: number, bytes: Uint8Array): Promise<void>;
  missing(peer: string, manifest: CompanionFileManifest): Promise<number[]>;
  finish(peer: string, manifest: CompanionFileManifest): Promise<void>;
  readCompleteChunk(peer: string, manifest: CompanionFileManifest, index: number): Promise<Uint8Array>;
  saveComplete?(peer: string, manifest: CompanionFileManifest, path: string): Promise<void>;
}
export class CompanionIncomingFileOffer {
  peer: string = '';
  manifest: CompanionFileManifest = new CompanionFileManifest();
  phase: string = 'offered';
}
export class CompanionIncomingFileStatus extends CompanionIncomingFileOffer {
  receivedBytes: number = 0;
}
class IncomingJournal {
  schema: number = 1;
  app: string = '';
  local: string = '';
  offers: CompanionIncomingFileOffer[] = [];
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid incoming peer');
}
function manifestCopy(value: CompanionFileManifest): CompanionFileManifest {
  validateFileManifest(value); const copy = new CompanionFileManifest();
  copy.transfer_id = value.transfer_id; copy.size = value.size; copy.sha256 = value.sha256; copy.chunk_hashes = value.chunk_hashes.slice(); copy.mime = value.mime; return copy;
}
/** Durable local-consent lifecycle. No remote accept method. Pending intent is
 * saved before file allocation/removal so reopening can finish interrupted work. */
export class CompanionIncomingFiles implements CompanionFileReceiver {
  private app: string;
  private local: string;
  private port: CompanionIncomingFilePort;
  private crypto: CompanionMessageDigest;
  constructor(app: string, local: string, port: CompanionIncomingFilePort, crypto: CompanionMessageDigest) {
    identity(app); identity(local); this.app = app; this.local = local; this.port = port; this.crypto = crypto;
  }
  matchesIdentity(app: string, local: string): boolean { return app === this.app && local === this.local; }
  private async load(): Promise<IncomingJournal> {
    const raw = await this.port.readJournal();
    if (raw === null) { const journal = new IncomingJournal(); journal.app = this.app; journal.local = this.local; return journal; }
    if (raw.length > 4194304) throw new Error('incoming journal too large');
    const journal = JSON.parse(raw) as IncomingJournal;
    if (!journal || journal.schema !== 1 || journal.app !== this.app || journal.local !== this.local || !Array.isArray(journal.offers) || journal.offers.length > 128) throw new Error('invalid incoming journal');
    const keys: string[] = []; let reserved = 0;
    for (const offer of journal.offers) {
      if (!offer) throw new Error('invalid incoming offer'); identity(offer.peer); validateFileManifest(offer.manifest);
      if (offer.peer === this.local || !['offered', 'accepting', 'accepted', 'complete', 'cancelling', 'cancelled'].includes(offer.phase)) throw new Error('invalid incoming phase');
      const key = offer.peer + '/' + offer.manifest.transfer_id;
      if (keys.includes(key)) throw new Error('duplicate incoming offer'); keys.push(key);
      if (!['offered', 'cancelled'].includes(offer.phase)) reserved += offer.manifest.size;
    }
    if (reserved > 33554432) throw new Error('incoming app quota exceeded'); return journal;
  }
  private async phase(journal: IncomingJournal, offer: CompanionIncomingFileOffer, phase: string): Promise<void> {
    offer.phase = phase; await this.port.writeJournal(JSON.stringify(journal));
  }
  private find(journal: IncomingJournal, peer: string, id: string): CompanionIncomingFileOffer {
    const offer = journal.offers.find((item: CompanionIncomingFileOffer) => item.peer === peer && item.manifest.transfer_id === id);
    if (offer === undefined) throw new Error('unknown incoming transfer'); return offer;
  }
  private async recoverOne(journal: IncomingJournal, offer: CompanionIncomingFileOffer): Promise<void> {
    if (offer.phase === 'cancelling') {
      await this.port.remove(offer.peer, manifestCopy(offer.manifest)); await this.phase(journal, offer, 'cancelled');
    } else if (offer.phase === 'accepting') {
      await this.port.reserve(offer.peer, manifestCopy(offer.manifest)); await this.phase(journal, offer, 'accepted');
    }
  }
  recover(): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load();
      for (const offer of journal.offers) if (offer.phase === 'cancelling') await this.recoverOne(journal, offer);
      for (const offer of journal.offers) if (offer.phase === 'accepting') await this.recoverOne(journal, offer);
    });
  }
  pendingConsent(): Promise<CompanionIncomingFileOffer[]> {
    return this.port.exclusive(async () => {
      const journal = await this.load(); return journal.offers.filter((offer: CompanionIncomingFileOffer) => offer.phase === 'offered');
    });
  }
  /** Snapshot for local consent/history UI, including completed file manifests. */
  listLocal(): Promise<CompanionIncomingFileOffer[]> {
    return this.port.exclusive(async () => (await this.load()).offers);
  }
  /** Read-only progress from verified chunks; never grants consent or allocates.
   * A damaged completed copy is not reported complete just from its journal. */
  statusLocal(peer: string, id: string): Promise<CompanionIncomingFileStatus> {
    identity(peer);
    return this.port.exclusive(async () => {
      const offer = this.find(await this.load(), peer, id), result = new CompanionIncomingFileStatus();
      result.peer = offer.peer; result.manifest = manifestCopy(offer.manifest); result.phase = offer.phase;
      if (offer.phase === 'accepted' || offer.phase === 'complete') {
        const missing = await this.port.missing(peer, manifestCopy(offer.manifest));
        let previous = -1, absent = 0;
        for (const index of missing) {
          if (!Number.isInteger(index) || index <= previous || index >= offer.manifest.chunk_hashes.length) throw new Error('invalid backend missing chunks');
          previous = index; absent += Math.min(65536, offer.manifest.size - index * 65536);
        }
        result.receivedBytes = offer.manifest.size - absent;
        if (result.phase === 'complete' && missing.length > 0) result.phase = 'accepted';
      }
      return result;
    });
  }
  private async cancel(journal: IncomingJournal, offer: CompanionIncomingFileOffer): Promise<void> {
    if (offer.phase === 'offered') await this.phase(journal, offer, 'cancelled');
    else if (offer.phase !== 'cancelled') {
      await this.phase(journal, offer, 'cancelling'); await this.recoverOne(journal, offer);
    }
  }
  /** Reject an offer or remove an accepted/completed local file durably. */
  cancelLocal(peer: string, id: string): Promise<void> {
    identity(peer);
    return this.port.exclusive(async () => {
      const journal = await this.load(); await this.cancel(journal, this.find(journal, peer, id));
    });
  }
  /** Guest cancellation must not delete an already completed local artifact.
   * Check and cancellation share the same file-store lock. */
  cancelUnfinishedLocal(peer: string, id: string): Promise<void> {
    identity(peer);
    return this.port.exclusive(async () => {
      const journal = await this.load(), offer = this.find(journal, peer, id);
      if (offer.phase === 'complete') return;
      await this.cancel(journal, offer);
    });
  }
  /** Local consumption only; the authenticated remote command set cannot read files. */
  readCompleteChunk(peer: string, id: string, index: number): Promise<Uint8Array> {
    identity(peer);
    return this.port.exclusive(async () => {
      const journal = await this.load(), offer = this.find(journal, peer, id);
      if (offer.phase !== 'complete') throw new Error('incoming file not complete');
      if (!Number.isInteger(index) || index < 0 || index >= offer.manifest.chunk_hashes.length) throw new Error('invalid completed chunk index');
      return this.port.readCompleteChunk(peer, manifestCopy(offer.manifest), index);
    });
  }
  saveCompleteLocal(peer: string, id: string, path: string): Promise<void> {
    identity(peer);
    return this.port.exclusive(async () => {
      const offer = this.find(await this.load(), peer, id);
      if (offer.phase !== 'complete') throw new Error('Incoming file not complete');
      if (!this.port.saveComplete) throw new Error('Guest save unavailable');
      await this.port.saveComplete(peer, manifestCopy(offer.manifest), path);
    });
  }
  acceptLocal(peer: string, id: string): Promise<void> {
    identity(peer);
    return this.port.exclusive(async () => {
      const journal = await this.load(), offer = this.find(journal, peer, id);
      if (['cancelled', 'cancelling'].includes(offer.phase)) throw new Error('incoming transfer cancelled');
      if (offer.phase === 'offered') {
        let reserved = offer.manifest.size;
        for (const item of journal.offers) if (!['offered', 'cancelled'].includes(item.phase)) reserved += item.manifest.size;
        if (reserved > 33554432) throw new Error('incoming app quota exceeded');
        await this.phase(journal, offer, 'accepting');
      }
      await this.recoverOne(journal, offer);
    });
  }
  async executeAuthenticated(peer: string, request: CompanionFileRequest): Promise<CompanionFileValue> {
    identity(peer); if (peer === this.local) throw new Error('invalid incoming peer');
    const method = request.method, id = request.transfer_id, index = request.index, data = request.data.slice();
    const manifest = method === 'offer' ? manifestCopy(request.manifest as CompanionFileManifest) : null;
    return this.port.exclusive(async () => {
      const journal = await this.load();
      let offer: CompanionIncomingFileOffer;
      if (manifest !== null) {
        const old = journal.offers.find((item: CompanionIncomingFileOffer) => item.peer === peer && item.manifest.transfer_id === manifest.transfer_id);
        if (old !== undefined) {
          if (JSON.stringify(manifestCopy(old.manifest)) !== JSON.stringify(manifest)) throw new Error('incoming manifest changed'); offer = old;
        } else {
          if (journal.offers.length >= 128) throw new Error('incoming offer quota exceeded');
          offer = new CompanionIncomingFileOffer(); offer.peer = peer; offer.manifest = manifest; journal.offers.push(offer);
          await this.port.writeJournal(JSON.stringify(journal));
        }
      } else {
        offer = this.find(journal, peer, id);
        if (method === 'cancel') {
          await this.cancel(journal, offer);
        } else if (method !== 'status') {
          if (!['chunk', 'missing', 'finish'].includes(method)) throw new Error('unsupported incoming file method');
          await this.recoverOne(journal, offer);
          if (offer.phase !== 'cancelled') {
            if (!['accepted', 'complete'].includes(offer.phase)) throw new Error('incoming transfer not accepted');
            if (method === 'chunk') {
              if (!Number.isInteger(index) || index < 0 || index >= offer.manifest.chunk_hashes.length || data.length !== Math.min(65536, offer.manifest.size - index * 65536)) throw new Error('invalid incoming chunk');
              const digest = await this.crypto.sha256(data); let hash = ''; for (const byte of digest) hash += byte.toString(16).padStart(2, '0');
              if (hash !== offer.manifest.chunk_hashes[index]) throw new Error('incoming chunk hash mismatch');
              await this.port.writeChunk(peer, manifestCopy(offer.manifest), index, data);
            } else if (method === 'finish') {
              await this.port.finish(peer, manifestCopy(offer.manifest)); await this.phase(journal, offer, 'complete');
            }
          }
        }
      }
      const value = new CompanionFileValue(); value.phase = offer.phase;
      if (method === 'missing') {
        value.missing = offer.phase === 'cancelled' ? [] : await this.port.missing(peer, manifestCopy(offer.manifest));
        let prior = -1;
        for (const missing of value.missing) { if (!Number.isInteger(missing) || missing <= prior || missing >= offer.manifest.chunk_hashes.length) throw new Error('invalid backend missing chunks'); prior = missing; }
        if (offer.phase === 'complete' && value.missing.length > 0) { await this.phase(journal, offer, 'accepted'); value.phase = 'accepted'; }
      }
      return value;
    });
  }
}
