import { CompanionMessageStore, CompanionMessageDigest } from './CompanionMessageOutbox';
import { CompanionMessageEnvelope, encodeMessageEnvelope, decodeMessageEnvelope } from './CompanionMessageWire';

class InboxRecord {
  peer: string = '';
  id: string = '';
  wire: string = '';
  digest: string = '';
  applied: boolean = false;
}
class InboxSnapshot {
  kind: string = 'message-inbox';
  schema: number = 1;
  app: string = '';
  local: string = '';
  records: InboxRecord[] = [];
}
export class CompanionIncomingMessage {
  peer: string;
  messageId: string;
  envelope: CompanionMessageEnvelope;
  digest: Uint8Array;
  status: string;
  constructor(peer: string, id: string, envelope: CompanionMessageEnvelope, digest: Uint8Array, status: string) {
    this.peer = peer; this.messageId = id; this.envelope = envelope; this.digest = digest.slice(); this.status = status;
  }
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid inbox identity');
}
function time(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('invalid inbox time');
}
function hex(bytes: Uint8Array): string {
  let text = ''; for (const byte of bytes) text += byte.toString(16).padStart(2, '0'); return text;
}
function unhex(text: string): Uint8Array {
  const bytes = new Uint8Array(text.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}
/** Dedicated inbox CAS key, separate from outbox/state. Applied receipts remain
 * until expiry. Callers must make business writes idempotent by authenticated
 * peer/ID: a crash before acknowledge can repeat business work. */
export class CompanionMessageInbox {
  private app: string;
  private local: string;
  private store: CompanionMessageStore;
  private crypto: CompanionMessageDigest;
  private tail: Promise<void> = Promise.resolve();
  constructor(app: string, local: string, store: CompanionMessageStore, crypto: CompanionMessageDigest) {
    identity(app); identity(local); this.app = app; this.local = local; this.store = store; this.crypto = crypto;
  }
  private run<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation); this.tail = result.then(() => {}, () => {}); return result;
  }
  matchesIdentity(app: string, local: string): boolean { return this.app === app && this.local === local; }
  private async load(raw: string | null): Promise<InboxSnapshot> {
    if (raw === null) { const snapshot = new InboxSnapshot(); snapshot.app = this.app; snapshot.local = this.local; return snapshot; }
    if (raw.length > 18000000) throw new Error('inbox snapshot too large');
    const snapshot = JSON.parse(raw) as InboxSnapshot;
    if (!snapshot || snapshot.kind !== 'message-inbox' || snapshot.schema !== 1 || snapshot.app !== this.app ||
      snapshot.local !== this.local || !Array.isArray(snapshot.records) || snapshot.records.length > 1000) throw new Error('invalid inbox snapshot');
    const keys: string[] = []; let cost = 0;
    for (const record of snapshot.records) {
      if (!record) throw new Error('invalid inbox record');
      identity(record.peer); identity(record.id);
      if (record.peer === this.local || typeof record.applied !== 'boolean' || typeof record.wire !== 'string' ||
        record.wire.length < 18 || record.wire.length > 524306 || record.wire.length % 2 !== 0 || !/^[0-9a-f]*$/.test(record.wire) ||
        typeof record.digest !== 'string' || !/^[0-9a-f]{64}$/.test(record.digest)) throw new Error('invalid inbox record');
      const key = record.peer + '/' + record.id;
      if (keys.includes(key)) throw new Error('duplicate inbox record'); keys.push(key);
      const wire = unhex(record.wire); decodeMessageEnvelope(wire);
      cost += wire.length - 9 + 1024; if (cost > 8388608) throw new Error('inbox capacity exceeded');
      if (hex(await this.crypto.sha256(wire)) !== record.digest) throw new Error('inbox digest mismatch');
    }
    return snapshot;
  }
  private async save(raw: string | null, snapshot: InboxSnapshot): Promise<void> {
    if (!await this.store.compareExchange(raw, JSON.stringify(snapshot))) throw new Error('inbox storage conflict');
  }
  receiveAuthenticated(peer: string, id: string, bytes: Uint8Array, now: number): Promise<CompanionIncomingMessage> {
    identity(peer); identity(id); time(now); if (peer === this.local) throw new Error('invalid inbox peer');
    const envelope = decodeMessageEnvelope(bytes), wire = encodeMessageEnvelope(envelope);
    return this.run(async () => {
      const digest = await this.crypto.sha256(wire);
      if (digest.length !== 32) throw new Error('invalid inbox digest');
      if (envelope.expiresAt <= now) return new CompanionIncomingMessage(peer, id, envelope, digest, 'expired');
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const prior = snapshot.records.find((record: InboxRecord) => record.peer === peer && record.id === id);
      if (prior !== undefined) {
        if (prior.wire !== hex(wire) || prior.digest !== hex(digest)) throw new Error('inbox message content changed');
        return new CompanionIncomingMessage(peer, id, envelope, digest, prior.applied ? 'applied' : 'pending');
      }
      snapshot.records = snapshot.records.filter((record: InboxRecord) => decodeMessageEnvelope(unhex(record.wire)).expiresAt > now);
      let cost = envelope.payload.length + 1024;
      for (const record of snapshot.records) cost += record.wire.length / 2 - 9 + 1024;
      if (snapshot.records.length >= 1000 || cost > 8388608) throw new Error('inbox full');
      const record = new InboxRecord(); record.peer = peer; record.id = id; record.wire = hex(wire); record.digest = hex(digest);
      snapshot.records.push(record); await this.save(raw, snapshot);
      return new CompanionIncomingMessage(peer, id, envelope, digest, 'pending');
    });
  }
  acknowledge(peer: string, id: string, digest: Uint8Array, now: number): Promise<CompanionIncomingMessage> {
    identity(peer); identity(id); time(now);
    if (digest.length !== 32) throw new Error('invalid inbox token'); const token = hex(digest);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const record = snapshot.records.find((item: InboxRecord) => item.peer === peer && item.id === id);
      if (record === undefined) throw new Error('unknown inbox delivery');
      const envelope = decodeMessageEnvelope(unhex(record.wire));
      if (envelope.expiresAt <= now) throw new Error('inbox delivery expired');
      if (record.digest !== token) throw new Error('inbox delivery changed');
      if (!record.applied) { record.applied = true; await this.save(raw, snapshot); }
      return new CompanionIncomingMessage(peer, id, envelope, unhex(record.digest), 'applied');
    });
  }
  pending(now: number, limit: number, offset: number = 0): Promise<CompanionIncomingMessage[]> {
    time(now); if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('invalid inbox batch');
    if (!Number.isInteger(offset) || offset < 0 || offset >= 1000) throw new Error('invalid inbox offset');
    return this.run(async () => {
      const snapshot = await this.load(await this.store.read()), result: CompanionIncomingMessage[] = [];
      for (const record of snapshot.records) {
        const envelope = decodeMessageEnvelope(unhex(record.wire));
        if (!record.applied && envelope.expiresAt > now) result.push(new CompanionIncomingMessage(record.peer, record.id, envelope, unhex(record.digest), 'pending'));
      }
      result.sort((a: CompanionIncomingMessage, b: CompanionIncomingMessage) => Number(b.envelope.highPriority) - Number(a.envelope.highPriority));
      return result.slice(offset, offset + limit);
    });
  }
}
