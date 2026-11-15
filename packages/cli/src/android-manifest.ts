import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// These permissions belong to acoustic capture / guided Wi-Fi, rather than the
// shared runtime. Capability availability in a host profile is not app consent.
const acousticPermissions = `    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
`;

export interface AndroidLauncherIcons {
  icon?: string;
  roundIcon?: string;
}

/** Only local drawable/mipmap references can enter manifest attributes. */
export function validateAndroidIcon(value: unknown, field: keyof AndroidLauncherIcons): string {
  if (typeof value !== 'string' || !/^@(mipmap|drawable)\/[a-z][a-z0-9_]*$/.test(value) || value.endsWith('\n')) {
    throw new Error(`Invalid android.${field}: expected a local @mipmap or @drawable resource`);
  }
  return value;
}

/** Generate the host main manifest from its template and explicit app requests. */
export function writeAndroidManifest(out: string, hostTemplate: string, capabilities: readonly string[], icons: AndroidLauncherIcons = {}): void {
  if (!/<application\b/.test(hostTemplate)) throw new Error('Android host manifest has no application');
  let manifest = capabilities.includes('debug.acoustic')
    ? hostTemplate.replace(/<application\b/, acousticPermissions + '    <application')
    : hostTemplate;
  // Launcher activities inherit application icons. Always start from the host
  // template so removing an option also removes it from incremental output.
  for (const field of ['icon', 'roundIcon'] as const) {
    if (icons[field] === undefined) continue;
    const reference = validateAndroidIcon(icons[field], field);
    manifest = manifest.replace(/<application\b[^>]*>/, tag => {
      const clean = tag.replace(new RegExp(`\\sandroid:${field}=("[^"]*"|'[^']*')`, 'g'), '');
      return clean.replace(/>$/, ` android:${field}="${reference}">`);
    });
  }
  const directory = join(out, 'pod-android');
  mkdirSync(directory, { recursive: true });
  // Rewrite from the base template, so removing a capability cannot leave a
  // permission behind in an incremental build.
  writeFileSync(join(directory, 'AndroidManifest.xml'), manifest);
}
