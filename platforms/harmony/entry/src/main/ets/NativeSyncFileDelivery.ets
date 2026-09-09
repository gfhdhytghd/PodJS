import * as podjs from 'libpodjs_harmony.so';
import { util } from '@kit.ArkTS';
import { SyncFileEventDelivery } from './SyncFileEventDelivery';
let abilityVisible: boolean = false;
let pageVisible: boolean = false;
let deliveries: SyncFileEventDelivery[] = [];
let timer: number = -1;
const encoder: util.TextEncoder = new util.TextEncoder();
function tick(): void {
  for (const delivery of deliveries) delivery.pumpEvents((json: string) => podjs.postEvent(encoder.encodeInto(json)))
    .catch(() => { console.error('PodJS file delivery deferred'); });
}
export function refreshSyncFileDelivery(): void {
  const active = abilityVisible && pageVisible && deliveries.length !== 0 && podjs.hasCapability('companion.sync.file');
  for (const delivery of deliveries) delivery.setEventsActive(active);
  if (!active && timer !== -1) { clearInterval(timer); timer = -1; }
  if (active && timer === -1) { tick(); timer = setInterval(tick, 1000); }
}
export function attachSyncFileDelivery(incoming: SyncFileEventDelivery, outgoing: SyncFileEventDelivery): void {
  detachSyncFileDelivery(); deliveries = [incoming, outgoing]; refreshSyncFileDelivery();
}
export function detachSyncFileDelivery(): void {
  for (const delivery of deliveries) delivery.setEventsActive(false); deliveries = [];
  if (timer !== -1) { clearInterval(timer); timer = -1; }
}
export function syncFileAbilityVisible(value: boolean): void { abilityVisible = value; refreshSyncFileDelivery(); }
export function syncFilePageVisible(value: boolean): void { pageVisible = value; refreshSyncFileDelivery(); }
