import { CompanionOutgoingTransfer, CompanionOutgoingTransfers } from '@podjs/companion/src/main/ets/CompanionOutgoingTransfers';
import { CompanionFileIdentity } from '@podjs/companion/src/main/ets/CompanionFileIdentity';
import { CompanionFileManifest, validateFileManifest } from '@podjs/companion/src/main/ets/CompanionFileWire';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';
import { SyncFileEventDelivery } from './SyncFileEventDelivery';

export interface SyncOutgoingClient {
  readonly outgoingTransfers: CompanionOutgoingTransfers;
  resolveFileIdentity(id: string): Promise<CompanionFileIdentity | null>;
  queueGuestFile(peer: string, id: string, path: string, mime: string, cancelled: () => boolean): Promise<CompanionOutgoingTransfer>;
}
class Args { peerId?: string; path?: string; mime?: string; transferId?: string; }
class Operation {}
class Result {
  transferId: string;
  state: string;
  receivedBytes: number;
  totalBytes: number;
  progressKnown: boolean;
  cancelRequested: boolean;
  constructor(record: CompanionOutgoingTransfer) {
    this.transferId = record.manifest.transfer_id; this.totalBytes = record.manifest.size;
    this.state = record.phase === 'complete' ? 'complete' : record.phase === 'cancelled' ? 'cancelled' : record.progressKnown ? 'transferring' : 'offered';
    this.receivedBytes = record.acknowledgedChunks.reduce((bytes: number, index: number) => bytes + Math.min(65536, record.manifest.size - index * 65536), 0);
    this.progressKnown = record.progressKnown; this.cancelRequested = record.phase === 'cancel_requested';
  }
}
/** Local durable intent only; an authenticated foreground driver owns wire IO.
 * Outgoing cancel requires prior exposure and never claims peer cancellation. */
export class SyncOutgoingFileServices implements ServiceHandler, SyncFileEventDelivery {
  private active: Map<number, Operation> = new Map();
  private exposed: Map<string, string> = new Map();
  private eventsActive: boolean = false;
  private generation: number = 0;
  private pumping: boolean = false;
  private cursor: string = '';
  private emitted: Map<string, string> = new Map();
  constructor(private delegate: ServiceHandler, private client: () => Promise<SyncOutgoingClient>, private newId: () => Promise<string>) {}
  setEventsActive(value: boolean): void {
    if (this.eventsActive === value) return;
    this.eventsActive = value; this.generation++;
    if (!value) { this.cursor = ''; this.emitted.clear(); }
  }
  async pumpEvents(post: (json: string) => boolean): Promise<void> {
    if (!this.eventsActive || this.pumping) return;
    const generation = this.generation;
    const current = (): boolean => this.eventsActive && this.generation === generation;
    this.pumping = true;
    try {
      const client = await this.client(); if (!current()) return;
      const records = await client.outgoingTransfers.list(); if (!current()) return;
      const ids = records.map((record: CompanionOutgoingTransfer) => record.manifest.transfer_id).sort();
      const page = ids.filter((id: string) => id > this.cursor).slice(0, 64);
      for (const id of page) {
        if (!current()) return;
        const record = records.find((item: CompanionOutgoingTransfer) => item.manifest.transfer_id === id) as CompanionOutgoingTransfer;
        try {
          const identity = await client.resolveFileIdentity(id); if (!current()) return;
          if (identity === null || identity.direction !== 'outgoing' || identity.peer !== record.peer) { this.cursor = id; continue; }
        } catch (_) { if (!current()) return; this.cursor = id; continue; }
        const json = JSON.stringify({ t: 'sync.file.changed', value: new Result(record) });
        if (this.emitted.get(id) !== json) {
          if (!post(json)) return;
          if (!current()) return;
          this.emitted.set(id, json); this.exposed.set(id, record.peer);
        }
        this.cursor = id;
      }
      if (page.length < 64 || this.cursor === ids[ids.length - 1]) this.cursor = '';
    } finally { this.pumping = false; }
  }
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    const method = request.method;
    if (!['sync.files.offer', 'sync.files.status', 'sync.files.cancel'].includes(method)) { this.delegate.handle(request, complete); return; }
    const args = request.args as Args;
    try {
      if (!args) throw new Error('invalid arguments');
      if (method === 'sync.files.offer') {
        if (typeof args.peerId !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(args.peerId) ||
          typeof args.path !== 'string' || args.path.length === 0 || args.path.length > 1024 || typeof args.mime !== 'string') throw new Error('invalid offer');
        const manifest = new CompanionFileManifest(); manifest.transfer_id = 'validation'; manifest.mime = args.mime; manifest.sha256 = '0'.repeat(64);
        validateFileManifest(manifest);
      } else if (typeof args.transferId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(args.transferId)) throw new Error('invalid identity');
    } catch (_) {
      const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid file operation'; complete(reply); return;
    }
    const id = request.id, peer = args.peerId, path = args.path, mime = args.mime, transferId = args.transferId;
    const forwarded = new ServiceRequest(); forwarded.id = id; forwarded.t = request.t; forwarded.version = request.version;
    forwarded.method = method; forwarded.args = { transferId: transferId as string };
    const operation = new Operation(); this.active.set(id, operation);
    const current = (): boolean => this.active.get(id) === operation;
    Promise.resolve().then(async () => {
      if (!current()) return;
      const reply = new ServiceReply();
      try {
        const client = await this.client(); if (!current()) return;
        let record: CompanionOutgoingTransfer;
        if (method === 'sync.files.offer') {
          const fresh = await this.newId(); if (!current()) return;
          record = await client.queueGuestFile(peer as string, fresh, path as string, mime as string, () => !current());
          if (!current()) return;
          this.exposed.set(record.manifest.transfer_id, record.peer); reply.value = new Result(record);
        } else {
          const identity = await client.resolveFileIdentity(transferId as string); if (!current()) return;
          if (identity === null || identity.direction === 'incoming') {
            this.active.delete(id); this.delegate.handle(forwarded, complete); return;
          }
          const matches = (await client.outgoingTransfers.list()).filter((item: CompanionOutgoingTransfer) => item.manifest.transfer_id === transferId);
          if (!current()) return;
          if (matches.length !== 1 || matches[0].peer !== identity.peer) throw new Error('Outgoing identity changed');
          record = matches[0];
          if (method === 'sync.files.cancel') {
            if (this.exposed.get(transferId as string) !== record.peer) throw new Error('Outgoing transfer not exposed');
            if (record.phase !== 'cancelled') await client.outgoingTransfers.transition(record.peer, transferId as string, 'cancel_requested', () => !current());
          } else {
            this.exposed.set(transferId as string, record.peer); reply.value = new Result(record);
          }
        }
        reply.ok = true;
      } catch (_) { reply.code = 'host_error'; reply.message = 'Outgoing file operation failed'; }
      if (!current()) return;
      this.active.delete(id); complete(reply);
    });
  }
  cancel(id: number): void { this.active.delete(id); this.delegate.cancel(id); }
}
