#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" \
  platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java \
  platforms/android/runtime/src/main/java/dev/podjs/takeover/TakeoverDigest.java \
  platforms/android/runtime/src/test/java/dev/podjs/takeover/LegacyTakeoverDigest.java \
  platforms/android/runtime/src/test/java/dev/podjs/takeover/DigestDifferential.java
java -Xmx1g -ea -cp "$classes" dev.podjs.takeover.DigestDifferential benchmark
