#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
idle_test_tmp=$(mktemp -d)
trap 'rm -rf "$idle_test_tmp"' EXIT
idle_json=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)
javac -cp "$idle_json" -d "$idle_test_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/{IdleDiagnostics,ServiceWorkStats}.java tests/java/dev/podjs/runtime/IdleDiagnosticsTest.java
java -ea -cp "$idle_test_tmp:$idle_json" dev.podjs.runtime.IdleDiagnosticsTest
