# Installed sync boot test

This uses a separate test application ID and the normal Android MainActivity,
native package validation, guest service dispatch and installed sync owner.
It verifies guest state set/get, offline message enqueue, file write/offer/status/
cancel and the cold-start settings entry. Actual settings buttons approve a
temporary peer, establish an authenticated loopback LAN session, receive a remote
state change in the real guest subscription, and disconnect. The installed owner
is never injected. The test revokes its host-side pairing afterward.
Both endpoints are on one device: this is not wireless pairing/connection
acceptance. Offline messages use a separate test peer and expire after one minute.

From the repository root:

```sh
bash scripts/build-android-runtime.sh
bun packages/cli/src/cli.ts build android-watch --project "$PWD/tests/fixtures/sync-apk"
```

From `platforms/android`, with an Android device connected:

```sh
./gradlew :androidApp:connectedDebugAndroidTest \
  -PpodAppId=dev.podjs.syncboottest \
  -PpodDistDir="$PWD/../../dist/projects/dev.podjs.syncboottest/android-watch" \
  -PpodTestSourceDir="$PWD/../../tests/android-sync" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.podjs.runtime.InstalledSyncTest \
  --offline
```

Rebuild the native runtime when the PocketJS revision changes. Package validation
intentionally rejects a bundle paired with an older prebuilt native revision.
The test requires a device capable of the host's Vulkan renderer. OWW242 success
does not prove Wear OS behavior or cross-device wireless acceptance.

The same test resolves the installed package's launcher, so it can exercise the
Wear host without depending on the Android host Activity class. Build the fixture
with `build wearos-watch` instead, then run from `platforms/android`:

```sh
./gradlew :wearApp:connectedDebugAndroidTest \
  -PpodAppId=dev.podjs.syncwearboottest \
  -PpodDistDir="$PWD/../../dist/projects/dev.podjs.syncboottest/wearos-watch" \
  -PpodTestSourceDir="$PWD/../../tests/android-sync" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.podjs.runtime.InstalledSyncTest \
  --offline
```

The separate application ID keeps Android and Wear fixture data independent.
Running the Wear APK on a non-Wear Android watch proves only that APK's generic
host path, not Wear services, Data Layer or Wear OS acceptance.

`InstalledSyncPermissionTest` is a separate real-system permission-denial test.
Run it with a fresh test application ID (for example `dev.podjs.syncpermissiontest`)
and its class name in the instrumentation argument. It requires initially denied
operation permissions and an unobstructed PermissionController window. It checks
panel closure, secret clearing and no automatic scan after denial; it never grants
permissions through shell identity. A charging/system overlay is a failed device
precondition, not permission-flow acceptance. OWW242 currently requires its USB
cable reconnected to clear the long-charging overlay before this test can finish.
