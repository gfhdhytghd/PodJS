#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
mkdir -p "$task_tmp/android/content" "$task_tmp/android/util" "$task_tmp/dev/podjs/runtime" "$task_tmp/classes"
cat > "$task_tmp/android/content/Context.java" <<'JAVA'
package android.content; public class Context {}
JAVA
cat > "$task_tmp/android/util/Base64.java" <<'JAVA'
package android.util; public class Base64 { public static final int DEFAULT=0; public static byte[] decode(String value,int flags){return java.util.Base64.getMimeDecoder().decode(value);} }
JAVA
cat > "$task_tmp/dev/podjs/runtime/PodTakeover.java" <<'JAVA'
package dev.podjs.runtime; public class PodTakeover {static java.io.File root; static java.io.File filesRoot(android.content.Context context){return root;} }
JAVA
javac -cp "$json" -d "$task_tmp/classes" "$task_tmp/android/content/Context.java" "$task_tmp/android/util/Base64.java" "$task_tmp/dev/podjs/runtime/PodTakeover.java" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodHttpServices.java tests/java/dev/podjs/runtime/CloudHttpTest.java
java -ea -cp "$json:$task_tmp/classes" dev.podjs.runtime.CloudHttpTest
