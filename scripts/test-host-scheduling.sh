#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/{HostTurnQueue,HostEffectPump,MainThreadWatchdog}.java tests/java/dev/podjs/runtime/HostSchedulingTest.java
java -cp "$work" dev.podjs.runtime.HostSchedulingTest

javac -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/HostCallsiteSummary.java tests/java/dev/podjs/runtime/HostCallsiteSummaryTest.java
java -ea -cp "$work" dev.podjs.runtime.HostCallsiteSummaryTest
