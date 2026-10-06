#pragma once
// Host-only Android surface stand-in for the Vulkan synchronization test.
struct ANativeWindow {
  int references = 1;
};
inline void ANativeWindow_acquire(ANativeWindow *window) {
  ++window->references;
}
inline void ANativeWindow_release(ANativeWindow *window) {
  --window->references;
}
