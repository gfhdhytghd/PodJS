import { common } from '@kit.AbilityKit';
import { bundleManager } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import * as podjs from 'libpodjs_harmony.so';
import { CompanionState, NativeCompanionStateCas, NativeCompanionClient,
  CompanionMessageOutbox, CompanionMessageInbox, CompanionIncomingFiles,
  CompanionPairings, openCompanionPairings } from '@podjs/companion';
import { installedSyncIdentity } from './InstalledSyncIdentity';

let pending: Promise<NativeCompanionClient> | null = null;
let directory: string = '';
let pairingOwner: Promise<CompanionPairings> | null = null;
/** Host settings share one app-scoped pairing lease. Merely rendering settings
 * must not generate an invitation, approve a peer, or start a transport. */
export async function nativeInstalledSyncPairings(context: common.UIAbilityContext): Promise<CompanionPairings> {
  const client = await nativeInstalledSyncConnectionClient(context);
  if (pairingOwner === null) {
    const opening = openCompanionPairings(context, client.appId(), client.localId());
    pairingOwner = opening;
    opening.catch(() => { if (pairingOwner === opening) pairingOwner = null; });
  }
  return pairingOwner;
}
/** Called lazily behind the native capability gate after package boot. */
export function nativeInstalledSyncState(context: common.UIAbilityContext): Promise<CompanionState> {
  if (!podjs.hasCapability('companion.sync.state')) return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context).then((value: NativeCompanionClient) => value.state);
}
export function nativeInstalledSyncOutbox(context: common.UIAbilityContext): Promise<CompanionMessageOutbox> {
  if (!podjs.hasCapability('companion.sync.message')) return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context).then((value: NativeCompanionClient) => value.outbox);
}
export function nativeInstalledSyncInbox(context: common.UIAbilityContext): Promise<CompanionMessageInbox> {
  if (!podjs.hasCapability('companion.sync.message')) return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context).then((value: NativeCompanionClient) => value.inbox);
}
export function nativeInstalledSyncIncoming(context: common.UIAbilityContext): Promise<CompanionIncomingFiles> {
  if (!podjs.hasCapability('companion.sync.file')) return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context).then((value: NativeCompanionClient) => value.incomingFiles);
}
/** Host-only file orchestration access; guest operations still pass their own
 * service capability gate. Constructing the shared client starts no transport. */
export function nativeInstalledSyncFileClient(context: common.UIAbilityContext): Promise<NativeCompanionClient> {
  if (!podjs.hasCapability('companion.sync.file')) return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context);
}
export function nativeInstalledSyncConnectionClient(context: common.UIAbilityContext): Promise<NativeCompanionClient> {
  if (!podjs.hasCapability('companion.sync.state') && !podjs.hasCapability('companion.sync.message') && !podjs.hasCapability('companion.sync.file'))
    return Promise.reject(new Error('Installed sync is unavailable'));
  return owner(context);
}
export async function verifyInstalledIncomingIdentity(context: common.UIAbilityContext, id: string, peer: string): Promise<void> {
  const client = await nativeInstalledSyncFileClient(context);
  const identity = await client.resolveFileIdentity(id);
  if (identity === null || identity.direction !== 'incoming' || identity.peer !== peer) throw new Error('Incoming file identity changed');
}
export async function newInstalledFileTransferId(): Promise<string> {
  const bytes = (await cryptoFramework.createRandom().generateRandom(16)).data;
  let id = 'file-'; for (const byte of bytes) id += byte.toString(16).padStart(2, '0'); return id;
}
function owner(context: common.UIAbilityContext): Promise<NativeCompanionClient> {
  if (pending !== null) {
    if (directory !== context.filesDir) return Promise.reject(new Error('Installed sync context mismatch'));
    return pending;
  }
  directory = context.filesDir;
  const opening = open(context); pending = opening;
  opening.catch(() => { if (pending === opening) pending = null; });
  return opening;
}
async function open(context: common.UIAbilityContext): Promise<NativeCompanionClient> {
  const bundle = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
  const identity = await installedSyncIdentity(bundle.name, new NativeCompanionStateCas(context), async () => {
    const bytes = (await cryptoFramework.createRandom().generateRandom(16)).data;
    let hex = ''; for (const byte of bytes) hex += byte.toString(16).padStart(2, '0'); return hex;
  });
  return new NativeCompanionClient(context, identity.appId, identity.deviceId);
}
