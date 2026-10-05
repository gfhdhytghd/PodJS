import { test, expect } from 'bun:test';
import { resolve } from 'node:path';

test('actual BLE discovery isolates scanner lifetime, bounds candidates and rejects late startup', async () => {
  const previous = (globalThis as any).canIUse;
  const scanners: any[] = []; let startup: Promise<void> = Promise.resolve();
  const native = { ScanReportType: { ON_FOUND: 1, ON_LOST: 2, ON_BATCH: 3 }, createBleScanner() {
    const scanner = { callback: null as any, stopped: 0, removed: 0,
      on(_event: string, callback: any) { this.callback = callback; },
      off(_event: string, callback: any) { expect(callback).toBe(this.callback); this.removed++; },
      startScan(filters: unknown) { expect(filters).toEqual([{ serviceUuid: 'deef0001-654d-4e33-9a27-1341d8c28fd1' }]); return startup; },
      async stopScan() { this.stopped++; }
    }; scanners.push(scanner); return scanner;
  } };
  (globalThis as any).canIUse = () => true;
  (globalThis as any).__podjsBleDiscovery = native;
  try {
    const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionBleDiscovery.ets')], target: 'bun',
      plugins: [{ name: 'ble-discovery-os', setup(builder) {
        builder.onResolve({ filter: /^@ohos\.bluetooth\.ble$/ }, () => ({ path: 'ble', namespace: 'discovery' }));
        builder.onLoad({ filter: /.*/, namespace: 'discovery' }, () => ({ contents: 'export default globalThis.__podjsBleDiscovery;', loader: 'js' }));
        builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
      } }] });
    if (!build.success) throw Error(build.logs.map(String).join('\n'));
    const { NativeCompanionBleDiscovery: Discovery } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
    const timers: (() => void)[] = []; let timerCancelled = 0;
    const timer = { schedule(_ms: number, callback: () => void) { timers.push(callback); return () => { timerCancelled++; }; } };
    const first = new Discovery(timer), second = new Discovery(timer);
    const run = first.scan(100), other = second.scan(100); await Promise.resolve();
    const row = (i: number) => ({ deviceId: 'AA:BB:CC:DD:EE:' + i.toString(16).padStart(2, '0'), deviceName: 'watch\u202e\n', rssi: -40, connectable: true });
    scanners[0].callback({ reportType: 1, scanResult: Array.from({ length: 40 }, (_, i) => row(i)) });
    scanners[0].callback({ reportType: 2, scanResult: [row(0)] });
    scanners[0].callback({ reportType: 1, scanResult: [{ ...row(1), rssi: -30 }, { ...row(2), deviceId: 'invalid' }] });
    timers[0](); const results = await run;
    expect(results.length).toBe(31); expect(results[0].rssi).toBe(-30); expect(results[0].name).toBe('watch');
    expect(scanners[0].stopped).toBe(1); expect(scanners[1].stopped).toBe(0);
    second.cancel(); await expect(other).rejects.toThrow('cancelled');
    await expect(first.scan()).rejects.toThrow('already used');
    scanners[0].callback({ reportType: 1, scanResult: [row(40)] }); expect(results.length).toBe(31);
    let release!: () => void; startup = new Promise(resolve => { release = resolve; });
    const late = new Discovery(timer); const pending = late.scan(100); timers[2]();
    await expect(pending).rejects.toThrow('deadline'); expect(scanners[2].stopped).toBe(1);
    release(); await Promise.resolve(); expect(scanners[2].stopped).toBe(2);
    expect(timerCancelled).toBe(3);
    startup = Promise.reject(Error('permission denied'));
    await expect(new Discovery(timer).scan()).rejects.toThrow('unavailable');
    expect(scanners[3].removed).toBe(1); expect(scanners[3].stopped).toBe(1);
    await expect(new Discovery(timer).scan(0)).rejects.toThrow('duration');
  } finally {
    (globalThis as any).canIUse = previous; delete (globalThis as any).__podjsBleDiscovery;
  }
});
