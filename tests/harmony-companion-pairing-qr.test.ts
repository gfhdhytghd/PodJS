import { test, expect, afterEach } from 'bun:test';
import { resolve } from 'node:path';
import { CompanionPairingInvitation } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitation';
import { CompanionPairingInvitationLease } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitationLease';
const previousCanIUse = (globalThis as any).canIUse;
afterEach(() => { (globalThis as any).canIUse = previousCanIUse; });

test('actual QR generator uses complete byte payload beyond 512 chars and releases late images', async () => {
  let buffer: ArrayBuffer | null = null, captured = '', releases = 0, supported = true, fail = false;
  let wait: Promise<void> | null = null;
  const bitmap = { async release() { releases++; } };
  (globalThis as any).canIUse = () => supported;
  (globalThis as any).__podjsPairingQr = {
    scanCore: { ScanType: { QR_CODE: 11 } },
    generateBarcode: { ErrorCorrectionLevel: { LEVEL_M: 1 }, async createBarcode(input: ArrayBuffer, options: unknown) {
      expect(input).toBeInstanceOf(ArrayBuffer); buffer = input; captured = new TextDecoder().decode(input);
      expect(options).toEqual({ scanType: 11, width: 512, height: 512, backgroundColor: 0xFFFFFF, pixelMapColor: 0, margin: 10, level: 1 });
      if (wait) await wait; if (fail) throw Error('sensitive contents'); return bitmap;
    } }
  };
  const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionPairingQr.ets')], target: 'bun',
    plugins: [{ name: 'qr-os', setup(builder) {
      builder.onResolve({ filter: /^@kit\.ScanKit$/ }, () => ({ path: 'qr', namespace: 'qr-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'qr-os' }, () => ({ contents: 'export const scanCore = globalThis.__podjsPairingQr.scanCore; export const generateBarcode = globalThis.__podjsPairingQr.generateBarcode;', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const { renderCompanionPairingQr: render } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  // Synthetic safe-integer clock exercises the accepted wire-format upper bound.
  const now = 1000000000000000;
  async function lease() {
    let n = 0;
    const invitation = await CompanionPairingInvitation.create('a'.repeat(128), 'w'.repeat(128), now, { async challenge() { return new Uint8Array(32).fill(++n); } });
    return new CompanionPairingInvitationLease(invitation, { now: () => now }, { schedule() { return () => {}; } }, () => {});
  }
  const first = await lease(); expect(await render(first)).toBe(bitmap); expect(captured.length).toBeGreaterThan(512);
  const parsed = CompanionPairingInvitation.parseQr(captured, 'a'.repeat(128), 'phone', now); expect(parsed.deviceId).toBe('w'.repeat(128)); parsed.close();
  expect(new Uint8Array(buffer!).every(byte => byte === 0)).toBe(true); first.close(); await bitmap.release();
  let release!: () => void; wait = new Promise(resolve => { release = resolve; });
  const late = await lease(), pending = render(late); late.close(); release();
  await expect(pending).rejects.toThrow('failed or invitation closed'); expect(releases).toBe(2);
  wait = new Promise(resolve => { release = resolve; });
  const claimed = await lease(), rendering = render(claimed); const key = claimed.claim(() => {}); release();
  await expect(rendering).rejects.toThrow('failed or invitation closed'); expect(releases).toBe(3); expect(key.every(byte => byte === 0)).toBe(true);
  wait = null; fail = true; const failed = await lease(); await expect(render(failed)).rejects.toThrow('failed or invitation closed'); expect(() => failed.assertActive()).toThrow('closed');
  supported = false; const unavailable = await lease(); await expect(render(unavailable)).rejects.toThrow('unsupported'); expect(() => unavailable.assertActive()).toThrow('closed');
});
