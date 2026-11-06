#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
artifact_tmp=$(mktemp -d)
trap 'rm -rf "$artifact_tmp"' EXIT
javac -d "$artifact_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodUtf8Artifact.java tests/java/dev/podjs/runtime/Utf8ArtifactTest.java
java -ea -cp "$artifact_tmp" dev.podjs.runtime.Utf8ArtifactTest
