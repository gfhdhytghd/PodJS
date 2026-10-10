#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodActiveValidation.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverCoordinator.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverAdmission.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverSource.java tests/java/dev/podjs/runtime/TakeoverTest.java tests/java/dev/podjs/runtime/ActiveValidationTest.java
java -ea -cp "$classes" dev.podjs.runtime.TakeoverTest
java -ea -cp "$classes" dev.podjs.runtime.ActiveValidationTest
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java tests/java/dev/podjs/takeover/NativeBackupTest.java
python3 scripts/test-recovery-native-backup.py
java -ea -cp "$classes" dev.podjs.takeover.NativeBackupTest
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java platforms/android/runtime/src/main/java/dev/podjs/takeover/TakeoverDigest.java tests/java/dev/podjs/takeover/TakeoverDigestTest.java
java -ea -cp "$classes" dev.podjs.takeover.TakeoverDigestTest tests/fixtures/takeover-digest-v1.tsv
javac -cp "$classes" -d "$classes" platforms/android/runtime/src/test/java/dev/podjs/takeover/LegacyTakeoverDigest.java platforms/android/runtime/src/test/java/dev/podjs/takeover/DigestDifferential.java
java -ea -cp "$classes" dev.podjs.takeover.DigestDifferential
javac -d "$classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/BootSurface.java tests/java/dev/podjs/runtime/BootSurfaceTest.java
java -ea -cp "$classes" dev.podjs.runtime.BootSurfaceTest
bash scripts/test-room-v18.sh

bash scripts/test-stores.sh
