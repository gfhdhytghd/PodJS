#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
provision_tmp=$(mktemp -d)
trap 'rm -rf "$provision_tmp"' EXIT
json=${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -name '*.jar' -print -quit)}
javac -cp "$json" -d "$provision_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/ProvisioningSession.java platforms/android/runtime/src/main/java/dev/podjs/runtime/BleSetupCrypto.java platforms/android/runtime/src/main/java/dev/podjs/runtime/OnlineProvisioningSession.java tests/java/dev/podjs/runtime/ProvisioningTest.java tests/java/dev/podjs/runtime/ProvisioningHttpTest.java
java -ea -cp "$json:$provision_tmp" dev.podjs.runtime.ProvisioningTest
java -ea -cp "$json:$provision_tmp" dev.podjs.runtime.ProvisioningHttpTest

# Exercise both dispatch source sets: release apps may disable legacy BLE while
# retaining online pairing. Transport-only tests cannot catch a missing method here.
mkdir -p "$provision_tmp/stubs/android/content" "$provision_tmp/stubs/android/os" "$provision_tmp/stubs/dev/podjs/runtime"
cat > "$provision_tmp/stubs/android/content/Context.java" <<'JAVA'
package android.content; public class Context {}
JAVA
cat > "$provision_tmp/stubs/android/os/CancellationSignal.java" <<'JAVA'
package android.os; public class CancellationSignal {public void throwIfCanceled(){}}
JAVA
cat > "$provision_tmp/stubs/dev/podjs/runtime/PodBlePermissions.java" <<'JAVA'
package dev.podjs.runtime; final class PodBlePermissions {static void require(android.content.Context c,boolean scan,boolean advertise){throw new AssertionError("Unexpected Bluetooth permission request");}}
JAVA
cat > "$provision_tmp/stubs/dev/podjs/runtime/BleProvisioningSession.java" <<'JAVA'
package dev.podjs.runtime;
abstract class BleProvisioningStub implements ProvisioningSession {}
final class BleProvisioningSession extends BleProvisioningStub {
 BleProvisioningSession(android.content.Context c,String device,String install){throw new AssertionError("Unexpected Bluetooth transport");}
 public void start(){throw new AssertionError();} public String qr(){throw new AssertionError();}
 public String sessionId(){throw new AssertionError();} public org.json.JSONObject receive(){throw new AssertionError();}
 public void checkActive(){throw new AssertionError();} public void respond(int seq,org.json.JSONObject result){throw new AssertionError();} public void close(){}
}
JAVA
queue_status=0
for variant in main noBleProvision; do
  dispatch=platforms/android/runtime/src/main/java/dev/podjs/runtime/PodBleProvisionServices.java
  if [[ "$variant" == noBleProvision ]]; then dispatch=platforms/android/runtime/src/noBleProvision/java/dev/podjs/runtime/PodOnlineProvisionServices.java; fi
  mkdir -p "$provision_tmp/$variant"
  javac -cp "$json:$provision_tmp" -d "$provision_tmp/$variant" "$provision_tmp/stubs/android/content/Context.java" "$provision_tmp/stubs/android/os/CancellationSignal.java" "$provision_tmp/stubs/dev/podjs/runtime/PodBlePermissions.java" "$provision_tmp/stubs/dev/podjs/runtime/BleProvisioningSession.java" "$dispatch" tests/java/dev/podjs/runtime/ProvisioningDispatchTest.java tests/java/dev/podjs/runtime/ProvisioningQueueTest.java
  java -ea -cp "$json:$provision_tmp/$variant:$provision_tmp" dev.podjs.runtime.ProvisioningDispatchTest "$variant"
  java -ea -cp "$json:$provision_tmp/$variant:$provision_tmp" dev.podjs.runtime.ProvisioningQueueTest "$variant" || queue_status=1
done
exit "$queue_status"
