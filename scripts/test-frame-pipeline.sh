#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
cpp=platforms/android/runtime/src/main/cpp
cxx=${CXX:-c++}
flags=(-std=c++20 -O2 -pthread ${PODJS_TEST_CXXFLAGS:-} -Itests/cpp -Icrates/podjs-runtime/include -I"$cpp")
"$cxx" "${flags[@]}" tests/cpp/system_font_pixels_test.cpp -o "$build/font-pixels"
"$build/font-pixels"
"$cxx" "${flags[@]}" tests/cpp/glyph_cache_directory_test.cpp -o "$build/font-directory"
"$build/font-directory"
"$cxx" "${flags[@]}" tests/cpp/glyph_cache_read_test.cpp -lz -o "$build/font-read"
"$build/font-read"
"$cxx" "${flags[@]}" "$cpp/gpu_draw_list.cpp" "$cpp/vulkan_gpu.cpp" "$cpp/gpu_draw_list_test.cpp" -o "$build/gpu"
"$build/gpu"
"$cxx" "${flags[@]}" -Itests/cpp "$cpp/render_thread.cpp" "$cpp/vulkan_renderer.cpp" "$cpp/gpu_draw_list.cpp" "$cpp/vulkan_gpu.cpp" tests/cpp/vulkan_frame_pipeline_test.cpp -o "$build/pipeline"
"$build/pipeline"
"$cxx" "${flags[@]}" "$cpp/frame_perf_window_test.cpp" -o "$build/perf"
"$build/perf"
"$cxx" "${flags[@]}" "$cpp/stall_watchdog_test.cpp" -o "$build/watchdog"
"$build/watchdog"
