#!/usr/bin/env bash
# Existing standalone native units that are absent from the old mixed chain.
set -euo pipefail
root=${1:-$(cd "$(dirname "$0")/.." && pwd)}
cd "$root"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cpp=platforms/android/runtime/src/main/cpp
for name in renderer_diagnostics touch_snapshot_queue; do
    "${CXX:-c++}" -std=c++20 -O2 -pthread -Itests/cpp -Icrates/podjs-runtime/include -I"$cpp" "$cpp/${name}_test.cpp" -o "$work/$name"
    "$work/$name"
done
