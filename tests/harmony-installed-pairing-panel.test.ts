import { test, expect } from 'bun:test';

async function fixture() {
  let active = true, releases = 0, closed = 0, background: () => void = () => {};
  let render: () => Promise<any> = async () => bitmap;
  const bitmap = { async release() { releases++; } };
  const control = {
    installedSyncIsForeground: () => active,
    onInstalledSyncBackground: (listener: () => void) => { background = listener; return () => { background = () => {}; }; },
    nativeInstalledSyncConnectionClient: async () => ({ appId: () => 'installed.app', localId: () => 'watch' }),
    renderCompanionPairingQr: () => render(),
    CompanionPairingInvitation: { async create(app: string, local: string) {
      expect(app).toBe('installed.app'); expect(local).toBe('watch');
      return { close() { closed++; } };
    } }
  };
  const source = (await Bun.file('platforms/harmony/entry/src/main/ets/InstalledPairingPanel.ets').text())
    .split('  build() {')[0].replace(/^import [\s\S]*?;\n/gm, '').replace('@Component\n', '')
    .replace('export struct ', 'export class ').replace(/@State /g, '') + '}';
  const key = '__pairPanel' + Math.random().toString(16).slice(2); (globalThis as any)[key] = control;
  try {
    const prelude = `const { installedSyncIsForeground, onInstalledSyncBackground, nativeInstalledSyncConnectionClient,
      renderCompanionPairingQr, CompanionPairingInvitation } = globalThis.${key};
      class NativeCompanionSyncCrypto {} class CompanionPairingInvitationLease { assertActive() {} close() {} }`;
    const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(prelude + source);
    const module = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
    const panel = new module.InstalledPairingPanel(); panel.getUIContext = () => ({ getHostContext: () => ({}) });
    return { panel, bitmap, setRender: (value: () => Promise<any>) => { render = value; },
      background: () => { active = false; background(); }, counts: () => ({ releases, closed }) };
  } finally { delete (globalThis as any)[key]; }
}
test('installed invitation uses actual identity and clears QR resources on background', async () => {
  const f = await fixture(); f.panel.aboutToAppear(); await f.panel.createInvitation();
  expect(f.panel.device).toBe('watch'); expect(f.panel.bitmap).toBe(f.bitmap);
  f.background(); expect(f.panel.bitmap).toBe(null); expect(f.panel.invitation).toBe(null);
  expect(f.counts().releases).toBe(1); expect(f.counts().closed).toBe(1); f.panel.aboutToDisappear();
});
test('late QR render releases its image and cannot resurrect a cancelled invitation', async () => {
  const f = await fixture(); let resolve!: (bitmap: any) => void;
  f.setRender(() => new Promise(done => { resolve = done; }));
  const pending = f.panel.createInvitation(); await new Promise(done => setTimeout(done, 0));
  f.panel.cancel(); resolve(f.bitmap); await pending;
  expect(f.counts().releases).toBe(1); expect(f.panel.bitmap).toBe(null); expect(f.panel.device).toBe('');
});
test('confirmation requires the exact current request and foreground user response', async () => {
  const f = await fixture(); const request = { peer: 'phone' }; let approved = 0;
  f.panel.confirmation = request; f.panel.answer = (value: boolean) => { if (value) approved++; };
  f.panel.respond({ peer: 'phone' }, true); expect(approved).toBe(0);
  f.panel.respond(request, true); f.panel.respond(request, true); expect(approved).toBe(1);
  f.panel.answer = () => { approved++; }; f.background(); f.panel.respond(request, true); expect(approved).toBe(1);
});
