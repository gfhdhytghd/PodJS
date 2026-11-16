#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/tmp/podjs-swift-home
export SWIFTPM_MODULECACHE_OVERRIDE=/tmp/podjs-swift-module-cache
export CLANG_MODULE_CACHE_PATH=/tmp/podjs-clang-cache
mkdir -p "$HOME"
printf 'Swift unit UID: '
id -u
cat /proc/self/cgroup
sed -n '/^CapEff:/p' /proc/self/status
cd /workspace/platforms/apple-companion
swift --version | grep -q 'Swift version 6.0.3'
swift test --jobs 2 --scratch-path "/tmp/$1" --xunit-output /tmp/podjs-swift-unit.xml \
    -Xlinker -L/native -Xlinker -lm -Xlinker -ldl -Xlinker -lpthread
