import { CompanionMessageOutbox, encodeCompanionJson, encodeStateUtf8 } from './CompanionMessages';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';
import { SyncMessageInbox } from './SyncMessageInbox';

class MessageInput {
  messageId: string = '';
  ttlMs: number = 0;
  priority: string = '';
  payload?: Object | null;
}
class SendArgs { peerId: string = ''; message: MessageInput = new MessageInput(); }
class AckArgs { peerId: string = ''; messageId: string = ''; }
class SendResult { messageId: string; state: string = 'queued'; constructor(id: string) { this.messageId = id; } }
class MessageOperation {}
function identity(value: string): boolean { return typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value); }

/** Local durable queue only. Network delivery and business ACK are separate.
 * Installed owner identity is supplied by the host, never by request arguments.
 */
export class SyncMessageServices implements ServiceHandler {
  private active: Map<number, MessageOperation> = new Map();
  constructor(private delegate: ServiceHandler, private outbox: () => Promise<CompanionMessageOutbox>,
    private now: () => number, private inbox: SyncMessageInbox | null = null) {}
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (request.method === 'sync.messages.ack' && this.inbox !== null) { this.acknowledge(request, complete); return; }
    if (request.method !== 'sync.messages.send') { this.delegate.handle(request, complete); return; }
    const args = request.args as SendArgs;
    let payload: Uint8Array;
    try {
      if (!args || !identity(args.peerId) || !args.message || !identity(args.message.messageId) ||
        !Number.isSafeInteger(args.message.ttlMs) || args.message.ttlMs <= 0 ||
        !['normal', 'high'].includes(args.message.priority) || args.message.payload === undefined) throw new Error('Invalid message');
      payload = new Uint8Array(encodeStateUtf8(encodeCompanionJson(args.message.payload)));
    } catch (_) {
      const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid sync message arguments'; complete(reply); return;
    }
    const peer = args.peerId, messageId = args.message.messageId, ttl = args.message.ttlMs;
    const high = args.message.priority === 'high', id = request.id, operation = new MessageOperation();
    this.active.set(id, operation);
    Promise.resolve().then(async () => {
      const current = (): boolean => this.active.get(id) === operation;
      if (!current()) return;
      const reply = new ServiceReply();
      try {
        const outbox = await this.outbox(); if (!current()) return;
        await outbox.enqueueWithTtl(peer, messageId, payload, ttl, high, this.now());
        reply.ok = true; reply.value = new SendResult(messageId);
      } catch (_) { reply.code = 'host_error'; reply.message = 'Sync message operation failed'; }
      if (!current()) return;
      this.active.delete(id); complete(reply);
    });
  }
  private acknowledge(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    const args = request.args as AckArgs, inbox = this.inbox;
    if (!args || !identity(args.peerId) || !identity(args.messageId) || inbox === null) {
      const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid message acknowledgement'; complete(reply); return;
    }
    const peer = args.peerId, messageId = args.messageId, id = request.id, operation = new MessageOperation();
    this.active.set(id, operation);
    Promise.resolve().then(async () => {
      const current = (): boolean => this.active.get(id) === operation;
      if (!current()) return;
      const reply = new ServiceReply();
      try { await inbox.acknowledge(peer, messageId, current); reply.ok = true; }
      catch (_) { reply.code = 'host_error'; reply.message = 'Sync message acknowledgement failed'; }
      if (!current()) return;
      this.active.delete(id); complete(reply);
    });
  }
  cancel(id: number): void { this.active.delete(id); this.delegate.cancel(id); }
}
