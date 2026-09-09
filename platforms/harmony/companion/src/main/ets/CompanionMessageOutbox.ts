import { CompanionMessageEnvelope, encodeMessageEnvelope } from './CompanionMessageWire';

/** Dedicated message namespace: MUST NOT share a state-sync snapshot key.
 * Successful CAS means the complete replacement has been durably committed. */
export interface CompanionMessageStore {
  read(): Promise<string | null>;
  compareExchange(expected: string | null, desired: string): Promise<boolean>;
}
export interface CompanionMessageDigest { sha256(bytes: Uint8Array): Promise<Uint8Array>; }
class StoredMessage {
  peer: string = '';
  id: string = '';
  expires: number = 0;
  high: boolean = false;
  payload: string = '';
  digest: string = '';
}
class OutboxSnapshot {
  schema: number = 2;
  app: string = '';
  local: string = '';
  messages: StoredMessage[] = [];
  intents: MessageIntent[] = [];
}
class MessageIntent {
  peer: string = '';
  id: string = '';
  ttl: number = 0;
  expires: number = 0;
  high: boolean = false;
  digest: string = '';
}
export class CompanionOutgoingMessage {
  peer: string;
  messageId: string;
  envelope: CompanionMessageEnvelope;
  digest: Uint8Array;
  constructor(record: StoredMessage) {
    this.peer = record.peer; this.messageId = record.id;
    this.envelope = new CompanionMessageEnvelope(record.expires, record.high, unhex(record.payload));
    this.digest = unhex(record.digest);
  }
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid message identity');
}
function time(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('invalid message time');
}
function hex(bytes: Uint8Array): string {
  let value = ''; for (const byte of bytes) value += byte.toString(16).padStart(2, '0'); return value;
}
function unhex(value: string): Uint8Array {
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(value.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}
/** Durable at-least-once outbox; queue reads do not mark sent. Each operation
 * rereads storage; competing writers fail explicitly instead of losing edits. */
export class CompanionMessageOutbox {
  private app: string;
  private local: string;
  private store: CompanionMessageStore;
  private crypto: CompanionMessageDigest;
  private tail: Promise<void> = Promise.resolve();
  constructor(app: string, local: string, store: CompanionMessageStore, crypto: CompanionMessageDigest) {
    identity(app); identity(local); this.app = app; this.local = local; this.store = store; this.crypto = crypto;
  }
  private async load(raw: string | null): Promise<OutboxSnapshot> {
    if (raw === null) { const fresh = new OutboxSnapshot(); fresh.app = this.app; fresh.local = this.local; return fresh; }
    if (raw.length > 18000000) throw new Error('message snapshot too large');
    const snapshot = JSON.parse(raw) as OutboxSnapshot;
    if (!snapshot || ![1, 2].includes(snapshot.schema) || snapshot.app !== this.app || snapshot.local !== this.local ||
      !Array.isArray(snapshot.messages) || snapshot.messages.length > 1000) throw new Error('invalid message snapshot');
    if (snapshot.schema === 1) {
      if (snapshot.intents !== undefined) throw new Error('invalid legacy message snapshot');
      snapshot.intents = [];
    }
    if (!Array.isArray(snapshot.intents) || snapshot.intents.length > 10000) throw new Error('invalid message retry ledger');
    const intentKeys: Map<string, MessageIntent> = new Map();
    for (const intent of snapshot.intents) {
      if (!intent) throw new Error('invalid message retry identity');
      identity(intent.peer); identity(intent.id); time(intent.ttl); time(intent.expires);
      const key = intent.peer + '/' + intent.id;
      if (intent.peer === this.local || intent.ttl === 0 || intent.expires < intent.ttl ||
        typeof intent.high !== 'boolean' || typeof intent.digest !== 'string' ||
        !/^[0-9a-f]{64}$/.test(intent.digest) || intentKeys.has(key)) throw new Error('invalid message retry identity');
      intentKeys.set(key, intent);
    }
    const keys: string[] = []; let cost = 0;
    for (const message of snapshot.messages) {
      if (!message) throw new Error('invalid stored message');
      identity(message.peer); identity(message.id); time(message.expires);
      if (message.peer === this.local || typeof message.high !== 'boolean' || typeof message.payload !== 'string' ||
        message.payload.length > 524288 || message.payload.length % 2 !== 0 || !/^[0-9a-f]*$/.test(message.payload) ||
        typeof message.digest !== 'string' || !/^[0-9a-f]{64}$/.test(message.digest)) throw new Error('invalid stored message');
      const key = message.peer + '/' + message.id;
      if (keys.includes(key)) throw new Error('duplicate stored message'); keys.push(key);
      cost += message.payload.length / 2 + 1024;
      if (cost > 8388608) throw new Error('message queue too large');
      const bytes = encodeMessageEnvelope(new CompanionMessageEnvelope(message.expires, message.high, unhex(message.payload)));
      if (hex(await this.crypto.sha256(bytes)) !== message.digest) throw new Error('stored message digest mismatch');
      const intent = intentKeys.get(key);
      if (intent !== undefined && (intent.expires !== message.expires || intent.high !== message.high ||
        intent.digest !== hex(await this.crypto.sha256(unhex(message.payload))))) throw new Error('message retry content mismatch');
    }
    return snapshot;
  }
  matchesIdentity(app: string, local: string): boolean { return this.app === app && this.local === local; }
  private run<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation); this.tail = result.then(() => {}, () => {}); return result;
  }
  private async save(raw: string | null, snapshot: OutboxSnapshot): Promise<void> {
    snapshot.schema = 2;
    const desired = JSON.stringify(snapshot);
    if (desired.length > 18000000) throw new Error('message snapshot too large');
    if (!await this.store.compareExchange(raw, desired)) throw new Error('message storage conflict');
  }
  /** Queue and first expiry commit atomically. ACK removes only the queue row;
   * the retry identity survives until expiry so a lost service reply cannot
   * recreate an acknowledged message. Legacy absolute-expiry IDs are distinct.
   */
  enqueueWithTtl(peer: string, messageId: string, payload: Uint8Array, ttl: number, high: boolean, now: number): Promise<number> {
    identity(peer); identity(messageId); time(ttl); time(now);
    if (peer === this.local || ttl === 0 || ttl > Number.MAX_SAFE_INTEGER - now) throw new Error('invalid message TTL');
    const stable = new CompanionMessageEnvelope(now + ttl, high, payload);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const payloadDigest = await this.crypto.sha256(stable.payload);
      if (payloadDigest.length !== 32) throw new Error('invalid message digest');
      const digest = hex(payloadDigest);
      const prior = snapshot.intents.find((item: MessageIntent) => item.peer === peer && item.id === messageId);
      if (prior !== undefined) {
        if (prior.ttl !== ttl || prior.high !== high || prior.digest !== digest) throw new Error('message retry identity changed');
        if (prior.expires <= now) throw new Error('message retry expired');
        return prior.expires;
      }
      if (snapshot.messages.some((item: StoredMessage) => item.peer === peer && item.id === messageId))
        throw new Error('message ID belongs to absolute-expiry request');
      snapshot.intents = snapshot.intents.filter((item: MessageIntent) => item.expires > now);
      if (snapshot.intents.length >= 10000) throw new Error('message retry ledger full');
      await this.append(snapshot, peer, messageId, stable, now);
      const intent = new MessageIntent(); intent.peer = peer; intent.id = messageId; intent.ttl = ttl;
      intent.expires = stable.expiresAt; intent.high = high; intent.digest = digest;
      snapshot.intents.push(intent); await this.save(raw, snapshot); return intent.expires;
    });
  }
  private async append(snapshot: OutboxSnapshot, peer: string, messageId: string, stable: CompanionMessageEnvelope, now: number): Promise<void> {
    const digest = await this.crypto.sha256(encodeMessageEnvelope(stable));
    if (digest.length !== 32) throw new Error('invalid message digest');
    const record = new StoredMessage(); record.peer = peer; record.id = messageId; record.expires = stable.expiresAt;
    record.high = stable.highPriority; record.payload = hex(stable.payload); record.digest = hex(digest);
    snapshot.messages = snapshot.messages.filter((item: StoredMessage) => item.expires > now);
    let cost = stable.payload.length + 1024;
    for (const item of snapshot.messages) cost += item.payload.length / 2 + 1024;
    if (snapshot.messages.length >= 1000 || cost > 8388608) throw new Error('message queue full');
    snapshot.messages.push(record);
  }
  enqueue(peer: string, messageId: string, envelope: CompanionMessageEnvelope, now: number): Promise<void> {
    identity(peer); identity(messageId); time(now);
    if (peer === this.local) throw new Error('invalid message peer');
    const stable = new CompanionMessageEnvelope(envelope.expiresAt, envelope.highPriority, envelope.payload);
    if (stable.expiresAt <= now) throw new Error('message already expired');
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const intent = snapshot.intents.find((item: MessageIntent) => item.peer === peer && item.id === messageId);
      if (intent !== undefined) {
        if (intent.expires !== stable.expiresAt || intent.high !== stable.highPriority ||
          intent.digest !== hex(await this.crypto.sha256(stable.payload))) throw new Error('message identity conflicts with TTL request');
        return;
      }
      const digest = await this.crypto.sha256(encodeMessageEnvelope(stable));
      if (digest.length !== 32) throw new Error('invalid message digest');
      // Reject ID changes even if the old row has expired but has not been purged.
      const prior = snapshot.messages.find((item: StoredMessage) => item.peer === peer && item.id === messageId);
      if (prior !== undefined) {
        if (prior.digest !== hex(digest)) throw new Error('message ID content mismatch');
        return;
      }
      await this.append(snapshot, peer, messageId, stable, now); await this.save(raw, snapshot);
    });
  }
  pending(peer: string, now: number, limit: number): Promise<CompanionOutgoingMessage[]> {
    identity(peer); time(now);
    if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new Error('invalid message batch limit');
    return this.run(async () => {
      const snapshot = await this.load(await this.store.read());
      const rows = snapshot.messages.filter((item: StoredMessage) => item.peer === peer && item.expires > now);
      // Stable sort preserves FIFO among equal priorities.
      rows.sort((a: StoredMessage, b: StoredMessage) => Number(b.high) - Number(a.high));
      return rows.slice(0, limit).map((item: StoredMessage) => new CompanionOutgoingMessage(item));
    });
  }
  acknowledgeAuthenticated(peer: string, messageId: string, digest: Uint8Array): Promise<boolean> {
    identity(peer); identity(messageId);
    if (digest.length !== 32) throw new Error('invalid message ACK digest');
    const expected = hex(digest);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw);
      const index = snapshot.messages.findIndex((item: StoredMessage) => item.peer === peer && item.id === messageId);
      if (index < 0) return false;
      if (snapshot.messages[index].digest !== expected) throw new Error('message ACK content mismatch');
      snapshot.messages.splice(index, 1); await this.save(raw, snapshot); return true;
    });
  }
  expire(now: number): Promise<number> {
    time(now);
    return this.run(async () => {
      const raw = await this.store.read(), snapshot = await this.load(raw), before = snapshot.messages.length;
      snapshot.messages = snapshot.messages.filter((item: StoredMessage) => item.expires > now);
      const removed = before - snapshot.messages.length;
      if (removed > 0) await this.save(raw, snapshot); return removed;
    });
  }
}
