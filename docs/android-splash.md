# Android system splash

Declare a host splash in pod.config.json:

```json
{"android":{"splash":{"background":"#000000","icon":"@drawable/launch_icon","iconBackground":"#000000","exit":{"fade":true,"scale":1.12,"durationMs":80}}}}
```

Provide the drawable through android.resources. android.theme is the ordinary
post-splash Activity theme (default AppTheme); the CLI generates Theme.PodJS.Splash
with parent Theme.SplashScreen.IconBackground (a Theme.SplashScreen child) and
that postSplashScreenTheme, so iconBackground is effective. Both Android watch
and Wear hosts package the generated resources and select the starting theme.
API 31+ uses the platform provider; API 30 uses androidx core-splashscreen compat.
An animated drawable animates only where the AndroidX/platform API supports it.
Configuration validation bounds colors/resource references, exit duration to
0..2000ms, scale to 1..2, and timeoutMs to 1..30000ms (default 8000ms).

The Activity installs the splash before super.onCreate. AndroidX's keep predicate
blocks preDraw; waiting there for a TextureView frame would also prevent creating
its surface and booting the guest. PodJS lets preDraw proceed, receives the native
SplashScreenViewProvider and retains it above the Activity until the host gate
opens. The app draws behind that provider, rather than mounting a guest overlay.
This follows the provider's explicit remove ownership described in the
[AndroidX source](https://github.com/androidx/androidx/blob/androidx-main/core/core-splashscreen/src/main/java/androidx/core/splashscreen/SplashScreen.kt).

By default a nonempty guest render snapshot and TextureView update open the gate.
Applications requiring data readiness can call:

```ts
import {hold, release} from '@podjs/framework/splash';
void hold('initial-data');
// Load the first screen, publish its contents, then:
void release('initial-data');
```

These ordinary host.lifecycle services reject unsupported hosts or an unconfigured
Activity; callers should handle rejection. Holds are idempotent per owner, at most
32 owners. Releasing a real hold waits for another presented texture callback so
an old loading frame does not open the splash. No command can resurrect an exited
splash or extend the absolute Activity timeout. Missing calls still retain the
first-content default; missing release eventually times out. Timeout can expose
an incomplete guest if startup has failed, but cannot conceal failure forever.

Actual migration/blocked/paused RecoveryView states bypass holds and first-frame
readiness immediately; normal ACTIVE/no-source CHECKING remains covered.
The configured short exit exposes progress. No guest boots before the existing
admission gate succeeds. The exit animates the provider view's alpha/scale exactly
once and removes it after completion; an end fallback and Activity destruction
remove it even if Android cancels an animation callback. Activity recreation gets
its own state, without global guest holds surviving a dead Activity.

PodSplash release logs use fixed reasons/content, recovery, timeout and numeric
exit settings; no app data or owner identifiers are logged. Texture callback and
nonempty snapshot metadata indicate guest presentation; they do not prove all
screen data is complete, which remains application hold/release policy.
