#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
javac -cp "$json" -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodGzipJson.java tests/java/dev/podjs/runtime/GzipJsonTest.java
bun scripts/test-gzip-json.ts "$work" "$json"
