import { expect, test } from 'bun:test';
import { installedSyncIdentity } from '../platforms/harmony/entry/src/main/ets/InstalledSyncIdentity';
import type { CompanionStatePort } from '../platforms/harmony/entry/src/main/ets/CompanionState';
class Port implements CompanionStatePort {
  value: string | null = null;
  uncertain = false;
  async read(namespace: string) { expect(namespace).toBe('podjs-installed-sync-identity');return this.value; }
  async compareExchange(namespace: string, old: string | null, next: string) {
    expect(namespace).toBe('podjs-installed-sync-identity');
    if (old !== this.value) return false; this.value = next;
    if (this.uncertain) throw Error('commit result lost');return true;
  }
}
test('installed identity is stable and concurrent first opens converge', async () => {
  const port = new Port();let randomCalls = 0;
  const random = async () => (++randomCalls).toString(16).padStart(32, '0');
  const [first, second] = await Promise.all([installedSyncIdentity('installed.app', port, random), installedSyncIdentity('installed.app', port, random)]);
  expect(first).toEqual(second);expect(first.deviceId).toMatch(/^watch-[0-9a-f]{32}$/);
  expect(await installedSyncIdentity('installed.app', port, async () => {throw Error('must not rotate');})).toEqual(first);
});
test('foreign or corrupt stored identities fail without overwriting', async () => {
  for (const value of ['broken', '{}', JSON.stringify({ schema: 1, appId: 'foreign', deviceId: 'watch-' + 'a'.repeat(32) })]) {
    const port = new Port();port.value = value;
    await expect(installedSyncIdentity('installed.app', port, async () => 'b'.repeat(32))).rejects.toThrow();expect(port.value).toBe(value);
  }
});
test('uncertain committed identity is recovered and malformed randomness never persists', async () => {
  const port = new Port();port.uncertain = true;
  await expect(installedSyncIdentity('installed.app', port, async () => 'a'.repeat(32))).rejects.toThrow('commit result lost');
  expect((await installedSyncIdentity('installed.app', port, async () => {throw Error('must not rotate');})).deviceId).toBe('watch-' + 'a'.repeat(32));
  const empty = new Port();await expect(installedSyncIdentity('installed.app', empty, async () => 'invalid')).rejects.toThrow();expect(empty.value).toBeNull();
});
