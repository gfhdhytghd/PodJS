import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { CompanionFileSource } from './CompanionFileSender';
import { IncomingNativeRequest } from './CompanionIncomingNativePort';

/** Host-private source. Keep the lease only for import; it pauses guest frames
 * via the shared IO lock. Closing during a read releases after worker cleanup. */
export class NativeCompanionGuestFile implements CompanionFileSource {
  readonly size: number;
  private lease: Object | null;
  constructor(lease: Object, size: number) { this.lease = lease; this.size = size; }
  async readChunk(index: number): Promise<Uint8Array> {
    const lease = this.lease;
    if (lease === null) throw new Error('Guest source closed');
    const request = new IncomingNativeRequest(); request.method = 'readGuestChunk'; request.index = index;
    const bytes = await native.incomingFilesRun(lease, request) as Uint8Array;
    if (this.lease !== lease) throw new Error('Guest source closed'); return bytes;
  }
  close(): void {
    const lease = this.lease; this.lease = null;
    if (lease !== null) native.incomingFilesClose(lease);
  }
}
export async function openCompanionGuestFile(context: common.UIAbilityContext, path: string): Promise<NativeCompanionGuestFile> {
  const lease = await native.guestFileOpen(context.filesDir, path);
  try { return new NativeCompanionGuestFile(lease, native.guestFileSize(lease)); }
  catch (_) { native.incomingFilesClose(lease); throw new Error('Cannot inspect guest source'); }
}
