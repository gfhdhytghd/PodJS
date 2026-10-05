import { test, expect } from 'bun:test';
import { resolve } from 'node:path';

test('actual GATT client/server exchange framed data and cancel late advertising without trusting a central', async () => {
  const prior = (globalThis as any).canIUse;
  const serverAddress = 'AA:BB:CC:DD:EE:01', clientAddress = 'AA:BB:CC:DD:EE:02';
  const service = 'deef0001-654d-4e33-9a27-1341d8c28fd1', tx = service.replace('0001', '0002'), rx = service.replace('0001', '0003');
  let server: any, client: any, nextRequest = 0;
  let advertising: () => Promise<number> = async () => 7;
  const stopped: number[] = [], responses: any[] = [], pending = new Map<number, { resolve(): void; reject(error: Error): void }>();
  function request(event: string, fields: object) {
    return new Promise<void>((resolve, reject) => {
      const transId = ++nextRequest; pending.set(transId, { resolve, reject });
      server.callbacks.get(event)({ deviceId: clientAddress, transId, offset: 0, isPrepared: false, needRsp: true, serviceUuid: service, ...fields });
    });
  }
  const native = { GattWriteType: { WRITE: 1 },
    startAdvertising(params: any) {
      expect(params.advertisingData).toEqual({ serviceUuids: [service], manufactureData: [], serviceData: [], includeDeviceName: false });
      expect(params.advertisingSettings.connectable).toBe(true); expect(params.duration).toBe(100);
      return advertising();
    },
    async stopAdvertising(id: number) { stopped.push(id); },
    createGattServer() {
      server = { callbacks: new Map<string, any>(), services: [] as any[], closed: 0, disconnected: [] as string[],
        on(event: string, callback: any) { this.callbacks.set(event, callback); },
        off(event: string, callback: any) { expect(callback).toBe(this.callbacks.get(event)); },
        addService(value: any) { this.services.push(value); },
        close() { this.closed++; },
        disconnect(address: string) { this.disconnected.push(address); },
        sendResponse(value: any) { responses.push(value); const task = pending.get(value.transId); pending.delete(value.transId);
          if (task) value.status === 0 ? task.resolve() : task.reject(Error('ATT error')); },
        async notifyCharacteristicChanged(address: string, value: any) {
          expect(address).toBe(clientAddress); expect(value.confirm).toBe(true); expect(value.characteristicUuid).toBe(rx);
          client.callbacks.get('BLECharacteristicChange')(value);
        }
      }; return server;
    },
    createGattClientDevice(address: string) {
      expect(address).toBe(serverAddress);
      client = { callbacks: new Map<string, any>(),
        on(event: string, callback: any) { this.callbacks.set(event, callback); },
        off(event: string, callback: any) { expect(callback).toBe(this.callbacks.get(event)); },
        connect() {
          server.callbacks.get('connectionStateChange')({ deviceId: clientAddress, state: 2 });
          this.callbacks.get('BLEConnectionStateChange')({ deviceId: serverAddress, state: 2 });
        }, disconnect() {}, close() {},
        async getServices() { return server.services; },
        setBLEMtuSize(mtu: number) { expect(mtu).toBe(517); server.callbacks.get('BLEMtuChange')(185); this.callbacks.get('BLEMtuChange')(185); },
        setCharacteristicChangeIndication(value: any, enabled: boolean) {
          expect(enabled).toBe(true);
          return request('descriptorWrite', { characteristicUuid: value.characteristicUuid,
            descriptorUuid: value.descriptors[0].descriptorUuid, value: new Uint8Array([2, 0]).buffer });
        },
        writeCharacteristicValue(value: any, kind: number) {
          expect(kind).toBe(1); expect(value.characteristicUuid).toBe(tx);
          return request('characteristicWrite', { characteristicUuid: tx, value: value.characteristicValue });
        }
      }; return client;
    }
  };
  (globalThis as any).canIUse = () => true; (globalThis as any).__podjsBleServer = native;
  try {
    const build = await Bun.build({ entrypoints: [resolve('tests/fixtures/harmony-ble-exports.ts')], target: 'bun',
      plugins: [{ name: 'ble-server-os', setup(builder) {
        builder.onResolve({ filter: /\/NativeCompanion/ }, args => ({ path: resolve(args.resolveDir, args.path + '.ets') }));
        builder.onResolve({ filter: /^@ohos\.bluetooth\./ }, args => ({ path: args.path, namespace: 'ble-server-os' }));
        builder.onLoad({ filter: /.*/, namespace: 'ble-server-os' }, args => ({ contents: args.path.endsWith('.constant') ?
          'export default { ProfileConnectionState: { STATE_CONNECTED: 2, STATE_CONNECTING: 1 } };' : 'export default globalThis.__podjsBleServer;', loader: 'js' }));
        builder.onLoad({ filter: /\.ets$/ }, async args => ({ contents: await Bun.file(args.path).text(), loader: 'ts' }));
      } }] });
    if (!build.success) throw Error(build.logs.map(String).join('\n'));
    const { NativeCompanionBleConnector: Connector, NativeCompanionBleAcceptor: Acceptor } = await import('data:text/javascript;base64,' + Buffer.from(await build.outputs[0].text()).toString('base64'));
    const timer = { schedule() { return () => {}; } };
    const acceptor = new Acceptor(clientAddress, 1000, timer), accepted = acceptor.connect();
    await expect(new Acceptor(null, 1000, timer).connect()).rejects.toThrow('already active');
    const connector = new Connector(serverAddress, 1000, timer), outgoing = connector.connect();
    const [left, right] = await Promise.all([accepted, outgoing]);
    expect(stopped).toEqual([7]);
    const payload = Uint8Array.from({ length: 800 }, (_, i) => i % 251);
    await right.write(payload); expect(await left.read()).toEqual(payload);
    await left.write(payload); expect(await right.read()).toEqual(payload);
    expect(responses.every(response => response.status === 0)).toBe(true);
    // A second central cannot change the selected connection's MTU namespace.
    server.callbacks.get('connectionStateChange')({ deviceId: 'AA:BB:CC:DD:EE:03', state: 2 });
    await expect(left.read()).rejects.toThrow('unexpected'); expect(server.closed).toBe(1);
    expect(server.disconnected).toEqual(['AA:BB:CC:DD:EE:03', clientAddress]); connector.cancel();
    let release!: (id: number) => void;
    advertising = () => new Promise(resolve => { release = resolve; });
    const late = new Acceptor(null, 1000, timer), opening = late.connect();
    late.cancel(); await expect(opening).rejects.toThrow('cancelled'); release(9); await Promise.resolve();
    expect(stopped).toEqual([7, 9]); expect(server.closed).toBe(1);
    advertising = async () => 10;
    const malformed = new Acceptor(clientAddress, 1000, timer), waiting = malformed.connect();
    await Promise.resolve(); server.callbacks.get('connectionStateChange')({ deviceId: clientAddress, state: 2 });
    await expect(request('descriptorWrite', { characteristicUuid: rx, descriptorUuid: '00002902-0000-1000-8000-00805f9b34fb',
      value: new Uint8Array([1, 0]).buffer })).rejects.toThrow('ATT error');
    await expect(waiting).rejects.toThrow('subscription'); expect(server.closed).toBe(1);
    expect(stopped).toEqual([7, 9, 10]);
  } finally { (globalThis as any).canIUse = prior; delete (globalThis as any).__podjsBleServer; }
});
