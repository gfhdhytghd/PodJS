#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/{ServiceResultTrace,SlowServiceCallbacks}.java tests/java/dev/podjs/runtime/SlowServiceCallbacksTest.java
java -cp "$work" dev.podjs.runtime.SlowServiceCallbacksTest
cargo test --manifest-path vendor/pocketjs/engine/Cargo.toml -p pocket-mod --test stall_diagnostics -- --nocapture
cargo test -p podjs-runtime stall_diagnostics
bun test packages/framework/tests/service-callback-trace.test.ts
