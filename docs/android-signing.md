# Android release signing and certificate rotation

The default `pod package --target=android-watch --project=/absolute/project` behavior is unchanged: assemble a debug-signed APK, a release bundle, and the runtime library. `--release` selects a non-debuggable release APK **when `android.signing` is configured**. Without signing configuration it retains the existing debug packaging behavior. Release bundles are not signed by this new APK signing path.

```json
{
  "android": {
    "signing": {
      "keystoreProperties": "../private/keystore.properties"
    }
  }
}
```

```sh
bun packages/cli/src/cli.ts package --release --target=android-watch --project=/absolute/project
```

Output: `dist/projects/<appId>/packages/android-watch/app-release.apk` in the framework checkout (or `wearos-watch` for that target). The APK is zipaligned before signing, and verified before publication. Both v2 and v3 are enabled; v1 and v4 are disabled. Signing refuses a debuggable APK unless `--release --debuggable` is explicitly selected. The Gradle release build does not contain a signing configuration or credential values.

The external Java properties file must have `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Java `Properties.load` syntax applies, including escapes and continuation lines. `storeFile` is relative to the **properties file's directory**, or absolute. Paths in `android.signing` are relative to the Pod project, or absolute. The CLI resolves paths but never reads credential files. Passwords are read only by an isolated Java worker and passed to apksigner through its child environment. Passwords are never included in command arguments; all signing tool output and credential-reader exceptions are suppressed, including on failures. Only fixed success/failure messages are public. The worker needs Java 17 or newer; tool discovery uses the newest installed stable Android SDK build-tools under `ANDROID_SDK_ROOT` or `ANDROID_HOME`.

Keep keystores and properties files outside repositories and build asset directories. Keep lineage files outside repositories as well. Signing lineage contains certificates and proof of rotation, with no private keys. Protect its directory (for example mode 700); the rotation helper writes mode 600 and refuses to replace an existing lineage. No credential files are copied into build directories. Use an increased `versionCode` and the same application ID when upgrading an installed app.

## Create a lineage

```sh
bun packages/cli/src/cli.ts signing rotate \
  --old-properties /private/old.properties \
  --new-properties /private/new.properties \
  --out /private/app.lineage
```

To rotate from the standard Android debug identity, explicitly use `--old-debug-keystore /private/debug.keystore` in place of `--old-properties`. This uses the publicly documented Android debug alias and passwords inside the worker. For other keystores, `--old-keystore <path> --old-key-alias <alias>` reads the environment variables named by `--old-store-pass-env` and `--old-key-pass-env` (defaults `POD_OLD_STORE_PASSWORD` and `POD_OLD_KEY_PASSWORD`). Supply those through a credential manager or launching subprocess, never as literal command arguments or in shell history. Prefer properties files for both signers.

## Package using the lineage

```json
{
  "android": {
    "signing": {
      "keystoreProperties": "/private/new.properties",
      "lineage": "/private/app.lineage",
      "rotationMinSdk": 28,
      "lineageSignerProperties": "/private/old.properties"
    }
  }
}
```

For rotation from Android debug, replace `lineageSignerProperties` with `lineageDebugKeystore`. These options are mutually exclusive. The oldest signer is required for v2 with lineage; v2 carries the oldest identity, while v3 carries the rotated identity and the proof of rotation. `rotationMinSdk` defaults to 28 (Android 9); use an integer >= 28. Values <= 32 use the original v3 signing block so rotation applies on Android 9 through 12L. A higher value opts into SDK-targeted rotation using v3.1 and leaves older supported platforms on the old signer. Rotation cannot authorize upgrades on platforms that do not support the scheme. Raising the rotation SDK later can also change which signer an older device sees; keep this policy consistent across subsequent releases.

```sh
apksigner verify --verbose --print-certs /path/app-release.apk
apksigner lineage --in /private/app.lineage --print-certs
```

`verify` reports schemes it actually used for the manifest SDK range. A minSdk >= 28 can report v2 false despite a valid embedded v2 block. To check that block independently, verify with `--min-sdk-version 24 --max-sdk-version 27`; this is a cryptographic check, not a change to the APK's actual supported SDKs. The apksig `SigningCertificateLineage.readFromApkFile` API checks the lineage embedded in the APK and its installed-data capability. The framework tests generate temporary keystores and APKs, verify both schemes, check the embedded lineage against the old APK certificate, and ensure passwords cannot escape via argv or hostile signer output. Android SDK tools are needed for these integration cases; absent an SDK, they are explicitly skipped.

Certificate and lineage checks establish signing continuity. A data-preserving upgrade on the target device remains a separate acceptance step. Release builds also disable host features gated by `ApplicationInfo.FLAG_DEBUGGABLE` and `application.buildInfo().debugBuild`; review app debug gates and any explicit source profiling overrides before release.

References: [Android apksigner](https://developer.android.com/tools/apksigner), [apksigner signing options](https://android.googlesource.com/platform/tools/apksig/+/master/src/apksigner/java/com/android/apksigner/help_sign.txt).

## Temporary data-verification packages

`package --release --debuggable` retains the release build type, signing identity and rotation policy, changing only the debuggable flag. It requires configured `android.signing`. **Do not publish this APK:** debugging access and debug-only host/app gates are enabled. Ordinary `--release` remains non-debuggable, including after a verification build.

`--version-code N` overrides the project versionCode in memory for one Android packaging invocation, including generated build metadata; it never edits pod.config.json. N must be an integer from 1 through 2100000000.

A device acceptance owner can stop an installed debuggable app, record a protected data digest, install a non-debuggable release, then install a higher-version release-signed debuggable verification APK without launching either. The verification APK restores run-as access for the after digest. Require strict before/after equality. Afterwards install a higher-version ordinary non-debuggable release before launching or measuring performance. Keep application ID, signer/lineage and content consistent across the chain. A refused step must stop the chain; never uninstall, clear data or downgrade to recover automatically. This procedure proves preservation across installation, not data/schema changes caused by later app startup.

```sh
bun packages/cli/src/cli.ts package --release --version-code 2 --target=android-watch --project=/absolute/project
# Retain the APK outside the packaged asset directory before the next invocation.
bun packages/cli/src/cli.ts package --release --debuggable --version-code 3 --target=android-watch --project=/absolute/project
bun packages/cli/src/cli.ts package --release --version-code 4 --target=android-watch --project=/absolute/project
```
