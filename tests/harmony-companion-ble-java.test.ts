import { test, expect } from 'bun:test';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { resolve, join, delimiter } from 'node:path';
import { execFileSync } from 'node:child_process';
import { CompanionBlePacketStream } from '../platforms/harmony/companion/src/main/ets/CompanionBlePacketStream';

const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(homedir(), 'Android/Sdk');
const androidJar = join(sdk, 'platforms/android-36/android.jar');
test.skipIf(!existsSync(androidJar) || !Bun.which('javac') || !Bun.which('java'))('real Android and Harmony BLE codecs interoperate at MTU 23, 185 and 517', async () => {
  const output = mkdtempSync(join(tmpdir(), 'podjs-ble-interop-'));
  try {
    const javaRoot = resolve('platforms/android/runtime/src/main/java/dev/podjs/runtime');
    execFileSync('javac', ['-cp', androidJar, '-d', output, join(javaRoot, 'PodSyncStream.java'), join(javaRoot, 'PodBleStream.java'), resolve('tests/fixtures/BleWireInterop.java')], { timeout: 20000 });
    for (const mtu of [23, 185, 517]) {
      const payload = Uint8Array.from({ length: 2049 }, (_, i) => i % 251), packets: Uint8Array[] = [];
      const timer = { schedule() { return () => {}; } };
      const sender = new CompanionBlePacketStream(mtu, { async write(bytes) { packets.push(bytes.slice()); }, close() {} }, timer, 1000);
      await sender.write(payload);
      const input = [Buffer.from(payload).toString('hex'), ...packets.flatMap(bytes => [Buffer.from(bytes).toString('hex'), Buffer.from(bytes).toString('hex')])].join('\n') + '\n';
      const result = execFileSync('java', ['-cp', output + delimiter + androidJar, 'BleWireInterop', String(mtu)], { input, encoding: 'utf8', timeout: 10000 });
      const encoded: Uint8Array[] = result.trim().split('\n').map(line => new Uint8Array(Buffer.from(line, 'hex')));
      expect(encoded).toEqual(packets);
      const receiver = new CompanionBlePacketStream(mtu, { async write() {}, close() {} }, timer, 1000);
      for (const packet of encoded) { receiver.receive(packet); receiver.receive(packet); }
      expect(await receiver.read()).toEqual(payload); sender.close(); receiver.close();
    }
  } finally { rmSync(output, { recursive: true, force: true }); }
}, 30000);
