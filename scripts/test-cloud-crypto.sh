#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodCloudCrypto.java tests/java/PodCloudCryptoTest.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodSoftwareEcdh.java tests/java/dev/podjs/runtime/SoftwareEcdhTest.java
java -cp "$task_tmp" PodCloudCryptoTest "$@"

java -cp "$task_tmp" dev.podjs.runtime.SoftwareEcdhTest
