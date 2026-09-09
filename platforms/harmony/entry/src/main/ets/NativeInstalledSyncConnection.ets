import { common } from '@kit.AbilityKit';
import * as podjs from 'libpodjs_harmony.so';
import { CompanionPairedAttempt, CompanionMessageTransport, CompanionStateWait, CompanionIncomingMessage, CompanionConnectionStatus } from '@podjs/companion';
import { InstalledSyncConnection } from './InstalledSyncConnection';
import { nativeInstalledSyncConnectionClient } from './NativeInstalledSync';

let connection: InstalledSyncConnection | null = null;
let directory: string = '';
let abilityVisible: boolean = false;
let pageVisible: boolean = false;
let timer: number = -1;
const backgroundListeners: Set<() => void> = new Set();
export function onInstalledSyncBackground(listener: () => void): () => void {
  backgroundListeners.add(listener); return () => { backgroundListeners.delete(listener); };
}
function notifyBackground(): void {
  for (const listener of Array.from(backgroundListeners)) { try { listener(); } catch (_) {} }
}
export function installedSyncIsForeground(): boolean { return abilityVisible && pageVisible; }
/** A status read must never create a client, lease, or network connection. */
export function installedSyncStatus(): CompanionConnectionStatus {
  return connection === null ? new CompanionConnectionStatus('idle', '', '') : connection.status();
}
function refresh(): void {
  const active = abilityVisible && pageVisible;
  connection?.setActive(active);
  if ((!active || connection === null) && timer !== -1) { clearInterval(timer); timer = -1; }
  if (active && connection !== null && timer === -1) {
    timer = setInterval(() => connection?.pump().catch(() => { console.error('PodJS installed connection deferred'); }), 1000);
  }
}
/** Called only by a host-selected pairing/transport action, never guest args. */
export function connectInstalledSync(context: common.UIAbilityContext, attempt: CompanionPairedAttempt,
  peer: string, initiator: boolean): Promise<CompanionMessageTransport> {
  if (connection !== null && directory !== context.filesDir) return Promise.reject(new Error('Installed connection context mismatch'));
  if (connection === null) {
    directory = context.filesDir;
    connection = new InstalledSyncConnection(() => nativeInstalledSyncConnectionClient(context), (name: string) => podjs.hasCapability(name));
  }
  refresh(); return connection.connect(attempt, peer, initiator);
}
export function disconnectInstalledSync(): void {
  connection?.disconnect(); connection = null; refresh();
}
export function synchronizeInstalledState(peer: string): CompanionStateWait {
  if (connection === null) throw new Error('No approved state connection');
  return connection.synchronizeState(peer);
}
export function acknowledgeInstalledMessage(delivery: CompanionIncomingMessage): Promise<void> {
  return connection === null ? Promise.resolve() : connection.acknowledgeMessage(delivery);
}
export function installedSyncAbilityVisible(value: boolean): void {
  const wasVisible = abilityVisible; abilityVisible = value; refresh(); if (wasVisible && !value) notifyBackground();
}
export function installedSyncPageVisible(value: boolean): void {
  const wasVisible = pageVisible; pageVisible = value; refresh(); if (wasVisible && !value) notifyBackground();
}
