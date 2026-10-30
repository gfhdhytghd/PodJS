#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodCloudCrypto.java tests/java/PodCloudCryptoTest.java platforms/android/runtime/src/main/java/dev/podjs/runtime/PodSoftwareEcdh.java tests/java/dev/podjs/runtime/SoftwareEcdhTest.java
java -cp "$task_tmp" PodCloudCryptoTest "$@"

java -cp "$task_tmp" dev.podjs.runtime.SoftwareEcdhTest
javac -cp "$task_tmp" -d "$task_tmp" tests/java/dev/podjs/runtime/PossessionProofTest.java
java -cp "$task_tmp" dev.podjs.runtime.PossessionProofTest tests/fixtures/pairing-possession-v1.tsv
javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/PodSecureLease.java tests/java/dev/podjs/runtime/SecureLeaseTest.java
java -cp "$task_tmp" dev.podjs.runtime.SecureLeaseTest
javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/SecureReadCache.java tests/java/dev/podjs/runtime/SecureReadCacheTest.java
java -cp "$task_tmp" dev.podjs.runtime.SecureReadCacheTest

javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/CloudWriterLease.java tests/java/dev/podjs/runtime/CloudWriterLeaseTest.java
java -cp "$task_tmp" dev.podjs.runtime.CloudWriterLeaseTest

javac -d "$task_tmp" platforms/android/runtime/src/main/java/dev/podjs/runtime/CloudWorkerOutcome.java platforms/android/runtime/src/main/java/dev/podjs/runtime/WindowedWorkQueue.java tests/java/dev/podjs/runtime/WindowedWorkQueueTest.java
java -cp "$task_tmp" dev.podjs.runtime.WindowedWorkQueueTest
