#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/{ShellCommandQueue,ServiceWorkStats}.java tests/java/dev/podjs/runtime/ShellCommandQueueTest.java
java -ea -cp "$work" dev.podjs.runtime.ShellCommandQueueTest
bun test tests/shell-config.test.ts
bun --conditions=browser test ./packages/framework/tests/shell.isolated.ts
