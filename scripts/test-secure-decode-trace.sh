#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Compile the production Android library first, then run only the JVM-compatible
# decoder against a synthetic AES key. No AndroidKeyStore/device or app data.
(cd platforms/android && ./gradlew :runtime:compileReleaseJavaWithJavac)
trace_work=$(mktemp -d)
trap 'rm -rf "$trace_work"' EXIT
trace_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/wilf/Android/Sdk}}
trace_json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
trace_classes=platforms/android/runtime/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes
trace_cp="$trace_work:$trace_json:$trace_classes:$trace_sdk/platforms/android-36/android.jar"
javac -cp "$trace_cp" -d "$trace_work" tests/java/dev/podjs/runtime/SecureDecodeTraceTest.java
java -ea -cp "$trace_cp" dev.podjs.runtime.SecureDecodeTraceTest
