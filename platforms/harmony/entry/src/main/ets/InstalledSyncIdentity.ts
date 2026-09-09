import { CompanionStatePort } from './CompanionState';

export class InstalledSyncIdentity {
  schema: number = 1;
  appId: string = '';
  deviceId: string = '';
}
const NAMESPACE = 'podjs-installed-sync-identity';
function decode(raw: string, app: string): InstalledSyncIdentity {
  if (raw.length > 1024) throw new Error('Invalid installed sync identity');
  const identity = JSON.parse(raw) as InstalledSyncIdentity;
  if (!identity || identity.schema !== 1 || identity.appId !== app ||
      typeof identity.deviceId !== 'string' || !/^watch-[0-9a-f]{32}$/.test(identity.deviceId))
    throw new Error('Invalid installed sync identity');
  return identity;
}
/** Host-private namespace. CAS prevents simultaneous first opens from producing
 * different local identities. Corrupt or foreign records never rotate silently. */
export async function installedSyncIdentity(app: string, port: CompanionStatePort,
  randomId: () => Promise<string>): Promise<InstalledSyncIdentity> {
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(app)) throw new Error('Invalid installed application identity');
  const previous = await port.read(NAMESPACE);
  if (previous !== null) return decode(previous, app);
  const identity = new InstalledSyncIdentity(); identity.appId = app; identity.deviceId = 'watch-' + await randomId();
  const encoded = JSON.stringify(identity); decode(encoded, app);
  if (await port.compareExchange(NAMESPACE, null, encoded)) return identity;
  const winner = await port.read(NAMESPACE);
  if (winner === null) throw new Error('Installed sync identity creation conflict');
  return decode(winner, app);
}
