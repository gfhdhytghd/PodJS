import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { CompanionIncomingNativePort, IncomingNativeBridge, IncomingNativeRequest } from './CompanionIncomingNativePort';
import { CompanionOutgoingFiles } from './CompanionOutgoingFiles';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionImportCrypto, CompanionStreamingDigest } from './CompanionFileImporter';
import { NativeCompanionSyncCrypto } from './NativeCompanionSync';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';

class ImportDigest implements CompanionStreamingDigest {
  private digest: cryptoFramework.Md = cryptoFramework.createMd('SHA256');
  async update(bytes: Uint8Array): Promise<void> { await this.digest.update({ data: bytes }); }
  async finish(): Promise<Uint8Array> { return (await this.digest.digest()).data; }
}
export class NativeCompanionImportCrypto extends NativeCompanionSyncCrypto implements CompanionImportCrypto {
  streamingSha256(): CompanionStreamingDigest { return new ImportDigest(); }
}

class OutgoingBridge implements IncomingNativeBridge {
  incomingFilesOpen(root: string, app: string): Promise<Object> { return native.outgoingFilesOpen(root, app); }
  incomingFilesClose(lease: Object): void { native.incomingFilesClose(lease); }
  incomingFilesRun(lease: Object, request: IncomingNativeRequest): Promise<string | null | number[] | Uint8Array | void> {
    return native.incomingFilesRun(lease, request);
  }
}
export function createCompanionOutgoingFiles(context: common.UIAbilityContext, appId: string, transfers: CompanionOutgoingTransfers | null = null): CompanionOutgoingFiles {
  return new CompanionOutgoingFiles(appId, new CompanionIncomingNativePort(new OutgoingBridge(), context.filesDir, appId), transfers);
}
