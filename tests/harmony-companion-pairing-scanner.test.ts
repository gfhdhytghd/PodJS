import { test, expect, afterEach } from 'bun:test';
import { resolve } from 'node:path';
import { CompanionPairingInvitation } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitation';
const previousCanIUse = (globalThis as any).canIUse;
afterEach(() => { (globalThis as any).canIUse = previousCanIUse; });

test('actual system scanner adapter restricts live QR, sanitizes failures and ignores cancelled late results', async () => {
  let n = 0;
  const invitation = await CompanionPairingInvitation.create('app', 'watch', Date.now(), { async challenge() { return new Uint8Array(32).fill(++n); } });
  const wire = invitation.encodeForQr(Date.now());
  let result = { scanType: 11, source: 0, originalValue: wire }, failCode = 0, calls = 0, supported = true;
  let pending: Promise<void> | null = null;
  (globalThis as any).canIUse = () => supported;
  (globalThis as any).__podjsPairingScanner = {
    scanCore: { ScanType: { QR_CODE: 11 }, ScanSource: { CAMERA: 0 } },
    scanBarcode: { async startScanForResult(_context: unknown, options: unknown) {
      calls++; expect(options).toEqual({ scanTypes: [11], enableMultiMode: false, enableAlbum: false });
      if (pending) await pending;
      if (failCode) throw { code: failCode, message: 'sensitive scanned contents' };
      return result;
    } }
  };
  const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionPairingScanner.ets')], target: 'bun',
    plugins: [{ name: 'scan-os', setup(builder) {
      builder.onResolve({ filter: /^@kit\.ScanKit$/ }, () => ({ path: 'scan', namespace: 'scan-os' }));
      builder.onLoad({ filter: /.*/, namespace: 'scan-os' }, () => ({ contents: 'export const scanCore = globalThis.__podjsPairingScanner.scanCore; export const scanBarcode = globalThis.__podjsPairingScanner.scanBarcode;', loader: 'js' }));
      builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
    } }] });
  if (!build.success) throw Error(build.logs.map(String).join('\n'));
  const { scanCompanionPairingInvitation: scan } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
  const scanned = await scan({}, 'app', 'phone'); expect(scanned.deviceId).toBe('watch'); scanned.close(); expect(result.originalValue).toBe('');
  for (const changed of [{ scanType: 1, source: 0, originalValue: wire }, { scanType: 11, source: 1, originalValue: wire }, { scanType: 11, source: 0, originalValue: 'sensitive-invalid' }]) {
    result = changed; await expect(scan({}, 'app', 'phone')).rejects.toThrow('pairing scan failed or invitation invalid'); expect(result.originalValue).toBe('');
  }
  failCode = 1000500002; expect(await scan({}, 'app', 'phone')).toBeNull();
  failCode = 1; await expect(scan({}, 'app', 'phone')).rejects.toThrow('pairing scan failed or invitation invalid'); failCode = 0;
  let release!: () => void, cancelled = false;
  pending = new Promise(resolve => { release = resolve; }); result = { scanType: 11, source: 0, originalValue: wire };
  const late = scan({}, 'app', 'phone', () => cancelled);
  await expect(scan({}, 'app', 'phone')).rejects.toThrow('busy');
  cancelled = true; release(); expect(await late).toBeNull(); expect(result.originalValue).toBe(''); pending = null;
  const previous = calls; expect(await scan({}, 'app', 'phone', () => true)).toBeNull(); expect(calls).toBe(previous);
  supported = false; await expect(scan({}, 'app', 'phone')).rejects.toThrow('unsupported'); expect(calls).toBe(previous);
});
