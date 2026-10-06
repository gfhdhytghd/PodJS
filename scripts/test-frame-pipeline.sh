#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
cpp=platforms/android/runtime/src/main/cpp
cxx=${CXX:-c++}
flags=(-std=c++20 -O2 -Icrates/podjs-runtime/include -I"$cpp")
"$cxx" "${flags[@]}" "$cpp/gpu_draw_list.cpp" "$cpp/vulkan_gpu.cpp" "$cpp/gpu_draw_list_test.cpp" -o "$build/gpu"
"$build/gpu"
"$cxx" "${flags[@]}" -Itests/cpp "$cpp/vulkan_renderer.cpp" "$cpp/gpu_draw_list.cpp" "$cpp/vulkan_gpu.cpp" tests/cpp/vulkan_frame_pipeline_test.cpp -o "$build/pipeline"
"$build/pipeline"
"$cxx" "${flags[@]}" "$cpp/frame_perf_window_test.cpp" -o "$build/perf"
"$build/perf"
