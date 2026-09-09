import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { SyncStateEvents } from './SyncStateEvents';
import { nativeInstalledSyncState } from './NativeInstalledSync';

let abilityVisible: boolean = false;
let pageVisible: boolean = false;
let context: common.UIAbilityContext | null = null;
let delivery: SyncStateEvents | null = null;
let timer: number = -1;
const encoder: util.TextEncoder = new util.TextEncoder();
function allowed(): boolean {
  return abilityVisible && pageVisible && context !== null && podjs.hasCapability('companion.sync.state');
}
function stop(): void {
  delivery?.close(); delivery = null;
  if (timer !== -1) { clearInterval(timer); timer = -1; }
}
function tick(): void {
  delivery?.pump().catch(() => { console.error('PodJS state delivery deferred'); });
}
export function refreshSyncStateDelivery(): void {
  if (!allowed()) { stop(); return; }
  if (delivery !== null) return;
  const ownerContext = context;
  if (ownerContext === null) return;
  delivery = new SyncStateEvents(() => nativeInstalledSyncState(ownerContext), allowed,
    (json: string) => podjs.postEvent(encoder.encodeInto(json)));
  tick(); timer = setInterval(tick, 1000);
}
export function attachSyncStateDelivery(value: common.UIAbilityContext): void {
  stop(); context = value; refreshSyncStateDelivery();
}
export function detachSyncStateDelivery(): void { stop(); context = null; pageVisible = false; }
export function syncStateAbilityVisible(value: boolean): void { abilityVisible = value; refreshSyncStateDelivery(); }
export function syncStatePageVisible(value: boolean): void { pageVisible = value; refreshSyncStateDelivery(); }
