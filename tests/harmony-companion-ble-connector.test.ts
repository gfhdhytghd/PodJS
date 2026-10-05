import { test, expect } from 'bun:test';
import { resolve } from 'node:path';
test('actual GATT client validates service, negotiates MTU, acknowledges writes and cancels stalled setup', async () => {
  const old = (globalThis as any).canIUse;
  const service = 'deef0001-654d-4e33-9a27-1341d8c28fd1', tx = service.replace('0001', '0002'), rx = service.replace('0001', '0003');
  const address = 'AA:BB:CC:DD:EE:FF'; const devices: any[] = [];
  let subscribe: Promise<void> = Promise.resolve(), wrongService = false;
  const native = { GattWriteType: { WRITE: 1 }, createGattClientDevice(peer: string) {
    expect(peer).toBe(address);
    const gatt = { callbacks: new Map<string, any>(), closed: 0, disconnected: 0, writes: [] as Uint8Array[],
      on(event: string, callback: any) { this.callbacks.set(event, callback); },
      off(event: string, callback: any) { expect(callback).toBe(this.callbacks.get(event)); },
      connect() { this.callbacks.get('BLEConnectionStateChange')({ deviceId: address, state: 2 }); },
      disconnect() { this.disconnected++; }, close() { this.closed++; },
      async getServices() { return [{ serviceUuid: wrongService ? 'wrong' : service, isPrimary: true, characteristics: [
        { serviceUuid: service, characteristicUuid: tx, characteristicValue: new ArrayBuffer(0), properties: { write: true }, descriptors: [] },
        { serviceUuid: service, characteristicUuid: rx, characteristicValue: new ArrayBuffer(0), properties: { indicate: true }, descriptors: [
          { serviceUuid: service, characteristicUuid: rx, descriptorUuid: '00002902-0000-1000-8000-00805f9b34fb', descriptorValue: new ArrayBuffer(0) }
        ] }
      ] }]; },
      setBLEMtuSize(mtu: number) { expect(mtu).toBe(517); this.callbacks.get('BLEMtuChange')(23); },
      setCharacteristicChangeIndication(characteristic: any, enabled: boolean) { expect(characteristic.characteristicUuid).toBe(rx); expect(enabled).toBe(true); return subscribe; },
      async writeCharacteristicValue(characteristic: any, kind: number) { expect(characteristic.characteristicUuid).toBe(tx); expect(kind).toBe(1); this.writes.push(new Uint8Array(characteristic.characteristicValue).slice()); }
    }; devices.push(gatt); return gatt;
  } };
  (globalThis as any).canIUse = () => true; (globalThis as any).__podjsGatt = native;
  try {
    const build = await Bun.build({ entrypoints: [resolve('platforms/harmony/companion/src/main/ets/NativeCompanionBleConnector.ets')], target: 'bun',
      plugins: [{ name: 'gatt-os', setup(builder) {
        builder.onResolve({ filter: /^\.\/NativeCompanion/ }, args => ({ path: resolve(args.resolveDir, args.path + '.ets') }));
        builder.onResolve({ filter: /^@ohos\.bluetooth\./ }, args => ({ path: args.path, namespace: 'gatt-os' }));
        builder.onLoad({ filter: /.*/, namespace: 'gatt-os' }, args => ({ contents: args.path.endsWith('.constant') ?
          'export default { ProfileConnectionState: {STATE_CONNECTED:2, STATE_CONNECTING:1} };' : 'export default globalThis.__podjsGatt;', loader: 'js' }));
        builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
      } }] });
    if (!build.success) throw Error(build.logs.map(String).join('\n'));
    const { NativeCompanionBleConnector: Connector } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
    const timers: (() => void)[] = []; const timer = { schedule(_ms: number, callback: () => void) { timers.push(callback); return () => {}; } };
    const connector = new Connector(address, 1000, timer), stream = await connector.connect();
    await stream.write(new Uint8Array([1, 2, 3]));
    expect(Array.from(devices[0].writes[0])).toEqual([0x50, 1, 0, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3]);
    devices[0].callbacks.get('BLECharacteristicChange')({ serviceUuid: service, characteristicUuid: rx, characteristicValue: devices[0].writes[0].buffer });
    expect(await stream.read()).toEqual(new Uint8Array([1, 2, 3]));
    devices[0].callbacks.get('BLECharacteristicChange')({ serviceUuid: service, characteristicUuid: tx, characteristicValue: new ArrayBuffer(1) });
    await expect(stream.read()).rejects.toThrow('unexpected'); expect(devices[0].closed).toBe(1);
    await expect(connector.connect()).rejects.toThrow('already used');
    wrongService = true; await expect(new Connector(address, 1000, timer).connect()).rejects.toThrow('setup');
    expect(devices[1].closed).toBe(1); wrongService = false;
    let release!: () => void; subscribe = new Promise(resolve => { release = resolve; });
    const late = new Connector(address, 1000, timer), pending = late.connect();
    for (let i = 0; i < 8; i++) await Promise.resolve();
    late.cancel(); await expect(pending).rejects.toThrow('cancelled');
    release(); for (let i = 0; i < 8; i++) await Promise.resolve();
    expect(devices[2].closed).toBe(1); expect(devices[2].writes.length).toBe(0);
  } finally { (globalThis as any).canIUse = old; delete (globalThis as any).__podjsGatt; }
});
