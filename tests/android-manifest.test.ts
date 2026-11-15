import { expect, test } from 'bun:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeAndroidManifest } from '../packages/cli/src/android-manifest.ts';
import { resolvePodProject } from '../packages/cli/src/project.ts';
import { POD_TARGETS } from '../packages/framework/src/targets.ts';
import { writeFileSync } from 'node:fs';

const root = join(import.meta.dir, '..');
const optional = ['RECORD_AUDIO', 'CHANGE_WIFI_STATE', 'CHANGE_NETWORK_STATE', 'NEARBY_WIFI_DEVICES'];
const permissionNames = (xml: string) => [...xml.matchAll(/<uses-permission\b[^>]*android:name="([^"]+)"/g)].map(match => match[1]);
for (const platform of ['android', 'wearos']) {
  const template = readFileSync(join(root, 'platforms', platform, 'app/src/main/AndroidManifest.xml'), 'utf8');
  test(`${platform}: project launcher icons reach application attributes and reset on rebuild`, () => {
    const out = mkdtempSync(join(tmpdir(), 'pod-launcher-'));
    try {
      writeFileSync(join(out, 'main.tsx'), 'export default 1;');
      const base = {entry:'main.tsx', appId:'com.example.icon', name:'Icon', versionName:'1', versionCode:1, capabilities:[], resources:{fontSource:'system'}};
      const defaults = {fontRegular:'unused.ttf', fontBold:'unused.ttf', outputRoot:out};
      for (const icons of [{icon:'@mipmap/ic_launcher', roundIcon:'@mipmap/ic_launcher_round'}, {icon:'@drawable/launcher'}, {roundIcon:'@drawable/round_launcher'}]) {
        writeFileSync(join(out, 'pod.config.json'), JSON.stringify({...base, android:icons}));
        const project = resolvePodProject(out, POD_TARGETS, defaults);
        expect(project.config.android).toEqual(icons);
        writeAndroidManifest(out, template, ['debug.acoustic'], project.config.android);
        const xml = readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8');
        const application = xml.match(/<application\b[^>]*>/)![0];
        for (const field of ['icon','roundIcon'] as const) {
          if (icons[field]) expect(application).toContain(`android:${field}="${icons[field]}"`);
          else expect(application).not.toContain(`android:${field}=`);
        }
        expect(xml.slice(xml.indexOf('<activity'))).toBe(template.slice(template.indexOf('<activity')));
        expect(permissionNames(xml)).toContain('android.permission.RECORD_AUDIO');
      }
      writeAndroidManifest(out, template, []);
      expect(readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8')).toBe(template);
    } finally { rmSync(out, {recursive:true, force:true}); }
  });
  test(`${platform}: explicit icons replace template defaults without duplicate attributes`, () => {
    const out = mkdtempSync(join(tmpdir(), 'pod-launcher-defaults-'));
    try {
      const withDefaults = template.replace('<application ', '<application android:icon="@drawable/default_icon" android:roundIcon=\'@drawable/default_round\' ');
      writeAndroidManifest(out, withDefaults, [], {icon:'@mipmap/ic_launcher', roundIcon:'@mipmap/ic_launcher_round'});
      const xml = readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8');
      expect(xml.match(/android:icon=/g)).toHaveLength(1);
      expect(xml.match(/android:roundIcon=/g)).toHaveLength(1);
      expect(xml).not.toContain('@drawable/default');
      writeAndroidManifest(out, withDefaults, []);
      expect(readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8')).toBe(withDefaults);
    } finally { rmSync(out, {recursive:true, force:true}); }
  });
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

test('inactive generation GC is wired from CLI through a default-off Gradle placeholder', () => {
  const cli = readFileSync(join(root, 'packages/cli/src/cli.ts'), 'utf8');
  const gradle = readFileSync(join(root, 'platforms/android/app/build.gradle.kts'), 'utf8');
  expect(cli).toContain('`-PpodTakeoverInactiveGenerationGc=${project.config.android?.takeover?.inactiveGenerationGc ?? ""}`');
  expect(gradle).toContain('manifestPlaceholders["podTakeoverInactiveGenerationGc"] = providers.gradleProperty("podTakeoverInactiveGenerationGc").orElse("").get()');
  const template = readFileSync(join(root, 'platforms/android/app/src/main/AndroidManifest.xml'), 'utf8');
  const metadata = '<meta-data android:name="dev.podjs.takeover.inactiveGenerationGc" android:value="${podTakeoverInactiveGenerationGc}" />';
  expect(template.split(metadata)).toHaveLength(2);
  const out = mkdtempSync(join(tmpdir(), 'pod-manifest-gc-'));
  try {
    for (const capabilities of [[], ['debug.acoustic']]) {
      writeAndroidManifest(out, template, capabilities);
      const xml = readFileSync(join(out, 'pod-android/AndroidManifest.xml'), 'utf8');
      expect(xml.split(metadata)).toHaveLength(2);
    }
  } finally { rmSync(out, { recursive: true, force: true }); }
});
