import { CompanionFileRequests } from './CompanionFileRequests';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';
import { CompanionFileSender } from './CompanionFileSender';
import { CompanionFileManifest, decodeFileRequest } from './CompanionFileWire';

export interface CompanionRegisteredFileClient {
  readonly fileRequests: CompanionFileRequests;
  readonly outgoingTransfers: CompanionOutgoingTransfers;
  createRegisteredFileSender(peer: string, id: string): Promise<CompanionFileSender>;
}
export interface CompanionRegisteredFileTransport {
  fileStatus(): string;
  driveFile(sender: CompanionFileSender): Promise<string>;
}
/** Borrowed authenticated foreground transport only. Host paces step (including
 * consent checks); this class never connects, reconnects, extends a deadline or
 * owns transport shutdown. close suppresses subsequent and late selections. */
export class CompanionRegisteredFileDriver {
  private closed: boolean = false;
  private busy: boolean = false;
  private sender: CompanionFileSender | null = null;
  constructor(private client: CompanionRegisteredFileClient, private transport: CompanionRegisteredFileTransport, private peer: string,
    private cleanup: ((cancelled: () => boolean) => Promise<boolean>) | null = null) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(peer)) throw new Error('invalid file driver peer');
  }
  close(): void { this.closed = true; this.sender = null; }
  async step(): Promise<string> {
    if (this.closed) throw new Error('file driver closed');
    if (this.busy) throw new Error('file driver busy'); this.busy = true;
    const check = (): void => { if (this.closed) throw new Error('file driver closed'); };
    try {
      const state = this.transport.fileStatus();
      if (state === 'complete' || state === 'cancelled') this.sender = null;
      if (this.sender === null) {
        const pending = await this.client.fileRequests.next(this.peer); check();
        const completed = await this.client.fileRequests.completed(this.peer); check();
        const records = await this.client.outgoingTransfers.list(); check();
        const requests = pending === null ? completed : [pending].concat(completed);
        if (requests.length > 1) throw new Error('file driver requires exclusive peer queue');
        let id: string | null = null;
        if (requests.length === 1) {
          const request = decodeFileRequest(requests[0].payload);
          id = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
          if (!records.some(record => record.peer === this.peer && record.manifest.transfer_id === id)) throw new Error('file driver request not registered');
        } else {
          if (this.cleanup !== null) { await this.cleanup(() => this.closed); check(); }
          const first = records.find(record => record.peer === this.peer && !['complete', 'cancelled'].includes(record.phase));
          if (first !== undefined) id = first.manifest.transfer_id;
        }
        if (id === null) return 'idle';
        const sender = await this.client.createRegisteredFileSender(this.peer, id); check(); this.sender = sender;
      }
      check(); const result = await this.transport.driveFile(this.sender); check();
      if (result === 'complete' || result === 'cancelled') this.sender = null;
      return result;
    } finally { this.busy = false; }
  }
}
