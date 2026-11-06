cmake_minimum_required(VERSION 3.22.1)
project(podjs_android LANGUAGES C CXX)

add_library(podjs_runtime STATIC IMPORTED)
set_target_properties(podjs_runtime PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../prebuilt/${ANDROID_ABI}/libpodjs_runtime.a")
add_library(podjs_android SHARED jni_bridge.cpp render_thread.cpp vulkan_renderer.cpp vulkan_gpu.cpp gpu_draw_list.cpp)
# Keep the watch frame/asset paths optimized even in debuggable application builds.
target_compile_features(podjs_android PRIVATE cxx_std_20)
target_compile_options(podjs_android PRIVATE -O2)
target_include_directories(podjs_android PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../crates/podjs-runtime/include")
target_link_libraries(podjs_android PRIVATE podjs_runtime android log vulkan dl)
