#!/usr/bin/env bash
# Build from this checkout only. No prior/unmerged probe binary is required.
set -euo pipefail
cd "$(dirname "$0")/.."
perf_out=${1:?output directory required}
mkdir -p "$perf_out/classes" "$perf_out/dex"
perf_out=$(realpath "$perf_out")
perf_sdk=${ANDROID_SDK_ROOT:-/home/wilf/Android/Sdk}
./platforms/android/gradlew -p platforms/android :runtime:compileReleaseJavaWithJavac -PpodAbis=x86_64
perf_classes=platforms/android/runtime/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes
javac -cp "$perf_sdk/platforms/android-34/android.jar:$perf_classes" -d "$perf_out/classes" tests/java/dev/podjs/runtime/CloudPerf2{Guest,Storage,Wait}Probe.java
jar cf "$perf_out/probes.jar" -C "$perf_classes" . -C "$perf_out/classes" .
"$perf_sdk/build-tools/35.0.0/d8" --min-api 26 --lib "$perf_sdk/platforms/android-34/android.jar" --output "$perf_out/dex" "$perf_out/probes.jar"
