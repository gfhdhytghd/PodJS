#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
app=${WATCHRSS_APP_SOURCE:-/home/wilf/data/OPPO_Develop/PodJS_WatchRSS}
bun scripts/export-takeover-schema.ts "$app" --check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Offline JVM dependencies already resolved by local Gradle. Overrides support CI.
sqlite=${SQLITE_JDBC_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.46.1.3" -name '*.jar' -print -quit)}
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
slf4j=${SLF4J_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.30" -name '*.jar' -print -quit)}
cp="$sqlite:$json:$slf4j:$work/classes:platforms/android/runtime/src/main/resources"
mkdir -p "$work/classes"
javac -cp "$cp" -d "$work/classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodActiveValidation.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodTakeoverCoordinator.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodRoomV18Converter.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodRoomV19Converter.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodRoomVersionsConverter.java tests/java/dev/podjs/runtime/RoomV18Test.java tests/java/dev/podjs/runtime/RoomV19Test.java
java -ea -cp "$cp" dev.podjs.runtime.RoomV18Test "$work/fixture"
python3 scripts/room-v18-oracle.py "$app" "$work/fixture"

java -ea -cp "$cp" dev.podjs.runtime.RoomV19Test "$work/v19"
