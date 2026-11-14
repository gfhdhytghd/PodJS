#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
developer_dir=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
artifact_dir="$repo_dir/platforms/watchos/Artifacts"
xcframework="$artifact_dir/PodJSRuntime.xcframework"
archive="$artifact_dir/PodJSRuntime-watchOS.xcframework.zip"
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/podjs-watchos.XXXXXX")

cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT HUP INT TERM

export DEVELOPER_DIR="$developer_dir"
export PATH="$HOME/.cargo/bin:$PATH"
export WATCHOS_DEPLOYMENT_TARGET=11.0
export PODJS_POCKETJS_REVISION=$(git -C "$repo_dir/vendor/pocketjs" rev-parse HEAD)

rustup target add aarch64-apple-watchos aarch64-apple-watchos-sim
rustup toolchain install nightly --profile minimal --component rust-src
cargo build --manifest-path "$repo_dir/Cargo.toml" --release \
  --target aarch64-apple-watchos -p podjs-runtime
cargo build --manifest-path "$repo_dir/Cargo.toml" --release \
  --target aarch64-apple-watchos-sim -p podjs-runtime
cargo +nightly build --manifest-path "$repo_dir/Cargo.toml" \
  -Z build-std=std,panic_abort --release \
  --target arm64_32-apple-watchos -p podjs-runtime

for slice in device simulator; do
  mkdir -p "$work_dir/$slice/Headers"
  cp "$repo_dir/crates/podjs-runtime/include/podjs_runtime.h" \
    "$work_dir/$slice/Headers/podjs_runtime.h"
  printf '%s\n' 'module PodJSRuntime {' \
    '  header "podjs_runtime.h"' \
    '  export *' \
    '}' > "$work_dir/$slice/Headers/module.modulemap"
done

cp "$repo_dir/target/aarch64-apple-watchos-sim/release/libpodjs_runtime.a" \
  "$work_dir/simulator/libpodjs_runtime.a"

strip_tool="$developer_dir/Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"
device_arm64="$work_dir/libpodjs_runtime-arm64.a"
device_arm64_32="$work_dir/libpodjs_runtime-arm64_32.a"
cp "$repo_dir/target/aarch64-apple-watchos/release/libpodjs_runtime.a" "$device_arm64"
cp "$repo_dir/target/arm64_32-apple-watchos/release/libpodjs_runtime.a" "$device_arm64_32"
"$strip_tool" -S "$device_arm64"
"$strip_tool" -S "$device_arm64_32"
"$strip_tool" -S "$work_dir/simulator/libpodjs_runtime.a"
"$developer_dir/Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo" -create \
  "$device_arm64" "$device_arm64_32" \
  -output "$work_dir/device/libpodjs_runtime.a"

mkdir -p "$artifact_dir"
rm -rf "$xcframework"
rm -f "$archive"
xcodebuild -create-xcframework \
  -library "$work_dir/device/libpodjs_runtime.a" -headers "$work_dir/device/Headers" \
  -library "$work_dir/simulator/libpodjs_runtime.a" -headers "$work_dir/simulator/Headers" \
  -output "$xcframework"
ditto -c -k --sequesterRsrc --keepParent "$xcframework" "$archive"

printf 'XCFramework: %s\n' "$xcframework"
printf 'Archive: %s\n' "$archive"
printf 'Checksum: '
swift package compute-checksum "$archive"
