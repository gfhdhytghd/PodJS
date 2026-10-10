import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// These permissions belong to acoustic capture / guided Wi-Fi, rather than the
// shared runtime. Capability availability in a host profile is not app consent.
const acousticPermissions = `    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
`;

/** Generate the host main manifest from its template and explicit app requests. */
export function writeAndroidManifest(out: string, hostTemplate: string, capabilities: readonly string[]): void {
  if (!/<application\b/.test(hostTemplate)) throw new Error('Android host manifest has no application');
  const manifest = capabilities.includes('debug.acoustic')
    ? hostTemplate.replace(/<application\b/, acousticPermissions + '    <application')
    : hostTemplate;
  const directory = join(out, 'pod-android');
  mkdirSync(directory, { recursive: true });
  // Rewrite from the base template, so removing a capability cannot leave a
  // permission behind in an incremental build.
  writeFileSync(join(directory, 'AndroidManifest.xml'), manifest);
}
