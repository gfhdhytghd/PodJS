/** Byte-compatible with runtime sync_auth. Provision keys through authenticated
 * pairing and fresh OS-random challenges on every connection. No encryption. */
export interface CompanionSyncCrypto {
  sha256(bytes: Uint8Array): Promise<Uint8Array>;
  hmacSha256(key: Uint8Array, bytes: Uint8Array): Promise<Uint8Array>;
}

/** Host-only key handle bound to one approved app/local/peer identity. */
export interface CompanionSyncSigner {
  matchesIdentity(app: string, local: string, peer: string): boolean;
  sign(bytes: Uint8Array): Promise<Uint8Array>;
}
export function copyCompanionSyncKey(key: Uint8Array | CompanionSyncSigner, app: string, local: string, peer: string): Uint8Array | CompanionSyncSigner {
  if (key instanceof Uint8Array) {
    if (key.length !== 32 || !key.some((b: number) => b !== 0)) throw new Error('invalid pairing key');
    return key.slice();
  }
  if (!key || !key.matchesIdentity(app, local, peer)) throw new Error('pairing signer identity mismatch');
  return key;
}
export function clearCompanionSyncKey(key: Uint8Array | CompanionSyncSigner | null): void {
  if (key instanceof Uint8Array) key.fill(0);
}
export class CompanionSyncBinding {
  version: number = 1;
  app_id: string;
  initiator: string;
  responder: string;
  initiator_nonce: number[];
  responder_nonce: number[];
  constructor(appId: string, initiator: string, responder: string,
    initiatorNonce: number[], responderNonce: number[]) {
    this.app_id = appId; this.initiator = initiator; this.responder = responder;
    this.initiator_nonce = initiatorNonce.slice(); this.responder_nonce = responderNonce.slice();
  }
}

function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid sync identity');
}
function bytes(value: number[], length: number): void {
  if (!Array.isArray(value) || value.length !== length) throw new Error('invalid sync bytes');
  for (let i = 0; i < value.length; i++) {
    const b = value[i];
    if (!Number.isInteger(b) || b < 0 || b > 255) throw new Error('invalid sync bytes');
  }
}
function ascii(value: string): Uint8Array {
  const out = new Uint8Array(value.length);
  for (let i = 0; i < value.length; i++) {
    if (value.charCodeAt(i) > 127) throw new Error('non-ASCII protocol encoding');
    out[i] = value.charCodeAt(i);
  }
  return out;
}

/** Rebuild field order explicitly: serde struct order is part of the MAC. */
export function encodeSyncBinding(binding: CompanionSyncBinding): Uint8Array {
  if (binding.version !== 1) throw new Error('unsupported sync protocol');
  identity(binding.app_id); identity(binding.initiator); identity(binding.responder);
  if (binding.initiator === binding.responder) throw new Error('identical peers');
  bytes(binding.initiator_nonce, 32); bytes(binding.responder_nonce, 32);
  if (!binding.initiator_nonce.some((b: number) => b !== 0) ||
    !binding.responder_nonce.some((b: number) => b !== 0) ||
    binding.initiator_nonce.every((b: number, i: number) => b === binding.responder_nonce[i]))
    throw new Error('invalid sync challenge');
  const canonical = new CompanionSyncBinding(binding.app_id, binding.initiator, binding.responder,
    binding.initiator_nonce, binding.responder_nonce);
  return ascii(JSON.stringify(canonical));
}

function macInput(binding: Uint8Array, initiator: boolean, label: string, body: Uint8Array): Uint8Array {
  const domain = ascii(label);
  const out = new Uint8Array(domain.length + 4 + binding.length + 1 + body.length);
  out.set(domain);
  new DataView(out.buffer).setUint32(domain.length, binding.length, false);
  out.set(binding, domain.length + 4); out[domain.length + 4 + binding.length] = initiator ? 1 : 0;
  out.set(body, domain.length + 5 + binding.length);
  return out;
}

/** Owns a copy of the key, never caller storage. close() also invalidates work
 * already awaiting crypto. OS/VM internal crypto copies cannot be wiped here. */
export class CompanionSyncHandshake {
  private key: Uint8Array | CompanionSyncSigner;
  private binding: Uint8Array;
  private closed: boolean = false;
  private authenticating: boolean = false;
  private authenticated: boolean = false;
  private crypto: CompanionSyncCrypto;
  private initiator: boolean;
  constructor(key: Uint8Array | CompanionSyncSigner, binding: CompanionSyncBinding, initiator: boolean, crypto: CompanionSyncCrypto) {
    this.binding = encodeSyncBinding(binding);
    this.key = copyCompanionSyncKey(key, binding.app_id, initiator ? binding.initiator : binding.responder, initiator ? binding.responder : binding.initiator);
    this.crypto = crypto; this.initiator = initiator;
  }
  close(): void { this.closed = true; clearCompanionSyncKey(this.key); }
  protected check(): void { if (this.closed) throw new Error('sync handshake closed'); }
  protected async mac(initiator: boolean, label: string, body: Uint8Array): Promise<Uint8Array> {
    this.check(); const temporary = this.key instanceof Uint8Array ? this.key.slice() : null;
    try {
      const input = macInput(this.binding, initiator, label, body);
      const result = temporary !== null ? await this.crypto.hmacSha256(temporary, input) : await (this.key as CompanionSyncSigner).sign(input);
      this.check();
      if (result.length !== 32) throw new Error('invalid HMAC result');
      return result.slice();
    } finally { if (temporary !== null) temporary.fill(0); }
  }
  private proofFor(initiator: boolean): Promise<Uint8Array> {
    return this.mac(initiator, 'PodJS-handshake-v1', new Uint8Array(0));
  }
  proof(): Promise<Uint8Array> { return this.proofFor(this.initiator); }
  /** Only the opposite role's proof is accepted. A failed authentication consumes
   * this handshake; retry requires a new object with fresh challenges. */
  async authenticate(remoteProof: Uint8Array): Promise<string> {
    this.check();
    if (this.authenticating || this.authenticated) throw new Error('authentication already started');
    this.authenticating = true;
    const received = remoteProof.slice();
    try {
      if (received.length !== 32) throw new Error('invalid peer proof');
      const expected = await this.proofFor(!this.initiator);
      let different = 0;
      for (let i = 0; i < 32; i++) different |= expected[i] ^ received[i];
      if (different !== 0) throw new Error('peer authentication failed');
      const digest = await this.crypto.sha256(this.binding.slice());
      this.check();
      if (digest.length !== 32) throw new Error('invalid SHA256 result');
      this.authenticated = true;
      let id = ''; for (const byte of digest) id += byte.toString(16).padStart(2, '0');
      return id;
    } catch (error) { this.close(); throw error; }
  }
}

export class CompanionSyncFrame {
  protocolVersion: number = 1;
  sessionId: string;
  sequence: number;
  channel: string;
  messageId: string;
  payload: number[];
  tag: number[] = [];
  constructor(sessionId: string, sequence: number, channel: string, messageId: string, payload: number[]) {
    this.sessionId = sessionId; this.sequence = sequence; this.channel = channel;
    this.messageId = messageId; this.payload = payload.slice();
  }
}
export class CompanionSyncDelivery {
  delivery: string;
  acknowledged: number;
  constructor(delivery: string, acknowledged: number) { this.delivery = delivery; this.acknowledged = acknowledged; }
}
function channelValid(channel: string): boolean {
  return ['state', 'message', 'file', 'ack'].includes(channel);
}
function frameBody(frame: CompanionSyncFrame): Uint8Array {
  if (frame.protocolVersion !== 1 || !/^[0-9a-f]{64}$/.test(frame.sessionId) ||
    !Number.isSafeInteger(frame.sequence) || frame.sequence < 1 || !channelValid(frame.channel))
    throw new Error('invalid sync frame');
  identity(frame.messageId);
  if (frame.payload.length > 263168) throw new Error('frame payload too large');
  bytes(frame.payload, frame.payload.length);
  // Avoid heterogeneous tuple typing in ArkTS while preserving serde tuple bytes.
  return ascii('[' + frame.protocolVersion + ',' + JSON.stringify(frame.sessionId) + ',' + frame.sequence + ',' +
    JSON.stringify(frame.channel) + ',' + JSON.stringify(frame.messageId) + ',' + JSON.stringify(frame.payload) + ']');
}

/** Serialized host session. Retain exact sent frames for retransmission. Verify
 * does not acknowledge storage; commit only after the durable transaction. */
export class CompanionSyncSession extends CompanionSyncHandshake {
  private boundApp: string;
  private boundLocal: string;
  private boundPeer: string;
  private role: boolean;
  private grants: string[];
  private sid: string = '';
  private sent: number = 0;
  private received: number = 0;
  private pendingSequence: number = 0;
  private pendingTag: string = '';
  private tail: Promise<void> = Promise.resolve();
  constructor(key: Uint8Array | CompanionSyncSigner, binding: CompanionSyncBinding, initiator: boolean,
    grants: string[], crypto: CompanionSyncCrypto) {
    super(key, binding, initiator, crypto);
    this.boundApp = binding.app_id;
    this.boundLocal = initiator ? binding.initiator : binding.responder;
    this.boundPeer = initiator ? binding.responder : binding.initiator;
    if (grants.length < 1 || grants.length > 4 || !grants.every((c: string) => channelValid(c))) {
      this.close(); throw new Error('invalid channel grants');
    }
    this.role = initiator; this.grants = grants.slice();
  }
  appId(): string { return this.boundApp; }
  localId(): string { return this.boundLocal; }
  peerId(): string { return this.boundPeer; }
  allowsChannel(channel: string): boolean { this.ready(); return this.grants.includes(channel); }
  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const result = this.tail.then(() => { this.check(); return work(); });
    this.tail = result.then(() => {}, () => {}); return result;
  }
  async authenticate(proof: Uint8Array): Promise<string> {
    const copy = proof.slice();
    return this.enqueue(async () => { const id = await super.authenticate(copy); this.sid = id; return id; });
  }
  private ready(): void { this.check(); if (!this.sid) throw new Error('handshake required'); }
  async send(channel: string, messageId: string, payload: number[]): Promise<CompanionSyncFrame> {
    const copy = payload.slice();
    return this.enqueue(async () => {
      this.ready();
      if (!this.grants.includes(channel)) throw new Error('channel not authorized');
      const frame = new CompanionSyncFrame(this.sid, this.sent + 1, channel, messageId, copy);
      const tag = await this.mac(this.role, 'PodJS-frame-v1', frameBody(frame));
      frame.tag = Array.from(tag); this.sent = frame.sequence; return frame;
    });
  }
  async verify(frame: CompanionSyncFrame): Promise<CompanionSyncDelivery> {
    let copy: CompanionSyncFrame;
    try {
      const fields = ['protocolVersion', 'sessionId', 'sequence', 'channel', 'messageId', 'payload', 'tag'];
      if (Object.keys(frame).length !== fields.length || !Object.keys(frame).every((k: string) => fields.includes(k)))
        throw new Error('invalid frame fields');
      frameBody(frame); bytes(frame.tag, 32);
      copy = new CompanionSyncFrame(frame.sessionId, frame.sequence, frame.channel, frame.messageId, frame.payload);
      copy.protocolVersion = frame.protocolVersion; copy.tag = frame.tag.slice();
    } catch (error) { this.close(); throw error; }
    return this.enqueue(async () => {
      this.ready();
      try {
        if (copy.sessionId !== this.sid) throw new Error('session mismatch');
        const expected = await this.mac(!this.role, 'PodJS-frame-v1', frameBody(copy));
        let different = 0; for (let i = 0; i < 32; i++) different |= expected[i] ^ copy.tag[i];
        if (different !== 0) throw new Error('frame authentication failed');
        if (!this.grants.includes(copy.channel)) throw new Error('channel not authorized');
        if (copy.sequence <= this.received) return new CompanionSyncDelivery('duplicate', this.received);
        if (copy.sequence !== this.received + 1) throw new Error('sequence gap');
        const tag = JSON.stringify(copy.tag);
        if (this.pendingSequence !== 0 && (this.pendingSequence !== copy.sequence || this.pendingTag !== tag))
          throw new Error('pending sequence payload changed');
        this.pendingSequence = copy.sequence; this.pendingTag = tag;
        return new CompanionSyncDelivery('pending', this.received);
      } catch (error) { this.close(); throw error; }
    });
  }
  commit(sequence: number): Promise<number> {
    return this.enqueue(async () => {
      this.ready();
      if (this.pendingSequence === 0 || sequence !== this.pendingSequence) throw new Error('no verified pending frame');
      this.received = sequence; this.pendingSequence = 0; this.pendingTag = ''; return this.received;
    });
  }
}
