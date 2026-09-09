import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { CompanionMessageStore } from './CompanionMessageOutbox';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';

export class NativeCompanionOutgoingTransfersStore implements CompanionMessageStore {
  private filesDir: string;
  private app: string;
  constructor(context: common.UIAbilityContext, app: string) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(app)) throw new Error('invalid outgoing transfer app');
    this.filesDir = context.filesDir; this.app = app;
  }
  read(): Promise<string | null> { return native.companionOutgoingTransfersRead(this.filesDir, this.app); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionOutgoingTransfersCompareExchange(this.filesDir, this.app, expected, desired);
  }
}
export function createCompanionOutgoingTransfers(context: common.UIAbilityContext, app: string, local: string): CompanionOutgoingTransfers {
  return new CompanionOutgoingTransfers(app, local, new NativeCompanionOutgoingTransfersStore(context, app));
}
