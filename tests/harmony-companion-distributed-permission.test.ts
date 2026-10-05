import { test, expect, afterEach } from 'bun:test';
import { resolve } from 'node:path';
const previousCanIUse = (globalThis as any).canIUse;
afterEach(() => { (globalThis as any).canIUse = previousCanIUse; delete (globalThis as any).__podjsDistributedPermission; });
for (const adapter of [
  { name: 'Distributed', permission: 'ohos.permission.DISTRIBUTED_DATASYNC', capability: 'SystemCapability.DistributedSched.AppCollaboration' },
  { name: 'Bluetooth', permission: 'ohos.permission.ACCESS_BLUETOOTH', capability: 'SystemCapability.Communication.Bluetooth.Core' }
]) test(`actual ${adapter.name} permission adapter gates capability, result identity, current grant and cancellation`, async () => {
  let grant = -1, supported = true, requests = 0, nextGrant = 0;
  let permission = adapter.permission, auth = 0, pending: Promise<void> | null = null;
  (globalThis as any).canIUse = (capability: string) => { expect(capability).toBe(adapter.capability); return supported; };
  (globalThis as any).__podjsDistributedPermission = { GrantStatus: { PERMISSION_GRANTED: 0 }, createAtManager() { return {
    checkAccessTokenSync(token: number, name: string) { expect(token).toBe(42); expect(name).toBe(adapter.permission); return grant; },
    async requestPermissionsFromUser(_context: unknown, names: string[]) {
      requests++; expect(names).toEqual([adapter.permission]); if (pending) await pending;
      grant = nextGrant; return { permissions: [permission], authResults: [auth] };
    }
  }; } };
  const build = await Bun.build({ entrypoints: [resolve(`platforms/harmony/companion/src/main/ets/NativeCompanion${adapter.name}Permission.ets`)], target: 'bun',
    plugins: [{ name: 'permission-os', setup(builder) {
      builder.onResolve({ filter: /^@kit\.AbilityKit$/ }, () => ({ path: 'permission', namespace: 'permission-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'permission-os' }, () => ({ contents: 'export const abilityAccessCtrl = globalThis.__podjsDistributedPermission;', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const module = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  const request = module[`requestCompanion${adapter.name}Permission`];
  const context = { applicationInfo: { accessTokenId: 42 } };
  expect(await request(context)).toBe(true); expect(requests).toBe(1);
  expect(await request(context)).toBe(true); expect(requests).toBe(1);
  grant = -1; auth = -1; expect(await request(context)).toBe(false);
  grant = -1; auth = 0; nextGrant = -1; expect(await request(context)).toBe(false);
  permission = 'ohos.permission.INTERNET'; expect(await request(context)).toBe(false); permission = adapter.permission;
  let release!: () => void, cancelled = false;
  pending = new Promise(resolve => { release = resolve; }); nextGrant = 0;
  const late = request(context, () => cancelled); await expect(request(context)).rejects.toThrow('busy');
  cancelled = true; release(); expect(await late).toBe(false); pending = null;
  const before = requests; expect(await request(context, () => true)).toBe(false); expect(requests).toBe(before);
  supported = false; await expect(request(context)).rejects.toThrow('unsupported'); expect(requests).toBe(before);
});
