#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Compile the actual Android implementation. Run JVM-compatible secure host
# operations with a synthetic key; never load AndroidKeyStore or device data.
(cd platforms/android && ./gradlew :runtime:compileReleaseJavaWithJavac)
secure_work=$(mktemp -d)
trap 'rm -rf "$secure_work"' EXIT
secure_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/wilf/Android/Sdk}}
secure_json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
secure_classes=platforms/android/runtime/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes
secure_cp="$secure_work:$secure_json:$secure_classes:$secure_sdk/platforms/android-36/android.jar"
javac -cp "$secure_cp" -d "$secure_work" tests/java/dev/podjs/runtime/{SecureDecodeTraceTest,SecureStoreCapacityTest}.java
java -Xmx64m -ea -cp "$secure_cp" dev.podjs.runtime.SecureDecodeTraceTest
java -Xmx64m -ea -cp "$secure_cp" dev.podjs.runtime.SecureStoreCapacityTest

# JVM Base64 shim only; execute the actual compiled native-service dispatcher.
javac -cp "$secure_cp" -d "$secure_work" tests/java/stubs/android/util/Base64.java tests/java/dev/podjs/runtime/SealedPartsServiceTest.java
java -Xmx128m -ea -cp "$secure_cp" dev.podjs.runtime.SealedPartsServiceTest
