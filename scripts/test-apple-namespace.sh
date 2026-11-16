#!/usr/bin/env bash
set -euo pipefail
repo=$1
rootfs=$2
scratch=$3
tools=$4
mount --make-rprivate /
mkdir -p "$rootfs/test-tools"
mount --bind "$repo" "$rootfs/workspace"
mount -o remount,bind,ro "$rootfs/workspace"
mount --bind "$tools" "$rootfs/test-tools"
mount -o remount,bind,ro "$rootfs/test-tools"
mount --bind "$repo/target/debug/libpodjs_runtime.a" "$rootfs/native/libpodjs_runtime.a"
mount -o remount,bind,ro "$rootfs/native/libpodjs_runtime.a"
mount -t proc proc "$rootfs/proc"
for name in null zero random urandom; do mount --bind "/dev/$name" "$rootfs/dev/$name"; done
# Preserve the original unprivileged UID and drop namespace capabilities.
exec chroot "$rootfs" /usr/bin/setpriv --bounding-set=-all --inh-caps=-all --ambient-caps=-all --no-new-privs \
    /bin/bash /test-tools/test-apple-inside.sh "$scratch"
