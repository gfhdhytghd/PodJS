import { CompanionStateSynchronizer } from './CompanionStateSynchronizer';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionStreamTimer } from './CompanionPacketStream';

/** Companion state SDK. A transport must authenticate before invoking ingress.
 * The CAS port must durably commit before resolving; no network work occurs here.
 */
export interface CompanionStatePort {
  read(appId: string): Promise<string | null>;
  compareExchange(appId: string, expected: string | null, desired: string): Promise<boolean>;
}
export interface CompanionStateCrypto {
  sha256(text: string): Promise<string>;
  messageId(): Promise<string>;
}
export class CompanionStateBatch {
  messageId: string = '';
  from: number = 0;
  to: number = 0;
  payload: string = '';
  digest: string = '';
}
export class CompanionStateReceipt {
  cursor: number = 0;
  digest: string = '';
  duplicate: boolean = false;
}
export class CompanionStateAcknowledgement {
  constructor(readonly synchronized: boolean, readonly appliedCursor: number) {}
}
class IncomingState {
  peer: string = '';
  from: number = 0;
  to: number = 0;
  messageId: string = '';
  digest: string = '';
}
class StateBody {
  version: number = 1;
  from: number = 0;
  to: number = 0;
  entries: CompanionStateEntry[] = [];
}
class OutgoingState {
  peer: string = '';
  acknowledged: number = 0;
  sentHash: string | null = null;
  cycle: string | null = null;
  cycleHash: string | null = null;
  position: number = 0;
  pending: CompanionStateBatch | null = null;
}
export class CompanionStateEntry {
  key: string = '';
  value: Object | null = null;
  counter: number = 0;
  deviceId: string = '';
  deleted: boolean = false;
}
export class CompanionStateSnapshot {
  version: number = 1;
  clock: number = 0;
  entries: CompanionStateEntry[] = [];
  cursors: Record<string, number> = {};
}
class StoredState {
  schema: number = 3;
  appId: string = '';
  localDeviceId: string = '';
  state: CompanionStateSnapshot = new CompanionStateSnapshot();
  outgoing: OutgoingState[] = [];
  incoming: IncomingState[] = [];
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('Invalid sync identity');
}
function counter(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid sync counter');
}
function digest(value: string): void {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) throw new Error('Invalid state digest');
}
function setCursor(state: CompanionStateSnapshot, peer: string, to: number): void {
  const cursors = new Map<string, number>();
  for (const key of Object.keys(state.cursors)) cursors.set(key, state.cursors[key]);
  cursors.set(peer, to);
  const fields: string[] = [];
  cursors.forEach((value: number, key: string) => { fields.push(JSON.stringify(key) + ':' + value.toString()); });
  state.cursors = JSON.parse('{' + fields.join(',') + '}') as Record<string, number>;
}
function body(payload: string): StateBody {
  if (typeof payload !== 'string' || bytes(payload) > 256 * 1024) throw new Error('Invalid state batch size');
  const parsed = JSON.parse(payload) as StateBody;
  if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.entries) || parsed.entries.length > 512) throw new Error('Invalid state batch');
  counter(parsed.from); counter(parsed.to);
  if (parsed.to !== parsed.from + 1) throw new Error('Invalid state batch range');
  for (const entry of parsed.entries) validate(entry);
  return parsed;
}
function canonical(value: Object | null, depth: number = 0): string {
  if (depth > 32) throw new Error('Sync value nesting limit');
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number' && Number.isFinite(value)) return JSON.stringify(value);
  if (Array.isArray(value)) {
    const values = value as (Object | null)[];
    const parts: string[] = [];
    for (let index = 0; index < values.length; index++) parts.push(canonical(values[index], depth + 1));
    return '[' + parts.join(',') + ']';
  }
  if (typeof value === 'object') {
    const record = value as Record<string, Object | null>;
    const parts: string[] = [];
    for (const key of Object.keys(record).sort()) parts.push(JSON.stringify(key) + ':' + canonical(record[key], depth + 1));
    return '{' + parts.join(',') + '}';
  }
  throw new Error('Sync values must be finite JSON data');
}
/** Stable finite JSON for service payloads; no state-specific size quota. */
export function encodeCompanionJson(value: Object | null): string {
  const text = canonical(value);
  if (canonical(JSON.parse(JSON.stringify(value)) as Object | null) !== text) throw new Error('Non-JSON sync value');
  return text;
}
function freezeValue(value: Object | null): Object | null {
  const text = canonical(value);
  if (text.length > 65536) throw new Error('State value too large; use file sync');
  // Reject custom JSON conversion (Date/toJSON), undefined, sparse arrays and
  // non-finite values instead of silently changing the payload on persistence.
  const encoded = JSON.stringify(value);
  const clone = JSON.parse(encoded) as Object | null;
  if (canonical(clone) !== text) throw new Error('Non-JSON state value');
  return JSON.parse(text) as Object | null;
}
function validate(entry: CompanionStateEntry): void {
  if (!entry) throw new Error('Invalid state entry');
  identity(entry.key); identity(entry.deviceId); counter(entry.counter);
  if (typeof entry.deleted !== 'boolean' || (entry.deleted && entry.value !== null)) throw new Error('Invalid tombstone');
  freezeValue(entry.value);
}
function compare(a: CompanionStateEntry, b: CompanionStateEntry): number {
  return a.counter - b.counter || (a.deviceId < b.deviceId ? -1 : a.deviceId > b.deviceId ? 1 : 0);
}
function bytes(text: string): number {
  let size = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text.charCodeAt(i);
    if (ch < 0x80) size++;
    else if (ch < 0x800) size += 2;
    else if (ch >= 0xd800 && ch <= 0xdbff && i + 1 < text.length && text.charCodeAt(i + 1) >= 0xdc00 && text.charCodeAt(i + 1) <= 0xdfff) { size += 4; i++; }
    else size += 3;
  }
  return size;
}

export class CompanionState {
  /** Starts an explicit bounded foreground run; caller must close on background. */
  synchronize(connection: CompanionSyncConnection, timer: CompanionStreamTimer, durationMs: number): CompanionStateSynchronizer {
    return new CompanionStateSynchronizer(connection, this, timer, durationMs);
  }
  matchesIdentity(appId: string, deviceId: string): boolean { return this.appId === appId && this.deviceId === deviceId; }
  private tail: Promise<void> = Promise.resolve();
  private listeners: Set<(state: CompanionStateSnapshot) => void> = new Set();
  constructor(private appId: string, private deviceId: string, private port: CompanionStatePort,
    private crypto: CompanionStateCrypto | null = null) {
    identity(appId); identity(deviceId);
  }
  get(key: string): Promise<Object | null | undefined> {
    return this.serial(async () => {
      identity(key);
      const stored = this.load(await this.port.read(this.appId));
      const entry = stored.state.entries.find(value => value.key === key);
      return entry && !entry.deleted ? freezeValue(entry.value) : undefined;
    });
  }
  snapshot(): Promise<CompanionStateSnapshot> {
    return this.serial(async () => this.load(await this.port.read(this.appId)).state);
  }
  /** Read-only ACK barrier for the current durable snapshot, not a claim that
   * the peer has no undisclosed edits. Does not prepare or send another batch. */
  acknowledgement(peer: string): Promise<CompanionStateAcknowledgement> {
    identity(peer);
    return this.serial(async () => {
      const stored = this.load(await this.port.read(this.appId));
      if (this.crypto === null) throw new Error('State sender crypto unavailable');
      const hash = await this.crypto.sha256(JSON.stringify(stored.state.entries)); digest(hash);
      const outgoing = stored.outgoing.find(value => value.peer === peer);
      const synchronized = outgoing !== undefined && outgoing.pending === null && outgoing.cycle === null && outgoing.sentHash === hash;
      const cursor = Object.keys(stored.state.cursors).includes(peer) ? stored.state.cursors[peer] : 0;
      return new CompanionStateAcknowledgement(synchronized, cursor);
    });
  }
  set(key: string, value: Object | null): Promise<CompanionStateEntry> { return this.write(key, value, false); }
  delete(key: string): Promise<CompanionStateEntry> { return this.write(key, null, true); }
  subscribe(listener: (state: CompanionStateSnapshot) => void): () => void {
    this.listeners.add(listener); return () => { this.listeners.delete(listener); };
  }
  /** Persist before send. Reopening returns identical pending ID/payload bytes.
   * Only the authenticated peer's matching ACK may advance this cycle.
   */
  prepare(peer: string): Promise<CompanionStateBatch | null> {
    return this.transact(async stored => {
      identity(peer);
      const crypto = this.crypto;
      if (crypto === null) throw new Error('State sender crypto unavailable');
      let outgoing = stored.outgoing.find(value => value.peer === peer);
      if (outgoing === undefined) {
        if (stored.outgoing.length >= 128) throw new Error('State outgoing peer quota exceeded');
        outgoing = new OutgoingState(); outgoing.peer = peer; stored.outgoing.push(outgoing);
      }
      if (outgoing.pending !== null) {
        if (await crypto.sha256(outgoing.pending.payload) !== outgoing.pending.digest) throw new Error('Corrupt pending state digest');
        return outgoing.pending;
      }
      if (outgoing.cycle === null) {
        const cycle = JSON.stringify(stored.state.entries), hash = await crypto.sha256(cycle);
        digest(hash);
        if (hash === outgoing.sentHash) return null;
        outgoing.cycle = cycle; outgoing.cycleHash = hash; outgoing.position = 0;
      }
      if (await crypto.sha256(outgoing.cycle) !== outgoing.cycleHash) throw new Error('Corrupt state cycle digest');
      const all = JSON.parse(outgoing.cycle) as CompanionStateEntry[];
      const next = new StateBody(); next.from = outgoing.acknowledged; next.to = next.from + 1; counter(next.to);
      let size = 0;
      while (outgoing.position + next.entries.length < all.length && next.entries.length < 512) {
        const entry = all[outgoing.position + next.entries.length], extra = bytes(JSON.stringify(entry)) + 1;
        if (size + extra > 256 * 1024 - 256) break;
        next.entries.push(entry); size += extra;
      }
      if (next.entries.length === 0 && outgoing.position < all.length) throw new Error('State entry cannot fit wire batch');
      const pending = new CompanionStateBatch();
      pending.messageId = await crypto.messageId(); identity(pending.messageId);
      pending.from = next.from; pending.to = next.to; pending.payload = JSON.stringify(next);
      body(pending.payload); pending.digest = await crypto.sha256(pending.payload); digest(pending.digest);
      outgoing.pending = pending;
      return pending;
    }, false);
  }
  acknowledgeAuthenticated(peer: string, messageId: string, to: number, hash: string): Promise<boolean> {
    return this.transact(async stored => {
      identity(peer); identity(messageId); counter(to); digest(hash);
      const outgoing = stored.outgoing.find(value => value.peer === peer);
      if (outgoing === undefined || outgoing.pending === null) return false;
      const pending = outgoing.pending;
      if (pending.messageId !== messageId || pending.to !== to || pending.digest !== hash) return false;
      if (this.crypto === null) throw new Error('State sender crypto unavailable');
      if (await this.crypto.sha256(pending.payload) !== pending.digest || outgoing.cycle === null ||
          await this.crypto.sha256(outgoing.cycle) !== outgoing.cycleHash) throw new Error('Corrupt state sender digest');
      const all = JSON.parse(outgoing.cycle) as CompanionStateEntry[];
      outgoing.position += body(pending.payload).entries.length;
      outgoing.acknowledged = to; outgoing.pending = null;
      if (outgoing.position === all.length) {
        outgoing.sentHash = outgoing.cycleHash; outgoing.cycle = null; outgoing.cycleHash = null; outgoing.position = 0;
      }
      return true;
    }, false);
  }
  /** payload must be the exact, strictly decoded UTF-8 JSON from a verified
   * state frame. The receipt and merged state are committed atomically.
   */
  receiveBatchAuthenticated(peer: string, messageId: string, payload: string): Promise<CompanionStateReceipt> {
    return this.transact(async stored => {
      identity(peer); identity(messageId);
      const parsed = body(payload);
      if (this.crypto === null) throw new Error('State receipt crypto unavailable');
      const hash = await this.crypto.sha256(payload); digest(hash);
      const state = stored.state;
      const current = Object.keys(state.cursors).includes(peer) ? state.cursors[peer] : 0;
      const prior = stored.incoming.find(value => value.peer === peer);
      const receipt = new CompanionStateReceipt(); receipt.cursor = parsed.to; receipt.digest = hash;
      if (parsed.to <= current) {
        if (prior === undefined || parsed.to !== current || prior.from !== parsed.from || prior.to !== parsed.to ||
            prior.messageId !== messageId || prior.digest !== hash) throw new Error('Conflicting or stale state batch replay');
        receipt.duplicate = true; return receipt;
      }
      if (parsed.from !== current) throw new Error('Sync sequence gap; request replay');
      if (prior !== undefined && prior.messageId === messageId) throw new Error('State batch identity reused');
      if (prior === undefined && stored.incoming.length >= 128) throw new Error('State receipt quota exceeded');
      this.merge(state, parsed.entries); setCursor(state, peer, parsed.to);
      const incoming = new IncomingState();
      incoming.peer = peer; incoming.from = parsed.from; incoming.to = parsed.to; incoming.messageId = messageId; incoming.digest = hash;
      stored.incoming = stored.incoming.filter(value => value.peer !== peer); stored.incoming.push(incoming);
      return receipt;
    }, true);
  }
  receiveAuthenticated(peer: string, from: number, to: number, entries: CompanionStateEntry[]): Promise<number> {
    let frozen: CompanionStateEntry[];
    try {
      identity(peer); counter(from); counter(to);
      if (to <= from || !Array.isArray(entries) || entries.length > 512) throw new Error('Invalid state range or batch');
      for (const entry of entries) validate(entry);
      frozen = JSON.parse(JSON.stringify(entries)) as CompanionStateEntry[];
    } catch (error) { return Promise.reject(error); }
    return this.transact(async stored => {
      if (stored.incoming.some(value => value.peer === peer)) throw new Error('Cannot mix state ingress APIs for a wire peer');
      const state = stored.state;
      // Enumerate own keys: peer identities such as __proto__ must not inherit
      // a cursor or mutate the object's prototype.
      const current = Object.keys(state.cursors).includes(peer) ? state.cursors[peer] : 0;
      if (to <= current) return current;
      if (from !== current) throw new Error('Sync sequence gap; request replay');
      this.merge(state, frozen);
      setCursor(state, peer, to);
      return to;
    }, true);
  }
  private write(key: string, value: Object | null, deleted: boolean): Promise<CompanionStateEntry> {
    let frozen: Object | null;
    try { identity(key); frozen = freezeValue(value); } catch (error) { return Promise.reject(error); }
    return this.mutate(state => {
      const entry = new CompanionStateEntry();
      entry.key = key; entry.value = frozen; entry.counter = state.clock + 1;
      entry.deviceId = this.deviceId; entry.deleted = deleted;
      this.merge(state, [entry]); return entry;
    });
  }
  private merge(state: CompanionStateSnapshot, entries: CompanionStateEntry[]): void {
    const table = new Map<string, CompanionStateEntry>();
    for (const entry of state.entries) table.set(entry.key, entry);
    for (const entry of entries) {
      validate(entry);
      const old = table.get(entry.key);
      if (old && compare(entry, old) === 0 && (entry.deleted !== old.deleted || canonical(entry.value) !== canonical(old.value))) {
        throw new Error('Conflicting payload for same state revision');
      }
      if (!old || compare(entry, old) > 0) table.set(entry.key, entry);
      state.clock = Math.max(state.clock, entry.counter);
    }
    if (table.size > 10000) throw new Error('State entry quota exceeded');
    state.entries = [];
    table.forEach((value: CompanionStateEntry) => { state.entries.push(value); });
    state.entries.sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
  }
  private load(raw: string | null): StoredState {
    if (raw === null) {
      const fresh = new StoredState(); fresh.appId = this.appId; fresh.localDeviceId = this.deviceId; return fresh;
    }
    if (bytes(raw) > 8 * 1024 * 1024) throw new Error('State snapshot quota exceeded');
    const stored = JSON.parse(raw) as StoredState;
    if (!stored || (stored.schema !== 1 && stored.schema !== 2 && stored.schema !== 3) || stored.appId !== this.appId || stored.localDeviceId !== this.deviceId || !stored.state) {
      throw new Error('State storage identity or schema mismatch');
    }
    const state = stored.state;
    if (state.version !== 1 || !Array.isArray(state.entries) || state.entries.length > 10000 ||
        !state.cursors || typeof state.cursors !== 'object' || Array.isArray(state.cursors)) throw new Error('Corrupt state snapshot');
    counter(state.clock);
    const keys = new Set<string>();
    for (const entry of state.entries) {
      validate(entry);
      if (keys.has(entry.key) || entry.counter > state.clock) throw new Error('Corrupt state snapshot');
      keys.add(entry.key);
    }
    if (Object.keys(state.cursors).length > 128) throw new Error('State peer quota exceeded');
    for (const peer of Object.keys(state.cursors)) { identity(peer); counter(state.cursors[peer]); }
    if (stored.schema === 1) {
      if (stored.outgoing !== undefined) throw new Error('Invalid legacy state sender fields');
      stored.outgoing = [];
    }
    if (!Array.isArray(stored.outgoing) || stored.outgoing.length > 128) throw new Error('Corrupt state sender');
    const peers = new Set<string>();
    for (const outgoing of stored.outgoing) {
      if (!outgoing) throw new Error('Corrupt state sender');
      identity(outgoing.peer); counter(outgoing.acknowledged); counter(outgoing.position);
      if (peers.has(outgoing.peer)) throw new Error('Duplicate state sender peer'); peers.add(outgoing.peer);
      if (outgoing.sentHash !== null) digest(outgoing.sentHash);
      if (outgoing.cycle === null) {
        if (outgoing.cycleHash !== null || outgoing.pending !== null || outgoing.position !== 0) throw new Error('Corrupt empty state cycle');
        continue;
      }
      if (typeof outgoing.cycle !== 'string') throw new Error('Corrupt state cycle');
      digest(outgoing.cycleHash as string);
      const all = JSON.parse(outgoing.cycle) as CompanionStateEntry[];
      if (!Array.isArray(all) || all.length > 10000 || outgoing.position > all.length) throw new Error('Corrupt state position');
      const cycleKeys = new Set<string>();
      for (const entry of all) {
        validate(entry);
        if (cycleKeys.has(entry.key) || entry.counter > state.clock) throw new Error('Corrupt state cycle entries'); cycleKeys.add(entry.key);
      }
      if (outgoing.pending !== null) {
        const pending = outgoing.pending; identity(pending.messageId); digest(pending.digest);
        const parsed = body(pending.payload);
        if (pending.from !== outgoing.acknowledged || pending.from !== parsed.from || pending.to !== parsed.to ||
            outgoing.position + parsed.entries.length > all.length ||
            (parsed.entries.length === 0 && all.length !== 0) ||
            JSON.stringify(parsed.entries) !== JSON.stringify(all.slice(outgoing.position, outgoing.position + parsed.entries.length))) throw new Error('Corrupt pending state batch');
      }
    }
    if (stored.schema < 3) {
      if (stored.incoming !== undefined) throw new Error('Invalid legacy state receipt fields');
      stored.incoming = [];
    }
    if (!Array.isArray(stored.incoming) || stored.incoming.length > 128) throw new Error('Corrupt state receipts');
    const receiptPeers = new Set<string>();
    for (const incoming of stored.incoming) {
      if (!incoming) throw new Error('Corrupt state receipt');
      identity(incoming.peer); identity(incoming.messageId); counter(incoming.from); counter(incoming.to); digest(incoming.digest);
      if (incoming.to !== incoming.from + 1 || receiptPeers.has(incoming.peer) ||
          !Object.keys(state.cursors).includes(incoming.peer) || state.cursors[incoming.peer] !== incoming.to) throw new Error('Corrupt state receipt cursor');
      receiptPeers.add(incoming.peer);
    }
    stored.schema = 3;
    return stored;
  }
  private mutate<T>(operation: (state: CompanionStateSnapshot) => T): Promise<T> {
    return this.transact(async stored => operation(stored.state), true);
  }
  private transact<T>(operation: (stored: StoredState) => Promise<T>, notify: boolean): Promise<T> {
    return this.serial(async () => {
      const raw = await this.port.read(this.appId), stored = this.load(raw);
      const result = await operation(stored);
      const desired = JSON.stringify(stored);
      if (bytes(desired) > 8 * 1024 * 1024 || Object.keys(stored.state.cursors).length > 128) throw new Error('State snapshot quota exceeded');
      if (!await this.port.compareExchange(this.appId, raw, desired)) throw new Error('State transaction conflict; reload and retry');
      if (notify) for (const listener of Array.from(this.listeners)) {
        try { listener(JSON.parse(JSON.stringify(stored.state)) as CompanionStateSnapshot); } catch (_) { /* Already committed. */ }
      }
      return result;
    });
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const task = this.tail.then(operation); this.tail = task.then(() => {}, () => {}); return task;
  }
}
