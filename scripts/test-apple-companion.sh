#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"
cargo build --locked -p podjs-runtime
# Static linkage avoids binding the host's newer glibc symbol versions into
# the Swift container. All Swift scratch/cache files disappear with the container.
docker run --rm --user "$(id -u):$(id -g)" \
  -e SWIFTPM_MODULECACHE_OVERRIDE=/tmp/podjs-swift-module-cache \
  -e CLANG_MODULE_CACHE_PATH=/tmp/podjs-clang-cache \
  -v "$project_root:/workspace:ro" \
  -v "$project_root/target/debug/libpodjs_runtime.a:/native/libpodjs_runtime.a:ro" \
  -w /workspace/platforms/apple-companion \
  swift:6.0.3@sha256:0bdd33b44c0493bdf6a674700ce8960cff301977125cff2ced94770d13d7a921 \
  swift test --scratch-path /tmp/podjs-swift-build \
  -Xlinker -L/native -Xlinker -lm -Xlinker -ldl -Xlinker -lpthread
