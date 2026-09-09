import { CompanionIncomingFiles, CompanionIncomingFileOffer, CompanionIncomingFileStatus } from '@podjs/companion/src/main/ets/CompanionIncomingFiles';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';
import { SyncFileEventDelivery } from './SyncFileEventDelivery';

class FileArgs { transferId: string = ''; path?: string; }
class SaveResult { path: string; size: number; constructor(path: string, size: number) { this.path = path; this.size = size; } }
class FileOperation {}
class FileResult {
  transferId: string = '';
  state: string = '';
  receivedBytes: number = 0;
  totalBytes: number = 0;
  constructor(status: CompanionIncomingFileStatus) {
    this.transferId = status.manifest.transfer_id; this.totalBytes = status.manifest.size;
    this.receivedBytes = status.receivedBytes;
    this.state = status.phase === 'offered' ? 'offered' : status.phase === 'complete' ? 'complete' :
      ['cancelled', 'cancelling'].includes(status.phase) ? 'cancelled' : 'transferring';
  }
}
class FileEvent {
  t: string = 'sync.file.changed';
  value: FileResult;
  constructor(status: CompanionIncomingFileStatus) { this.value = new FileResult(status); }
}
/** Incoming half only. Unknown/outgoing IDs are never guessed from guest peer
 * arguments; accept/cancel require an unambiguous prior status exposure. */
export class SyncIncomingFileServices implements ServiceHandler, SyncFileEventDelivery {
  private active: Map<number, FileOperation> = new Map();
  private exposed: Map<string, string> = new Map();
  private eventsActive: boolean = false;
  private eventGeneration: number = 0;
  private pumping: boolean = false;
  private cursor: string = '';
  private emitted: Map<string, string> = new Map();
  constructor(private delegate: ServiceHandler, private files: () => Promise<CompanionIncomingFiles>,
    private verifyIdentity: (id: string, peer: string) => Promise<void> = async () => {}) {}
  setEventsActive(active: boolean): void {
    if (this.eventsActive === active) return;
    this.eventsActive = active; this.eventGeneration++;
    if (!active) { this.cursor = ''; this.emitted.clear(); }
  }
  async pumpEvents(post: (json: string) => boolean): Promise<void> {
    if (!this.eventsActive || this.pumping) return;
    const generation = this.eventGeneration;
    const current = (): boolean => this.eventsActive && generation === this.eventGeneration;
    this.pumping = true;
    try {
      const files = await this.files(); if (!current()) return;
      const offers = await files.listLocal(); if (!current()) return;
      const ids = Array.from(new Set(offers.map((offer: CompanionIncomingFileOffer) => offer.manifest.transfer_id))).sort();
      const page = ids.filter((id: string) => id > this.cursor).slice(0, 64);
      for (const id of page) {
        if (!current()) return;
        const matches = offers.filter((offer: CompanionIncomingFileOffer) => offer.manifest.transfer_id === id);
        if (matches.length !== 1) { this.cursor = id; continue; }
        const peer = matches[0].peer;
        let status: CompanionIncomingFileStatus;
        try { await this.verifyIdentity(id, peer); if (!current()) return; status = await files.statusLocal(peer, id); }
        catch (_) { if (!current()) return; this.cursor = id; continue; }
        if (!current()) return;
        const json = JSON.stringify(new FileEvent(status));
        if (this.emitted.get(id) !== json) {
          if (!post(json)) return;
          if (!current()) return;
          this.emitted.set(id, json); this.exposed.set(id, peer);
        }
        this.cursor = id;
      }
      if (page.length < 64 || this.cursor === ids[ids.length - 1]) this.cursor = '';
    } finally { this.pumping = false; }
  }
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (!['sync.files.status', 'sync.files.accept', 'sync.files.cancel', 'sync.files.save'].includes(request.method)) {
      this.delegate.handle(request, complete); return;
    }
    const args = request.args as FileArgs;
    if (!args || typeof args.transferId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(args.transferId) ||
      (request.method === 'sync.files.save' && (typeof args.path !== 'string' || args.path.length === 0 || args.path.length > 1024))) {
      const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid file transfer identity'; complete(reply); return;
    }
    const transferId = args.transferId, path = args.path, id = request.id, method = request.method, operation = new FileOperation();
    this.active.set(id, operation);
    Promise.resolve().then(async () => {
      const current = (): boolean => this.active.get(id) === operation;
      if (!current()) return;
      const reply = new ServiceReply();
      try {
        const files = await this.files(); if (!current()) return;
        const matches = (await files.listLocal()).filter((offer: CompanionIncomingFileOffer) => offer.manifest.transfer_id === transferId);
        if (!current()) return;
        if (matches.length !== 1) throw new Error('Unknown or ambiguous file transfer');
        const peer = matches[0].peer;
        await this.verifyIdentity(transferId, peer); if (!current()) return;
        if (method !== 'sync.files.status' && this.exposed.get(transferId) !== peer) throw new Error('File transfer not exposed');
        if (method === 'sync.files.accept') await files.acceptLocal(peer, transferId);
        if (method === 'sync.files.cancel') await files.cancelUnfinishedLocal(peer, transferId);
        if (method === 'sync.files.save') {
          await files.saveCompleteLocal(peer, transferId, path as string);
          reply.value = new SaveResult(path as string, matches[0].manifest.size);
        }
        if (!current()) return;
        if (method !== 'sync.files.cancel' && method !== 'sync.files.save') {
          const status = await files.statusLocal(peer, transferId); if (!current()) return;
          reply.value = new FileResult(status); this.exposed.set(transferId, peer);
        }
        reply.ok = true;
      } catch (_) { reply.code = 'host_error'; reply.message = 'Incoming file operation failed'; }
      if (!current()) return;
      this.active.delete(id); complete(reply);
    });
  }
  cancel(id: number): void { this.active.delete(id); this.delegate.cancel(id); }
}
