#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HARMONY_ROOT="$PROJECT_ROOT/platforms/harmony"
TARGET_TRIPLE="aarch64-unknown-linux-ohos"
OHOS_ARCH="arm64-v8a"
CONFIGURATION="${CONFIGURATION:-release}"

if [[ -n "${DEVECO_STUDIO_HOME:-}" ]]; then
  DEVECO_HOME="$DEVECO_STUDIO_HOME"
elif [[ -d "/Applications/DevEco-Studio.app/Contents" ]]; then
  DEVECO_HOME="/Applications/DevEco-Studio.app/Contents"
elif [[ -d "/Applications/DevEco Studio.app/Contents" ]]; then
  DEVECO_HOME="/Applications/DevEco Studio.app/Contents"
else
  echo "DevEco Studio was not found. Set DEVECO_STUDIO_HOME to its Contents directory." >&2
  exit 1
fi

SDK_ROOT="$DEVECO_HOME/sdk"
NATIVE_ROOT="$SDK_ROOT/default/openharmony/native"
LLVM_BIN="$NATIVE_ROOT/llvm/bin"
SYSROOT="$NATIVE_ROOT/sysroot"
CLANG="$LLVM_BIN/clang"
AR="$LLVM_BIN/llvm-ar"
JAVA_HOME="$DEVECO_HOME/jbr/Contents/Home"
NODE="$DEVECO_HOME/tools/node/bin/node"
HVIGOR="$DEVECO_HOME/tools/hvigor/bin/hvigorw.js"
OHPM="$DEVECO_HOME/tools/ohpm/bin/ohpm"
CLANG_RESOURCE_ROOT="$NATIVE_ROOT/llvm/lib/clang"

for required in "$CLANG" "$AR" "$NODE" "$HVIGOR" "$OHPM" "$JAVA_HOME/bin/java"; do
  if [[ ! -e "$required" ]]; then
    echo "Required DevEco tool is missing: $required" >&2
    exit 1
  fi
done

CLANG_RESOURCE_DIR="$(find "$CLANG_RESOURCE_ROOT" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
if [[ -z "$CLANG_RESOURCE_DIR" ]]; then
  echo "Clang resource headers are missing below $CLANG_RESOURCE_ROOT" >&2
  exit 1
fi

export DEVECO_SDK_HOME="$SDK_ROOT"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export HVIGOR_USER_HOME="$PROJECT_ROOT/.pod/hvigor-user-home"
export LIBCLANG_PATH="$NATIVE_ROOT/llvm/lib"
export CC_aarch64_unknown_linux_ohos="$CLANG"
export AR_aarch64_unknown_linux_ohos="$AR"
export CFLAGS_aarch64_unknown_linux_ohos="--target=aarch64-linux-ohos --sysroot=$SYSROOT -D__MUSL__"
export C_INCLUDE_PATH="$CLANG_RESOURCE_DIR/include:$SYSROOT/usr/include:$SYSROOT/usr/include/aarch64-linux-ohos"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_OHOS_LINKER="$CLANG"
export RUSTFLAGS="-C link-arg=--target=aarch64-linux-ohos -C link-arg=--sysroot=$SYSROOT"
export PODJS_POCKETJS_REVISION="$(git -C "$PROJECT_ROOT/vendor/pocketjs" rev-parse HEAD)"
mkdir -p "$HVIGOR_USER_HOME"

rustup target add "$TARGET_TRIPLE"

cargo_args=(build -p podjs-runtime --target "$TARGET_TRIPLE")
if [[ "$CONFIGURATION" == "release" ]]; then
  cargo_args+=(--release)
fi
cargo "${cargo_args[@]}" --manifest-path "$PROJECT_ROOT/Cargo.toml"

if [[ "$CONFIGURATION" == "release" ]]; then
  profile="release"
else
  profile="debug"
fi
runtime_lib="$PROJECT_ROOT/target/$TARGET_TRIPLE/$profile/libpodjs_runtime.a"
prebuilt_dir="$HARMONY_ROOT/entry/src/main/cpp/prebuilt/$OHOS_ARCH"
mkdir -p "$prebuilt_dir"
cp -f "$runtime_lib" "$prebuilt_dir/libpodjs_runtime.a"

bundle_dir="$PROJECT_ROOT/dist/harmonyos-watch"
rawfile_dir="$HARMONY_ROOT/entry/src/main/resources/rawfile"
for asset in main.js main.pak pod.manifest.json; do
  source_file="$bundle_dir/$asset"
  if [[ ! -f "$source_file" ]]; then
    echo "Missing app asset: $source_file. Run pod build first." >&2
    exit 1
  fi
  cp -f "$source_file" "$rawfile_dir/$asset"
done

(
  cd "$HARMONY_ROOT"
  "$OHPM" install
  # The wearable runtime is the entry module. companion_example targets
  # phone/tablet and must not be built with requiredDeviceType=wearable.
  "$NODE" "$HVIGOR" --mode module -p module=entry@default -p product=default -p requiredDeviceType=wearable assembleHap
)

hap="$HARMONY_ROOT/entry/build/default/outputs/default/entry-default-unsigned.hap"
if [[ ! -f "$hap" ]]; then
  echo "Expected HAP was not produced: $hap" >&2
  exit 1
fi
mkdir -p "$bundle_dir"
cp -f "$hap" "$bundle_dir/podjs-harmony-watch-unsigned.hap"
echo "PodJS HarmonyOS package: $bundle_dir/podjs-harmony-watch-unsigned.hap"
