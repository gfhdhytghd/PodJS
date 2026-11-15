# Android launcher icons

Declare launcher icons separately from the system splash in `pod.config.json`:

```json
{
  "android": {
    "resources": "android/res",
    "icon": "@mipmap/ic_launcher",
    "roundIcon": "@mipmap/ic_launcher_round"
  }
}
```

Both fields are optional. Each accepts a local `@mipmap/name` or
`@drawable/name` reference with a lowercase Android resource name. Supply the
referenced files, density variants and adaptive-icon dependencies through
`android.resources`. Android resource linking rejects missing resources.

The CLI writes `android:icon` and `android:roundIcon` on the generated host
`<application>` for both `android-watch` and `wearos-watch`. Launcher activities
inherit these application icons. Omitted fields preserve the host template;
rebuilding without them removes earlier generated overrides.

`android.splash.icon` only selects the splash image. It does not declare a
launcher icon.
