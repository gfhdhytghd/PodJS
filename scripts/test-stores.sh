#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
sqlite=${SQLITE_JDBC_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.46.1.3" -name '*.jar' -print -quit)}
slf4j=${SLF4J_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.30" -name '*.jar' -print -quit)}
cp="$work:$json:$sqlite:$slf4j:platforms/android/runtime/src/main/resources"
javac -cp "$cp" -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/{PodTakeoverCoordinator,PodRoomV18Converter,PodTakeoverConverters,PodPreferenceReader,PodStoreConverter,PodStoreMappings}.java tests/java/dev/podjs/runtime/{StoreTest,RoomV18Test,StoreRoomTest}.java
java -ea -cp "$cp" dev.podjs.runtime.StoreTest "$work/oracle"
bun scripts/store-consumer-oracle.ts "$work/oracle"
java -ea -cp "$cp" dev.podjs.runtime.StoreRoomTest
