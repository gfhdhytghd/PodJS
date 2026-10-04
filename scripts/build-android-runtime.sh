#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/ndk/27.2.12479018}"
revision="$(git -C "$project_root/vendor/pocketjs" rev-parse HEAD)"

cd "$project_root"
IFS=',' read -r -a requested_abis <<< "${PODJS_ANDROID_ABIS:-armeabi-v7a,arm64-v8a}"
ndk_targets=()
rust_targets=()
for abi in "${requested_abis[@]}"; do
  case "$abi" in
    armeabi-v7a) rust_targets+=(armv7-linux-androideabi) ;;
    arm64-v8a) rust_targets+=(aarch64-linux-android) ;;
    x86_64) rust_targets+=(x86_64-linux-android) ;;
    *) printf 'Unsupported Android ABI: %s\n' "$abi" >&2; exit 1 ;;
  esac
  ndk_targets+=(-t "$abi")
done
PODJS_POCKETJS_REVISION="$revision" cargo ndk --platform 30 "${ndk_targets[@]}" build --release -p podjs-runtime
for index in "${!requested_abis[@]}"; do
  destination="platforms/android/runtime/prebuilt/${requested_abis[index]}"
  mkdir -p "$destination"
  cp "target/${rust_targets[index]}/release/libpodjs_runtime.a" "$destination/"
done
printf 'PodJS Android runtime: %s\n' "$revision"
