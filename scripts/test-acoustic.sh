#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodPcmSession.java tests/java/dev/podjs/runtime/PcmSessionTest.java
java -ea -cp "$task_tmp" dev.podjs.runtime.PcmSessionTest
