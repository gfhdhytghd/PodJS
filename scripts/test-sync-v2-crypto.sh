#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
syncv2_classes=$(mktemp -d)
trap 'rm -rf "$syncv2_classes"' EXIT
javac -d "$syncv2_classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/{PodCloudCrypto,PodSyncV2Crypto}.java tests/java/dev/podjs/runtime/SyncV2CryptoTest.java
java -cp "$syncv2_classes" dev.podjs.runtime.SyncV2CryptoTest tests/fixtures/syncv2.tsv
