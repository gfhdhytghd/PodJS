#!/usr/bin/env bash
# Run the existing Swift units in the caller's capmem cgroup. Docker is used
# only to export an already-cached, stopped SDK filesystem, never to run tests.
set -euo pipefail
repo=${1:-$(cd "$(dirname "$0")/.." && pwd)}
cd "$repo"
image='swift:6.0.3@sha256:0bdd33b44c0493bdf6a674700ce8960cff301977125cff2ced94770d13d7a921'
sdk="$repo/.cache/test-gate/swift-host-sdk"
rootfs="$sdk/rootfs"
report=${PODJS_SWIFT_REPORT:-$repo/.cache/test-gate/swift-unit.xml}
command -v unshare >/dev/null
command -v docker >/dev/null
docker image inspect "$image" >/dev/null
if [[ ! -f "$sdk/ready" ]]; then
    mkdir -p "$rootfs"
    sdk_container=$(docker create "$image")
    trap 'docker rm -v "$sdk_container" >/dev/null' EXIT
    docker export "$sdk_container" | tar --no-same-owner --no-same-permissions \
        --anchored --exclude='dev/*' --exclude='proc/*' --exclude='sys/*' -xf - -C "$rootfs"
    docker rm -v "$sdk_container" >/dev/null
    trap - EXIT
    chmod -R u+rwX "$rootfs"
    printf '%s\n' "$image" > "$sdk/ready"
fi
[[ $(cat "$sdk/ready") == "$image" ]]
# The native library is built before the read-only source bind.
CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-4} cargo build --locked -p podjs-runtime
mkdir -p "$rootfs/workspace" "$rootfs/native" "$rootfs/proc" "$rootfs/dev" "$rootfs/tmp"
touch "$rootfs/native/libpodjs_runtime.a"
for name in null zero random urandom; do touch "$rootfs/dev/$name"; done
rm -f "$rootfs/tmp/podjs-swift-unit.xml"
scratch='podjs-swift-build'
if [[ ${PODJS_TEST_FRESH:-0} == 1 ]]; then scratch="podjs-swift-fresh-$$"; fi
set +e
tools=$(cd "$(dirname "$0")" && pwd)
unshare --user --map-current-user --keep-caps --mount --pid --fork \
    bash "$tools/test-apple-namespace.sh" "$repo" "$rootfs" "$scratch" "$tools"
status=$?
set -e
if [[ -f "$rootfs/tmp/podjs-swift-unit.xml" ]]; then
    mkdir -p "$(dirname "$report")"
    cp "$rootfs/tmp/podjs-swift-unit.xml" "$report"
fi
exit "$status"
