#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
sqlite=${SQLITE_JDBC_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.46.1.3" -name '*.jar' -print -quit)}
slf4j=${SLF4J_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.30" -name '*.jar' -print -quit)}
cp="$work:$json:$sqlite:$slf4j:platforms/android/runtime/src/main/resources"
# SecureStoreLimits folds constants from SecureStoreCipher; resolve that source against android.jar and emit its classes.
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
javac -cp "$cp:$sdk/platforms/android-36/android.jar" -sourcepath platforms/android/runtime/src/main/java -implicit:class -d "$work" platforms/android/runtime/src/main/java/dev/podjs/runtime/SecureStoreLimits.java
javac -cp "$cp" -d "$work" platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java platforms/android/runtime/src/main/java/dev/podjs/takeover/TakeoverDigest.java platforms/android/runtime/src/main/java/dev/podjs/runtime/{PodActiveValidation,PodTakeoverSource,PodTakeoverAdmission,PodTakeoverCoordinator,PodRoomV18Converter,PodTakeoverConverters,PodFileConverter,PodPreferenceReader,PodStoreConverter,PodStoreMappings,PodNativeResume,PodStorePolicy}.java tests/java/dev/podjs/runtime/{StoreTest,NativeResumeTest,RoomV18Test,StoreRoomTest,FileTakeoverTest,CanonicalTakeoverTest,StorePolicyTest}.java
java -ea -cp "$cp" dev.podjs.runtime.StoreTest "$work/oracle"
bun scripts/store-consumer-oracle.ts "$work/oracle"
java -ea -cp "$cp" dev.podjs.runtime.StoreRoomTest

java -ea -cp "$cp" dev.podjs.runtime.FileTakeoverTest "$work/files-oracle"
bun scripts/file-consumer-oracle.ts "$work/files-oracle"

java -ea -cp "$cp" dev.podjs.runtime.CanonicalTakeoverTest

java -ea -cp "$cp" dev.podjs.runtime.StorePolicyTest

java -ea -cp "$cp" dev.podjs.runtime.NativeResumeTest
