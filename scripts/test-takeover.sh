#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverCoordinator.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverAdmission.java tests/java/dev/podjs/runtime/TakeoverTest.java
java -ea -cp "$classes" dev.podjs.runtime.TakeoverTest
bash scripts/test-room-v18.sh
