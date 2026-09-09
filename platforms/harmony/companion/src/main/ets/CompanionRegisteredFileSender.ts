import { CompanionFileSender, CompanionFileSource } from './CompanionFileSender';
import { CompanionFileRequests } from './CompanionFileRequests';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';
import { CompanionMessageDigest } from './CompanionMessageOutbox';
import { CompanionFileManifest, CompanionFileRequest, decodeFileRequest } from './CompanionFileWire';
import { decodeFileReply } from './CompanionFileReply';

function fingerprint(manifest: CompanionFileManifest): string {
  return JSON.stringify([manifest.transfer_id, manifest.size, manifest.sha256, manifest.mime, manifest.chunk_hashes]);
}
/** One authenticated foreground driver owns this peer queue. Durable terminal
 * registration precedes receipt consumption, so restart cannot reoffer a file
 * after its completion receipt was cleared. Source copies are not removed. */
export class CompanionRegisteredFileSender extends CompanionFileSender {
  private running: boolean = false;
  private expected: string;
  private stable: CompanionFileManifest;
  constructor(private queue: CompanionFileRequests, private target: string, manifest: CompanionFileManifest,
    source: CompanionFileSource, crypto: CompanionMessageDigest, private registry: CompanionOutgoingTransfers) {
    super(queue, target, manifest, source, crypto);
    this.expected = fingerprint(manifest);
    this.stable = JSON.parse(JSON.stringify(manifest)) as CompanionFileManifest;
  }
  private checkRequest(request: CompanionFileRequest): void {
    const id = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
    if (id !== this.stable.transfer_id || (request.method === 'offer' && fingerprint(request.manifest as CompanionFileManifest) !== this.expected))
      throw new Error('registered sender queue identity mismatch');
  }
  async step(cancelled: () => boolean = () => false): Promise<string> {
    if (this.running) throw new Error('registered sender already busy'); this.running = true;
    const check = (): void => { if (cancelled()) throw new Error('registered sender cancelled'); };
    try {
      check();
      const records = (await this.registry.list()).filter(record => record.manifest.transfer_id === this.stable.transfer_id); check();
      if (records.length !== 1 || records[0].peer !== this.target || fingerprint(records[0].manifest) !== this.expected) throw new Error('registered sender identity mismatch');
      const record = records[0];
      const pending = await this.queue.next(this.target); check();
      const completed = await this.queue.completed(this.target); check();
      if (pending !== null) this.checkRequest(decodeFileRequest(pending.payload));
      if (completed.length > 1 || (pending !== null && completed.length !== 0)) throw new Error('registered sender requires exclusive queue');
      for (const item of completed) this.checkRequest(decodeFileRequest(item.payload));
      const receipt = await this.queue.terminal(this.target); check();
      if (receipt !== null) {
        if (receipt.transferId !== this.stable.transfer_id) throw new Error('registered receipt identity mismatch');
        await this.registry.transition(this.target, receipt.transferId, receipt.phase, cancelled); check();
        if (!await this.queue.consumeTerminal(receipt)) throw new Error('registered receipt changed');
        check(); return receipt.phase;
      }
      if (record.phase === 'complete' || record.phase === 'cancelled') {
        if (pending !== null || completed.length !== 0) throw new Error('terminal registry has nonterminal requests');
        return record.phase;
      }
      if (completed.length === 1) {
        const observation = completed[0], request = decodeFileRequest(observation.payload);
        const value = decodeFileReply(observation.reply as Uint8Array, request, observation.digest).value;
        if (request.method === 'missing' && value.phase === 'accepted')
          await this.registry.observeMissing(this.target, this.stable.transfer_id, value.missing as number[], cancelled);
        if (request.method === 'chunk' && value.phase === 'accepted')
          await this.registry.observeChunk(this.target, this.stable.transfer_id, request.index, cancelled);
        check();
      }
      if (record.phase !== 'cancel_requested') return await super.step(cancelled);
      if (pending !== null) return 'awaiting_reply';
      const request = new CompanionFileRequest(); request.transfer_id = this.stable.transfer_id;
      if (completed.length === 0) {
        // A missing queue can mean never offered or a crash between requests.
        // Establish the idempotent offer before cancelling an unknown peer ID.
        request.method = 'offer'; request.manifest = this.stable;
      } else {
        request.method = 'cancel';
        if (!await this.queue.forgetCompleted(this.target, completed[0].messageId, cancelled)) throw new Error('registered observation changed');
      }
      check(); await this.queue.enqueue(this.target, request, cancelled); return 'queued';
    } finally { this.running = false; }
  }
}
