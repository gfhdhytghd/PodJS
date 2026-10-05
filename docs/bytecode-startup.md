# Android bytecode startup profiling

The CLI compiles the global IIFE bundle with the pinned rquickjs 0.12.2 / QuickJS
bytecode version 26. Native bytecode loading authenticates its SHA-256, byte
length, format and source hash against `pod.manifest.json` before mount or
`JS_ReadObject`. The execution receipt retains the authenticated bytecode digest.

Android-watch and WearOS store `.qbc` assets without ZIP deflate. This lets
`AAsset_getBuffer` use mapped bytes instead of inflating the entire bytecode at
boot. SHA-256 and the engine's own bytecode checksum are still verified.
Older compressed APKs remain readable through the existing AssetManager path.
Stored bytecode increases APK size; it eliminates an inflate buffer at startup.

Projects may explicitly opt in to removing duplicate function source strings:

```json
"bytecode": { "stripSource": true }
```

The default is to keep source strings. Stripping retains function names, local
and closure metadata, filenames, line/column positions and pc-to-line maps.
`Error.stack` locations remain available. `Function.prototype.toString()` for
compiled functions instead returns QuickJS's native-code representation; a
project that inspects or serializes function source must keep the default.
`main.js` remains packaged for source fallback and diagnostics. The manifest
records `sourceIncluded` and `debugIncluded`; SHA-256 covers the actual qbc bytes.
No additional runtime assets or manifest entry exceptions are introduced.

## Device timings

`PodJSPerf boot` continues to report total `evalMs`. A companion `bytecode` line
reports `assetMs`, `hashMs`, `mountMs`, `readObjectMs`,
`instantiateExecuteMs`, `drainJobsMs`, `finishMs` and byte length. The six runtime
phases are also exposed by the prefix-sized `PodEvalStats` API.

Global IIFE bundles have no separate ES-module linking phase. The public
`JS_EvalFunction` instantiates the top-level global function then calls it;
its timing includes both. Nested function creation and bundled module factories
run as normal top-level execution. The offline probe separates the initial
`js_closure` step; it never runs a PodJS app without its native host modules.

Only the device-owning agent should run device operations. After the authorized
`acceptance.py upgrade` and successful before/after data digest comparison, that
agent can capture at least five cold-process starts:

```sh
adb -s e3aca8af logcat -c
adb -s e3aca8af shell am force-stop com.lightningstudio.watchrss.podjs
adb -s e3aca8af shell am start -W -n com.lightningstudio.watchrss.podjs/dev.podjs.androidwatch.MainActivity
adb -s e3aca8af logcat -d -s PodJSPerf:I PodJS:I AndroidRuntime:E '*:S'
```

Record the median and range of `evalMs` and each bytecode phase, require
`evalMode=bytecode`, `pakIntegrity=entry-sha256`, `integrityFailures=0` and a normal
home frame, and compare identical installed-data/host-build conditions.
Bytecode evaluation under 250 ms is a device acceptance target, not a Linux or
QEMU guarantee. A force-stop start is process-cold; filesystem caches can remain
warm. Source fallback is not an optimization pass.

## Offline reproduction

Use one built distribution's `main.js` to compile both variants. Keep its pak
and all other assets unchanged; update each variant's manifest bytecode length
and SHA-256. The ignored Rust production-runtime harness checks the manifest and
measures SHA-256, mount, deserialize, instantiate/execute and finish:

```sh
PODJS_POCKETJS_REVISION=$(git -C vendor/pocketjs rev-parse HEAD) \
PODJS_PROFILE_DIST=/absolute/dist PODJS_PROFILE_BYTECODE=1 PODJS_PROFILE_ITERATIONS=11 \
cargo test -p podjs-runtime profile_bundle_compile_execute_and_modules -- --ignored --nocapture
```

`compile-bytecode <main.js> <main.qbc> [--strip-source]` produces either variant.
Its tests cover identical closure/direct-eval results and exact error stacks,
while recording the intentional Function.toString difference.

```sh
cargo test -p podjs-runtime --example compile-bytecode
scripts/profile-bytecode.sh /absolute/baseline-dist /absolute/stripped-dist /absolute/log-dir
```

The standalone C probe includes the exact cached engine to split
checksum/atom-table work, function-tree deserialization, and `js_closure`.
It reports warm-cache file mapping/inflate and pre-execution times, atom/function
counts and instruction bytes. Its atom reader follows `JS_ReadObject2` including
checksum validation and cleanup. It is not linked into the Android runtime.

An optional ARMv7 musl sysroot with zlib and a hard-float `libgcc.a` enables the
same binary under the installed QEMU user emulator:

```sh
PROBE_ARM_SYSROOT=/absolute/armv7-musl-sysroot \
PROBE_ARM_GCCLIB=/usr/lib/gcc/arm-none-eabi/16.2.0/thumb/v7+fp/hard/libgcc.a \
scripts/profile-bytecode.sh /absolute/baseline-dist /absolute/stripped-dist /absolute/log-dir
```

The script does not download dependencies or access devices. ARM QEMU times
reflect host emulation, libc, allocator and cache behavior; they cannot predict
OWW242 wall time. The four stored/deflated and full/stripped combinations help
separate packaging from source-size effects. Use Android logs for device-stage
attribution and the Rust harness for actual mounted-host top-level execution.
