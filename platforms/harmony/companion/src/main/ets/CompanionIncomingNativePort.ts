import { CompanionIncomingFilePort } from './CompanionIncomingFiles';
import { CompanionFileManifest } from './CompanionFileWire';

export class IncomingNativeRequest {
  method: string = '';
  text: string = '';
  peer: string = '';
  manifest: CompanionFileManifest = new CompanionFileManifest();
  index: number = 0;
  data: Uint8Array = new Uint8Array(0);
}
export interface IncomingNativeBridge {
  incomingFilesOpen(root: string, app: string): Promise<Object>;
  incomingFilesClose(lease: Object): void;
  incomingFilesRun(lease: Object, request: IncomingNativeRequest): Promise<string | null | number[] | Uint8Array | void>;
}
/** A lease spans the journal read, content mutation, and journal commit.
 * Concurrent transactions fail explicitly; callers may retry the whole operation. */
export class CompanionIncomingNativePort implements CompanionIncomingFilePort {
  private bridge: IncomingNativeBridge;
  private root: string;
  private app: string;
  private active: boolean = false;
  private lease: Object | null = null;
  constructor(bridge: IncomingNativeBridge, root: string, app: string) {
    this.bridge = bridge; this.root = root; this.app = app;
  }
  async exclusive<T>(work: () => Promise<T>): Promise<T> {
    if (this.active) throw new Error('incoming transaction already active');
    this.active = true;
    try {
      this.lease = await this.bridge.incomingFilesOpen(this.root, this.app);
      return await work();
    } finally {
      const lease = this.lease; this.lease = null; this.active = false;
      if (lease !== null) this.bridge.incomingFilesClose(lease);
    }
  }
  private run(request: IncomingNativeRequest): Promise<string | null | number[] | Uint8Array | void> {
    if (this.lease === null) throw new Error('incoming operation outside transaction');
    return this.bridge.incomingFilesRun(this.lease, request);
  }
  private request(method: string, peer: string, manifest: CompanionFileManifest): IncomingNativeRequest {
    const request = new IncomingNativeRequest(); request.method = method; request.peer = peer; request.manifest = manifest; return request;
  }
  async readJournal(): Promise<string | null> {
    const request = new IncomingNativeRequest(); request.method = 'readJournal';
    return await this.run(request) as string | null;
  }
  async writeJournal(value: string): Promise<void> {
    const request = new IncomingNativeRequest(); request.method = 'writeJournal'; request.text = value; await this.run(request);
  }
  async reserve(peer: string, manifest: CompanionFileManifest): Promise<void> { await this.run(this.request('reserve', peer, manifest)); }
  async remove(peer: string, manifest: CompanionFileManifest): Promise<void> { await this.run(this.request('remove', peer, manifest)); }
  async writeChunk(peer: string, manifest: CompanionFileManifest, index: number, bytes: Uint8Array): Promise<void> {
    const request = this.request('chunk', peer, manifest); request.index = index; request.data = bytes; await this.run(request);
  }
  async missing(peer: string, manifest: CompanionFileManifest): Promise<number[]> { return await this.run(this.request('missing', peer, manifest)) as number[]; }
  async finish(peer: string, manifest: CompanionFileManifest): Promise<void> { await this.run(this.request('finish', peer, manifest)); }
  async saveComplete(peer: string, manifest: CompanionFileManifest, path: string): Promise<void> {
    const request = this.request('saveComplete', peer, manifest); request.text = path; await this.run(request);
  }
  async readCompleteChunk(peer: string, manifest: CompanionFileManifest, index: number): Promise<Uint8Array> {
    const request = this.request('readCompleteChunk', peer, manifest); request.index = index;
    return await this.run(request) as Uint8Array;
  }
}
