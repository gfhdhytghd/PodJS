#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverCoordinator.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverAdmission.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverSource.java tests/java/dev/podjs/runtime/TakeoverTest.java
java -ea -cp "$classes" dev.podjs.runtime.TakeoverTest
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java tests/java/dev/podjs/takeover/NativeBackupTest.java
java -ea -cp "$classes" dev.podjs.takeover.NativeBackupTest
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/BootSurface.java tests/java/dev/podjs/runtime/BootSurfaceTest.java
java -ea -cp "$classes" dev.podjs.runtime.BootSurfaceTest
bash scripts/test-room-v18.sh

bash scripts/test-stores.sh
