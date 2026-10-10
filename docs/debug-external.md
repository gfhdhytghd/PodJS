# Debug-only Android external entry points

Declare `android.debugExternal` in pod.config.json with fully qualified component names:

```json
{"android":{"debugExternal":{
  "activities":[{"name":"example.app.DebugLauncher","action":"example.app.debug.OPEN"}],
  "receiver":{"name":"example.app.DebugReceiver","actions":["example.app.debug.REPORT"]},
  "provider":{"name":"example.app.DebugProvider","authority":"example.app.debug"}
}}}
```

Activity aliases dispatch their configured action, preserving primitive intent extras, then bring MainActivity forward. Receivers dispatch only listed actions and support ordered result codes/data. Provider dump arguments become a dump event; its CRUD methods are empty. Component classes and bridge implementation are generated into a debug source set outside the runtime asset directory. Ordinary release has neither manifest entries nor these classes; `--release --debuggable` explicitly opts into verification-only debug behavior.

Guest code uses `debugExternal.listen(async event => ({code:0,data:'...'}))` from `@podjs/framework/services`, then calls its returned disposer on owner teardown. The service reads real buildInfo and rejects non-debug hosts. Android checks FLAG_DEBUGGABLE independently. `event` includes id, kind (`intent` or `dump`), action, primitive extras, args and deadline. Application-specific allowlisting and authorization remain in the application. Handlers must never echo secrets or serialize arbitrary error objects. The SDK returns a fixed failure for thrown exceptions.

The host bounds pending requests at 32 and expires them after four seconds; late guest events are dropped. Intent extras are limited to 32 primitive fields, each string representation <=16k characters. Reply text is truncated to 60k characters. Pending requests are process-memory only and are closed on runtime disposal. Cold activity requests wait for guest subscription; broadcasts/provider dumps do not automatically start the UI or bypass onboarding. A guest timeout cannot roll back an asynchronous application action already in flight. This API is a foreground debug diagnostic bridge, not a persistent background job mechanism.

Tests: `bun test tests/debug-external.test.ts`; `bun --conditions=browser test ./packages/framework/tests/debug-external.isolated.ts`. APK manifest and DEX inspection is still required for each real packaging configuration. Neither unit tests nor package inspection establishes device dispatch behavior.

Result-capable aliases opt in with `{"name":"example.app.ResultEntry","action":"example.app.debug.OPEN","result":true,"timeoutMs":120000}`. `timeoutMs` is an integer from 4000 to 600000 (default 120000); it is only accepted for result aliases. The guest replies with `result:{code:-1,extras:{remote_url:"https://example.com/rss"}}` in addition to optional diagnostic code/data. Result code is an Android Activity result code, separate from the diagnostic code. Result extras support string, boolean and numeric values only, at most 32 keys (<=256 characters), <=16k characters per value and <=60k characters total. Invalid values, missing result, timeout, runtime closure or activity destruction cancel without extras. Late replies have no effect. The owner is retained under MainActivity via REORDER_TO_FRONT, so CLEAR_TOP cannot destroy the pending result. Configuration recreation cancels rather than replays a diagnostic action. A result may be observed by a permission-authorized startActivityForResult caller; shell `am start` alone does not establish result delivery.
