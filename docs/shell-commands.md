# Shell command channel

Android/Wear apps expose `content://<applicationId>.podjs.shell` in release and
debug. Both ContentProvider `call` overloads check `Binder.getCallingUid()` in
the first statement. Only exactly 2000 or 0 passes. All other UIDs receive
`SecurityException("denied")` without reading configuration/arguments or emitting
logs. Other provider methods provide no data or file path. No URI grants exist.
Android API reference: https://developer.android.com/reference/android/content/ContentProvider#call(java.lang.String,%20java.lang.String,%20android.os.Bundle)

Declare code-authored commands at the root of `pod.config.json`:

```json
{"shellCommands":["player.open","player.state"]}
```

The CLI validates up to 64 unique dotted names and writes the allowlist into
`pod.manifest.json`. Undeclared methods return `unknown_command` before starting
the launcher or guest. An empty allowlist disables all commands.

```ts
import {registerShellCommands,ShellCommandError} from '@podjs/framework/shell';
const stop = registerShellCommands({
  'player.open': async (args, context) => {
    if (Object.keys(args).length) throw new ShellCommandError('invalid_argument');
    if (!context.isActive()) throw new ShellCommandError('unavailable');
    await openPlayer(); // application-owned action
    return {opened:true};
  },
  'player.state': () => ({playing:currentPlaying()}),
});
// Call stop() at the application's ownership boundary.
```

Only one handler registry exists per guest. Registration emits a ready event;
commands reach the ordinary main-thread guest queue. Calls without a registered runtime try the launcher and wait for registration.
Android background activity launch restrictions still apply. Start the launcher
explicitly with ADB before calls on Android 15 or when the app is in background. Apps should register after restoring startup
navigation. The FIFO admits eight calls, including an active call. Each Binder
caller waits at most 15 seconds including queue/registration delay. A timeout
removes queued work, cancels a running handler's context and ignores late results.
Handlers must recheck `context.isActive()` after awaited preparation and before
publishing side effects. Completed side effects cannot be rolled back. Runtime
detach rejects outstanding requests and discards readiness; commands never replay
across guest replacement. No synchronous Binder call may block the main thread.

```sh
adb -s emulator-5588 shell am start -W -n example.app/dev.podjs.androidwatch.MainActivity
adb -s emulator-5588 shell content call --uri content://example.app.podjs.shell --method player.state
adb -s emulator-5588 shell 'content call --uri content://example.app.podjs.shell --method player.open --arg '\''{}'\'''
```

Arguments must be a JSON object (8 Ki characters). Bundle keys are `ok`, `error`,
and `json`. Success has an empty error and serialized JSON. Failure has a fixed
code and `json="null"`. Result JSON is limited to 32 Ki characters. Host errors
and unrecognized handler errors become a fixed code without exception text.
`ShellCommandError` permits `invalid_argument`, `unavailable`, `not_ready`, and
`not_found`. The host also returns `unknown_command`, `busy`, `timeout`,
`command_failed`, and `result_too_large`.

Register bounded product operations only. Do not register arbitrary eval, SQL,
file operations, or access to cookies, accounts, credentials or tokens. Return
explicit field projections instead of serializing route/source/session objects.
The numeric `shellDiagnostics()` facade reports SQL worker totals, process PSS,
observed video decoder lifetimes and explicit video session counters; it exposes
no SQL text or row contents. `sql.gzipJson` has its own category.

Focused checks: `bash scripts/test-shell-commands.sh`,
`bun --conditions=browser test ./packages/framework/tests/shell.isolated.ts`, and
`:runtime:testDebugUnitTest --tests dev.podjs.runtime.PodShellProviderTest`.
