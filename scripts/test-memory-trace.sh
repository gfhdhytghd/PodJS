#!/usr/bin/env bash
set -euo pipefail
memory_trace_root=$(cd "$(dirname "$0")/.." && pwd)
memory_trace_out=$(mktemp -d)
trap 'rm -rf "$memory_trace_out"' EXIT
javac -d "$memory_trace_out" "$memory_trace_root/platforms/android/runtime/src/main/java/dev/podjs/runtime/MemoryTraceScheduler.java" "$memory_trace_root/tests/MemoryTraceSchedulerTest.java"
java -cp "$memory_trace_out" dev.podjs.runtime.MemoryTraceSchedulerTest
