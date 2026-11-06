#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
font_tmp=$(mktemp -d)
trap 'rm -rf "$font_tmp"' EXIT
font_json=$(python3 - <<'PY'
from pathlib import Path
print(next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('**/*.jar')))
PY
)
javac -cp "$font_json" -d "$font_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodPakFontEntry.java tests/java/dev/podjs/runtime/PakFontEntryTest.java
java -ea -cp "$font_json:$font_tmp" dev.podjs.runtime.PakFontEntryTest
