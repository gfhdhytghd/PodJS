import { CompanionMessageStore, CompanionMessageDigest } from './CompanionMessageOutbox';
import { CompanionFileRequest, CompanionFileManifest, encodeFileRequest, decodeFileRequest } from './CompanionFileWire';
import { decodeFileReply, decodeFileReplyHeader } from './CompanionFileReply';

export interface CompanionFileRequestCrypto extends CompanionMessageDigest { messageId(): Promise<string>; }
class RequestRecord {
  peer: string = '';
  id: string = '';
  payload: string = '';
  digest: string = '';
  reply: string | null = null;
}
class RequestSnapshot {
  kind: string = 'file-requests';
  schema: number = 1;
  app: string = '';
  local: string = '';
  records: RequestRecord[] = [];
}
export class CompanionPendingFileRequest {
  peer: string;
  messageId: string;
  payload: Uint8Array;
  digest: string;
  reply: Uint8Array | null;
  constructor(record: RequestRecord) {
    this.peer = record.peer; this.messageId = record.id; this.payload = unhex(record.payload);
    this.digest = record.digest; this.reply = record.reply === null ? null : unhex(record.reply);
  }
}
/** Authenticated terminal observation retained until an explicit host action.
 * It is not an assertion that the peer exported the file to shared storage. */
export class CompanionFileTerminalReceipt {
  constructor(readonly peer: string, readonly messageId: string, readonly transferId: string,
    readonly digest: string, readonly phase: string) {}
}
function identity(id: string): void {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) throw new Error('invalid file request identity');
}
function hex(bytes: Uint8Array): string {
  let value = ''; for (const byte of bytes) value += byte.toString(16).padStart(2, '0'); return value;
}
function unhex(value: string): Uint8Array {
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(value.slice(i * 2, i * 2 + 2), 16); return bytes;
}
function binary(value: string, maximum: number): void {
  if (typeof value !== 'string' || value.length < 2 || value.length > maximum * 2 || value.length % 2 !== 0 || !/^[0-9a-f]+$/.test(value)) throw new Error('invalid stored file bytes');
}
/** Dedicated durable CAS key. One pending request per peer, no eviction; host
 * consumes completed observations before forgetting. Never regenerate pending
 * IDs or bytes across reconnect. Successful CAS must represent durable commit. */
export class CompanionFileRequests {
  private app: string;
  private local: string;
  private store: CompanionMessageStore;
  private crypto: CompanionFileRequestCrypto;
  private tail: Promise<void> = Promise.resolve();
  constructor(app: string, local: string, store: CompanionMessageStore, crypto: CompanionFileRequestCrypto) {
    identity(app); identity(local); this.app = app; this.local = local; this.store = store; this.crypto = crypto;
  }
  matchesIdentity(app: string, local: string): boolean { return app === this.app && local === this.local; }
  private run<T>(work: () => Promise<T>): Promise<T> {
    const result = this.tail.then(work); this.tail = result.then(() => {}, () => {}); return result;
  }
  private async load(raw: string | null): Promise<RequestSnapshot> {
    if (raw === null) { const snapshot = new RequestSnapshot(); snapshot.app = this.app; snapshot.local = this.local; return snapshot; }
    if (raw.length > 18000000) throw new Error('file request snapshot too large');
    const snapshot = JSON.parse(raw) as RequestSnapshot;
    if (!snapshot || snapshot.kind !== 'file-requests' || snapshot.schema !== 1 || snapshot.app !== this.app || snapshot.local !== this.local ||
      !Array.isArray(snapshot.records) || snapshot.records.length > 128) throw new Error('invalid file request snapshot');
    const ids: string[] = [], pending: string[] = []; let cost = 0;
    for (const record of snapshot.records) {
      if (!record) throw new Error('invalid file request record'); identity(record.peer); identity(record.id);
      if (record.peer === this.local || ids.includes(record.id)) throw new Error('invalid file request identity'); ids.push(record.id);
      binary(record.payload, 98304); const payload = unhex(record.payload), request = decodeFileRequest(payload);
      if (typeof record.digest !== 'string' || !/^[0-9a-f]{64}$/.test(record.digest) || hex(await this.crypto.sha256(payload)) !== record.digest) throw new Error('file request digest mismatch');
      cost += payload.length + 4096 + 256; if (cost > 8388608) throw new Error('file request capacity exceeded');
      if (record.reply === null) {
        if (pending.includes(record.peer)) throw new Error('multiple pending file requests'); pending.push(record.peer);
      } else { binary(record.reply, 4096); decodeFileReply(unhex(record.reply), request, record.digest); }
    }
    return snapshot;
  }
  private async save(raw: string | null, snapshot: RequestSnapshot, cancelled: () => boolean = () => false): Promise<void> {
    if (cancelled()) throw new Error('file request operation cancelled');
    if (!await this.store.compareExchange(raw, JSON.stringify(snapshot))) throw new Error('file request storage conflict');
  }
  enqueue(peer: string, request: CompanionFileRequest, cancelled: () => boolean = () => false): Promise<CompanionPendingFileRequest> {
    identity(peer); if (peer === this.local) throw new Error('invalid file request peer'); const payload = encodeFileRequest(request);
    return this.run(async () => {
      if (cancelled()) throw new Error('file request operation cancelled');
      const raw = await this.store.read(), snapshot = await this.load(raw);
      if (cancelled()) throw new Error('file request operation cancelled');
      if (snapshot.records.some((record: RequestRecord) => record.peer === peer && record.reply === null)) throw new Error('peer has pending file request');
      let cost = payload.length + 4352; for (const record of snapshot.records) cost += record.payload.length / 2 + 4352;
      if (snapshot.records.length >= 128 || cost > 8388608) throw new Error('file request queue full');
      const id = await this.crypto.messageId(); identity(id);
      if (snapshot.records.some((record: RequestRecord) => record.id === id)) throw new Error('file request ID collision');
      const digest = await this.crypto.sha256(payload); if (digest.length !== 32) throw new Error('invalid file request digest');
      const record = new RequestRecord(); record.peer = peer; record.id = id; record.payload = hex(payload); record.digest = hex(digest);
      snapshot.records.push(record); await this.save(raw, snapshot, cancelled); return new CompanionPendingFileRequest(record);
    });
  }
  next(peer: string): Promise<CompanionPendingFileRequest | null> {
    identity(peer); return this.run(async () => {
      const snapshot = await this.load(await this.store.read());
      const record = snapshot.records.find((item: RequestRecord) => item.peer === peer && item.reply === null);
      return record === undefined ? null : new CompanionPendingFileRequest(record);
    });
  }
  /** Read-only identity evidence across all peers, including completed receipts.
   * This is not an outgoing transfer registry: a sender may temporarily have no
   * request between consuming a reply and enqueueing its next operation. */
  recordsForTransfer(transferId: string): Promise<CompanionPendingFileRequest[]> {
    identity(transferId); return this.run(async () => {
      const snapshot = await this.load(await this.store.read());
      return snapshot.records.filter((record: RequestRecord) => {
        const request = decodeFileRequest(unhex(record.payload));
        const id = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
        return id === transferId;
      }).map((record: RequestRecord) => new CompanionPendingFileRequest(record));
    });
  }
  receiveAuthenticated(peer: string, id: string, bytes: Uint8Array): Promise<string> {
    identity(peer); identity(id); if (bytes.length > 4096) throw new Error('file reply too large');
    const stable = bytes.slice(); decodeFileReplyHeader(stable);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const record = snapshot.records.find((item: RequestRecord) => item.peer === peer && item.id === id);
      if (record === undefined) return 'stale_reply';
      decodeFileReply(stable, decodeFileRequest(unhex(record.payload)), record.digest);
      if (record.reply !== null) return 'duplicate_reply';
      record.reply = hex(stable); await this.save(raw, snapshot); return 'reply';
    });
  }
  completed(peer: string): Promise<CompanionPendingFileRequest[]> {
    identity(peer); return this.run(async () => {
      const snapshot = await this.load(await this.store.read());
      return snapshot.records.filter((record: RequestRecord) => record.peer === peer && record.reply !== null).map((record: RequestRecord) => new CompanionPendingFileRequest(record));
    });
  }
  private terminalRecord(snapshot: RequestSnapshot, peer: string): RequestRecord | null {
    const records = snapshot.records.filter((record: RequestRecord) => record.peer === peer);
    if (records.length !== 1 || records[0].reply === null) return null;
    const record = records[0];
    const reply = decodeFileReply(unhex(record.reply as string), decodeFileRequest(unhex(record.payload)), record.digest);
    return reply.value.phase === 'complete' || reply.value.phase === 'cancelled' ? record : null;
  }
  terminal(peer: string): Promise<CompanionFileTerminalReceipt | null> {
    identity(peer); return this.run(async () => {
      const record = this.terminalRecord(await this.load(await this.store.read()), peer);
      if (record === null) return null;
      const request = decodeFileRequest(unhex(record.payload));
      const reply = decodeFileReply(unhex(record.reply as string), request, record.digest);
      const transferId = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
      return new CompanionFileTerminalReceipt(peer, record.id, transferId, record.digest, reply.value.phase);
    });
  }
  /** Consume only the exact terminal observation the user has seen. Atomic CAS
   * rejects pending/extra records, stale confirmations and changed observations.
   * This removes the local receipt, not source bytes or the peer's file. */
  consumeTerminal(receipt: CompanionFileTerminalReceipt): Promise<boolean> {
    identity(receipt.peer); identity(receipt.messageId); identity(receipt.transferId);
    const expected = new CompanionFileTerminalReceipt(receipt.peer, receipt.messageId, receipt.transferId, receipt.digest, receipt.phase);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const record = this.terminalRecord(snapshot, expected.peer);
      if (record === null || record.id !== expected.messageId || record.digest !== expected.digest) return false;
      const request = decodeFileRequest(unhex(record.payload));
      const reply = decodeFileReply(unhex(record.reply as string), request, record.digest);
      const transferId = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
      if (transferId !== expected.transferId || reply.value.phase !== expected.phase) return false;
      snapshot.records.splice(snapshot.records.indexOf(record), 1); await this.save(raw, snapshot); return true;
    });
  }
  forgetCompleted(peer: string, id: string, cancelled: () => boolean = () => false): Promise<boolean> {
    identity(peer); identity(id); return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      if (cancelled()) throw new Error('file request operation cancelled');
      const index = snapshot.records.findIndex((record: RequestRecord) => record.peer === peer && record.id === id && record.reply !== null);
      if (index < 0) return false; snapshot.records.splice(index, 1); await this.save(raw, snapshot, cancelled); return true;
    });
  }
}
