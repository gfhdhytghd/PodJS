#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
provision_tmp=$(mktemp -d)
trap 'rm -rf "$provision_tmp"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
javac -cp "$json" -d "$provision_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/ProvisioningSession.java platforms/android/runtime/src/main/java/dev/podjs/runtime/BleSetupCrypto.java platforms/android/runtime/src/main/java/dev/podjs/runtime/OnlineProvisioningSession.java tests/java/dev/podjs/runtime/ProvisioningTest.java
java -ea -cp "$json:$provision_tmp" dev.podjs.runtime.ProvisioningTest
