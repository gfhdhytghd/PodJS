import { test, expect } from 'bun:test';

// Exercise the actual panel's event handlers, not ArkUI rendering. The latter
// still requires SDK compilation and device acceptance.
async function panelFixture() {
  let active = true, reads = 0, revokes = 0, disconnects = 0;
  let dialog: any = null;
  let connectionPhase = 'connected', connects = 0;
  let permission: () => Promise<boolean> = async () => true;
  const owner = { async recover() {}, async list() { reads++; return [{ peer: 'phone', phase: 'approved' }]; },
    async revoke() { revokes++; } };
  const control = {
    installedSyncIsForeground: () => active,
    installedSyncStatus: () => ({ phase: connectionPhase, peer: 'phone' }),
    disconnectInstalledSync: () => { disconnects++; },
    nativeInstalledSyncPairings: async () => owner,
    requestCompanionBluetoothPermission: () => permission(),
    connectInstalledSync: async (_context: any, _attempt: any, peer: string, initiator: boolean) => {
      expect(peer).toBe('phone'); expect(initiator).toBe(false); connects++; connectionPhase = 'connected';
    }
  };
  const source = (await Bun.file('platforms/harmony/entry/src/main/ets/InstalledSyncPanel.ets').text())
    .split('  build() {')[0].replace(/^import .*;\n/gm, '').replace('@Component\n', '')
    .replace('export struct ', 'export class ').replace(/@State /g, '') + '}';
  const key = '__syncPanelTest' + Math.random().toString(16).slice(2);
  (globalThis as any)[key] = control;
  try {
    const prelude = `const { installedSyncIsForeground, installedSyncStatus, disconnectInstalledSync, nativeInstalledSyncPairings,
      requestCompanionBluetoothPermission, connectInstalledSync } = globalThis.${key};
      class CompanionPairedAttempt {} class CompanionSyncAttempt {} class NativeCompanionBleAcceptor {} class NativeCompanionSyncCrypto {}`;
    const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(prelude + source);
    const module = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
    const panel = new module.InstalledSyncPanel();
    panel.getUIContext = () => ({ getHostContext: () => ({}), showAlertDialog: (value: any) => { dialog = value; } });
    return { panel, control, owner, background: () => { active = false; }, dialog: () => dialog,
      idle: () => { connectionPhase = 'idle'; panel.readConnection(); },
      permission: (value: () => Promise<boolean>) => { permission = value; },
      counts: () => ({ reads, revokes, disconnects, connects }) };
  } finally { delete (globalThis as any)[key]; }
}

test('installed pairing panel confirms revocation and invalidates an old dialog on disappearance', async () => {
  const f = await panelFixture(); await f.panel.refresh();
  expect(f.panel.records).toEqual([{ peer: 'phone', phase: 'approved' }]);
  expect(f.panel.connectionLabel).toBe('已连接：phone');
  f.panel.confirmRevoke(f.panel.records[0]); expect(f.counts().revokes).toBe(0);
  f.panel.aboutToDisappear(); f.dialog().secondaryButton.action();
  await Promise.resolve(); expect(f.counts().revokes).toBe(0);
  f.panel.confirmRevoke(f.panel.records[0]); f.dialog().secondaryButton.action();
  await new Promise(done => setTimeout(done, 0));
  expect(f.counts().revokes).toBe(1); expect(f.counts().disconnects).toBe(1);
});

test('watch wait uses the approved peer as responder and cancelled permission cannot start a connection', async () => {
  const f = await panelFixture(); f.idle();
  await f.panel.waitForPeer({ peer: 'phone', phase: 'importing' }); expect(f.counts().connects).toBe(0);
  await f.panel.waitForPeer({ peer: 'phone', phase: 'approved' }); expect(f.counts().connects).toBe(1);
  f.panel.aboutToDisappear(); expect(f.counts().disconnects).toBe(0);
  const g = await panelFixture(); g.idle(); let resolve!: (value: boolean) => void;
  g.permission(() => new Promise(done => { resolve = done; }));
  const waiting = g.panel.waitForPeer({ peer: 'phone', phase: 'approved' });
  g.panel.cancelPending(); resolve(true); await waiting;
  expect(g.counts().connects).toBe(0); expect(g.panel.busy).toBe(false);
});

test('background prevents confirmation and a late records read cannot update an exited panel', async () => {
  const f = await panelFixture(); await f.panel.refresh();
  f.panel.confirmRevoke(f.panel.records[0]); f.background(); f.dialog().secondaryButton.action();
  await Promise.resolve(); expect(f.counts().revokes).toBe(0);
  const before = f.counts().reads; await f.panel.refresh(); expect(f.counts().reads).toBe(before);
  const g = await panelFixture(); let release!: (records: any[]) => void;
  g.owner.list = () => new Promise(done => { release = done; });
  const pending = g.panel.refresh();
  await new Promise(done => setTimeout(done, 0));
  g.panel.aboutToDisappear(); release([{ peer: 'late', phase: 'approved' }]); await pending;
  expect(g.panel.records).toEqual([]); expect(g.panel.loaded).toBe(false);
});
