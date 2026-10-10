import { expect, test } from 'bun:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeAndroidManifest } from '../packages/cli/src/android-manifest.ts';

const root = join(import.meta.dir, '..');
const optional = ['RECORD_AUDIO', 'CHANGE_WIFI_STATE', 'CHANGE_NETWORK_STATE', 'NEARBY_WIFI_DEVICES'];
const permissionNames = (xml: string) => [...xml.matchAll(/<uses-permission\b[^>]*android:name="([^"]+)"/g)].map(match => match[1]);
for (const platform of ['android', 'wearos']) {
  const template = readFileSync(join(root, 'platforms', platform, 'app/src/main/AndroidManifest.xml'), 'utf8');
  test(`${platform}: absent or unrelated app capabilities leave the host manifest intact`, () => {
    const out = mkdtempSync(join(tmpdir(), 'pod-manifest-'));
    try {
      for (const capabilities of [[], ['cloud', 'tts', 'debug.acoustic.extra'], ['<uses-permission android:name="arbitrary"/>']]) {
        writeAndroidManifest(out, template, capabilities);
        expect(readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8')).toBe(template);
      }
    } finally { rmSync(out, { recursive: true, force: true }); }
  });
  test(`${platform}: explicit acoustic opt-in adds only its four permissions and preserves host configuration`, () => {
    const out = mkdtempSync(join(tmpdir(), 'pod-manifest-'));
    try {
      writeAndroidManifest(out, template, ['debug.acoustic', 'debug.acoustic']);
      const xml = readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8');
      expect(permissionNames(xml).sort()).toEqual([...permissionNames(template), ...optional.map(name => `android.permission.${name}`)].sort());
      expect(xml).toContain('android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation"');
      expect(xml.slice(xml.indexOf('<application'))).toBe(template.slice(template.indexOf('<application')));
      // Rebuilding the same output after withdrawing consent removes stale permissions.
      writeAndroidManifest(out, template, []);
      expect(readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8')).toBe(template);
    } finally { rmSync(out, { recursive: true, force: true }); }
  });
}
test('the shared runtime retains discovery/media permissions without requesting acoustic permissions', () => {
  const xml = readFileSync(join(root, 'platforms/android/runtime/src/main/AndroidManifest.xml'), 'utf8');
  for (const name of optional) expect(permissionNames(xml)).not.toContain(`android.permission.${name}`);
  for (const name of ['INTERNET', 'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE', 'CHANGE_WIFI_MULTICAST_STATE', 'ACCESS_FINE_LOCATION', 'BLUETOOTH_CONNECT', 'MODIFY_AUDIO_SETTINGS', 'FOREGROUND_SERVICE_MEDIA_PLAYBACK']) {
    expect(permissionNames(xml)).toContain(`android.permission.${name}`);
  }
});
test('a malformed host template fails before generating an unusable main manifest', () => {
  expect(() => writeAndroidManifest('/unused', '<manifest />', ['debug.acoustic'])).toThrow('no application');
});
