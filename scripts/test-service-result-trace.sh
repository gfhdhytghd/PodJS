#!/usr/bin/env bash
set -euo pipefail
trace_root=$(cd "$(dirname "$0")/.." && pwd)
trace_out=$(mktemp -d)
trap 'rm -rf "$trace_out"' EXIT
javac -d "$trace_out" "$trace_root/platforms/android/runtime/src/main/java/dev/podjs/runtime/ServiceResultTrace.java" "$trace_root/tests/ServiceResultTraceTest.java" "$trace_root/platforms/android/runtime/src/main/java/dev/podjs/runtime/DeviceServiceTrace.java" "$trace_root/tests/DeviceServiceTraceTest.java"
java -cp "$trace_out" dev.podjs.runtime.ServiceResultTraceTest

java -cp "$trace_out" dev.podjs.runtime.DeviceServiceTraceTest
