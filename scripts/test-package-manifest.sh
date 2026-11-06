#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
manifest_tmp=$(mktemp -d)
trap 'rm -rf "$manifest_tmp"' EXIT
PODJS_MANIFEST_CASES="$manifest_tmp/cases" bun test tests/package-manifest.test.ts
manifest_json=$(python3 - <<'PY'
from pathlib import Path
print(next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('**/*.jar')))
PY
)
javac -cp "$manifest_json" -d "$manifest_tmp/classes" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodManifest.java tests/java/dev/podjs/runtime/PackageManifestReadTest.java
manifest_limit=$(bun -e 'import {PACKAGE_MANIFEST_MAX_BYTES} from "./packages/cli/src/package-manifest.ts";console.log(PACKAGE_MANIFEST_MAX_BYTES)')
java -ea -cp "$manifest_json:$manifest_tmp/classes" dev.podjs.runtime.PackageManifestReadTest "$manifest_tmp/cases" "$manifest_limit"
# These generated files are real test inputs, including their absolute location
# (the test JVM reads it from the environment). Track them across Gradle runs.
cat > "$manifest_tmp/manifest-tests.gradle" <<'GRADLE'
allprojects {
    plugins.withId("com.android.library") {
        if (project.name == "runtime") {
            android.sourceSets.test.java.srcDir(rootProject.file("../../tests/android-manifest"))
        }
    }
    tasks.withType(Test).configureEach {
        inputs.dir(System.getenv("PODJS_MANIFEST_CASES")).withPropertyName("packageManifestCases")
    }
}
GRADLE
# Exercise all installed Android consumers with these exact writer outputs.
PODJS_MANIFEST_CASES="$manifest_tmp/cases" ./platforms/android/gradlew -p platforms/android -I "$manifest_tmp/manifest-tests.gradle" :runtime:testReleaseUnitTest --tests dev.podjs.runtime.PodManifestConsumersTest
