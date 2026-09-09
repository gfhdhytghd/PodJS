import { CompanionMessageInbox, CompanionIncomingMessage, decodeStateUtf8, encodeCompanionJson } from './CompanionMessages';

class MessageReceipt {
  digest: Uint8Array;
  expires: number;
  constructor(row: CompanionIncomingMessage) { this.digest = row.digest.slice(); this.expires = row.envelope.expiresAt; }
}
class GuestMessage {
  messageId: string = '';
  payload: Object | null = null;
  ttlMs: number = 0;
  priority: string = 'normal';
}
class GuestDelivery { peerId: string = ''; message: GuestMessage = new GuestMessage(); }
class MessageEvent { t: string = 'sync.message.received'; value: GuestDelivery = new GuestDelivery(); }

/** JSON.parse validates grammar; scan decoded keys at every nesting level to
 * reject duplicate aliases instead of silently changing authenticated content. */
function payload(bytes: Uint8Array): Object | null {
  const text = decodeStateUtf8(Array.from(bytes));
  const value = JSON.parse(text) as Object | null;
  const stack: Set<string>[] = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') {
      const start = i++;
      for (; i < text.length; i++) { if (text[i] === '\\') { i++; continue; } if (text[i] === '"') break; }
      let next = i + 1; while (next < text.length && /\s/.test(text[next])) next++;
      if (text[next] === ':') {
        const key = JSON.parse(text.slice(start, i + 1)) as string;
        const keys = stack[stack.length - 1];
        if (!keys || keys.has(key)) throw new Error('Duplicate message field'); keys.add(key);
      }
    } else if (text[i] === '{' || text[i] === '[') {
      if (stack.length >= 33) throw new Error('Message nesting limit'); stack.push(new Set());
    } else if (text[i] === '}' || text[i] === ']') stack.pop();
  }
  encodeCompanionJson(value); return value;
}

export class SyncMessageInbox {
  private active: boolean = false;
  private generation: number = 0;
  private pumping: boolean = false;
  private offset: number = 0;
  private receipts: Map<string, MessageReceipt> = new Map();
  constructor(private inbox: () => Promise<CompanionMessageInbox>, private now: () => number,
    private post: (json: string) => boolean, private notifyAck: (delivery: CompanionIncomingMessage) => Promise<void> = async () => {}) {}
  setActive(value: boolean): void {
    if (this.active === value) return;
    this.active = value; this.generation++;
    if (!value) { this.receipts.clear(); this.offset = 0; }
  }
  async pump(): Promise<void> {
    if (!this.active || this.pumping) return;
    const generation = this.generation;
    const current = (): boolean => this.active && this.generation === generation;
    this.pumping = true;
    try {
      const inbox = await this.inbox(); if (!current()) return;
      const rows = await inbox.pending(this.now(), 100, this.offset); if (!current()) return;
      for (const row of rows) {
        if (!current()) return;
        const now = this.now();
        this.receipts.forEach((receipt: MessageReceipt, key: string) => { if (receipt.expires <= now) this.receipts.delete(key); });
        if (row.envelope.expiresAt <= now) continue;
        const key = row.peer + '/' + row.messageId;
        if (!this.receipts.has(key) && this.receipts.size >= 1000) continue;
        let event: MessageEvent;
        try {
          event = new MessageEvent(); event.value.peerId = row.peer;
          event.value.message.messageId = row.messageId; event.value.message.payload = payload(row.envelope.payload);
          event.value.message.ttlMs = row.envelope.expiresAt - now;
          event.value.message.priority = row.envelope.highPriority ? 'high' : 'normal';
        } catch (_) { continue; }
        const receipt = new MessageReceipt(row);
        if (!this.post(JSON.stringify(event))) return;
        if (!current()) return;
        this.receipts.set(key, receipt);
      }
      this.offset = rows.length < 100 || this.offset + 100 >= 1000 ? 0 : this.offset + 100;
    } finally { this.pumping = false; }
  }
  async acknowledge(peer: string, id: string, current: () => boolean): Promise<void> {
    const generation = this.generation;
    const allowed = (): boolean => this.active && this.generation === generation && current();
    if (!allowed()) throw new Error('Message delivery inactive');
    const receipt = this.receipts.get(peer + '/' + id);
    if (!receipt || receipt.expires <= this.now()) throw new Error('Message not exposed to guest');
    const inbox = await this.inbox(); if (!allowed()) throw new Error('Message delivery cancelled');
    const applied = await inbox.acknowledge(peer, id, receipt.digest, this.now());
    // Keep the exact receipt until expiry for lost service-result retries.
    // A closed/full/failed transport does not undo this committed business ACK.
    if (allowed()) { try { this.notifyAck(applied).catch(() => {}); } catch (_) {} }
  }
}
