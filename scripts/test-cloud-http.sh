#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
# Use the JVM variant of the same OkHttp release for the host transport regression.
cat > "$task_tmp/http-classpath.gradle" <<'GRADLE'
allprojects { p ->
  if (p.path == ':runtime') {
    def testLibraries = p.configurations.create('hostHttpLibraries')
    p.dependencies.add('hostHttpLibraries', 'com.squareup.okhttp3:okhttp-jvm:5.3.2')
    p.tasks.register('httpTestClasspath') {
    doLast {
      testLibraries.resolvedConfiguration.resolvedArtifacts.each { a ->
        if (a.moduleVersion.id.group in ['com.squareup.okhttp3','com.squareup.okio','org.jetbrains.kotlin','org.jetbrains']) println a.file.absolutePath
      }
    }
  }
  }
}
GRADLE
./platforms/android/gradlew -p platforms/android -q -I "$task_tmp/http-classpath.gradle" :runtime:httpTestClasspath > "$task_tmp/dependencies"
http_cp="$json"
index=0
while IFS= read -r dependency; do
  case "$dependency" in
    *.aar) index=$((index+1)); unzip -p "$dependency" classes.jar > "$task_tmp/http-$index.jar"; http_cp="$http_cp:$task_tmp/http-$index.jar" ;;
    *.jar) http_cp="$http_cp:$dependency" ;;
  esac
done < "$task_tmp/dependencies"
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
javac -cp "$http_cp" -d "$task_tmp/classes" "$task_tmp/android/content/Context.java" "$task_tmp/android/util/Base64.java" "$task_tmp/dev/podjs/runtime/PodTakeover.java" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodHttpTempFiles.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodHttpServices.java tests/java/dev/podjs/runtime/CloudHttpTest.java tests/java/dev/podjs/runtime/ContentHttpTempTest.java
java -ea -cp "$http_cp:$task_tmp/classes" dev.podjs.runtime.CloudHttpTest

java -ea -cp "$http_cp:$task_tmp/classes" dev.podjs.runtime.ContentHttpTempTest
