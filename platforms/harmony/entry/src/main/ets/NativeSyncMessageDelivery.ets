import * as podjs from 'libpodjs_harmony.so';
import { SyncMessageInbox } from './SyncMessageInbox';
let abilityVisible: boolean = false;
let pageVisible: boolean = false;
let delivery: SyncMessageInbox | null = null;
let timer: number = -1;
function tick(): void {
  delivery?.pump().catch(() => { console.error('PodJS message delivery deferred'); });
}
export function refreshSyncMessageDelivery(): void {
  const active = abilityVisible && pageVisible && delivery !== null && podjs.hasCapability('companion.sync.message');
  delivery?.setActive(active);
  if (!active && timer !== -1) { clearInterval(timer); timer = -1; }
  if (active && timer === -1) { tick(); timer = setInterval(tick, 1000); }
}
export function attachSyncMessageDelivery(value: SyncMessageInbox): void {
  detachSyncMessageDelivery(); delivery = value; refreshSyncMessageDelivery();
}
export function detachSyncMessageDelivery(): void {
  delivery?.setActive(false); delivery = null;
  if (timer !== -1) { clearInterval(timer); timer = -1; }
}
export function syncMessageAbilityVisible(value: boolean): void { abilityVisible = value; refreshSyncMessageDelivery(); }
export function syncMessagePageVisible(value: boolean): void { pageVisible = value; refreshSyncMessageDelivery(); }
