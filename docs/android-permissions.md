# Android permission generation

The Android and Wear OS CLI build/package commands generate
`pod-android/AndroidManifest.xml` in the target output directory. The normal
Gradle host uses it as its main manifest, alongside variant-specific manifests.
Generation starts from the host template on every build, preserving Activity,
theme, startup and takeover configuration and removing stale optional grants.
Direct Gradle builds without a generated manifest use the base host manifest.

Only an explicit `debug.acoustic` entry in the application's `pod.config.json`
`capabilities` requests `RECORD_AUDIO`, `CHANGE_WIFI_STATE`,
`CHANGE_NETWORK_STATE` and `NEARBY_WIFI_DEVICES` (`neverForLocation`). These
permissions support acoustic capture and guided Wi-Fi. The host target profile
lists available services; it does not grant permission on behalf of an app.
Projectless CLI builds therefore request none of these optional permissions.

Shared Bluetooth/location, network status, IP discovery multicast and media
playback permissions remain in the runtime manifest. Service implementations
are retained; apps opting out cannot use permission-protected acoustic capture
or guided Wi-Fi operations.
