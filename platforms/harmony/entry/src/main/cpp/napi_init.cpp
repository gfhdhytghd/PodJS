#include <ace/xcomponent/native_interface_xcomponent.h>
#include <hilog/log.h>
#include <napi/native_api.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <charconv>
#include <cstdio>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gles_renderer.h"
#include "podjs_runtime.h"
#include "accessibility_provider.h"
#include "host_contract.h"
#include "runtime_events.h"
#include "journal_napi.h"
#include "background_napi.h"
#include "background_store_napi.h"
#include "guest_storage.h"

namespace {
struct EffectSignal {
  napi_env env = nullptr;
  napi_threadsafe_function function = nullptr;
  std::atomic<bool> queued{false};
  std::atomic<bool> active{true};
};
struct Host {
  std::mutex mutex;
  OH_NativeXComponent* component = nullptr;
  PodRuntime* runtime = nullptr;
  std::unique_ptr<pod_guest::IoGate> guestIo;
  pod_host::RuntimeEvents events;
  EffectSignal* effectSignal = nullptr;
  GlesRenderer renderer;
  std::vector<uint8_t> framebuffer;
  std::vector<PodTouch> touches;
  int32_t rotaryPrimaryMillidegrees = 0;
  uint32_t width = 466;
  uint32_t height = 466;
  uint32_t logicalWidth = 233;
  uint32_t logicalHeight = 233;
  double offsetX = 0, offsetY = 0;
  bool accessibilityBound = false;
  bool surfaceAvailable = false, surfaceVisible = true;

  ~Host() {
    if (runtime) pod_runtime_destroy(runtime);
  }

  bool frame() {
    if (!runtime || !surfaceAvailable || !surfaceVisible) return false;
    pod_guest::IoGuard fileIo(guestIo.get());
    if (!fileIo.held()) return false;
    const int32_t rotaryPrimary = rotaryPrimaryMillidegrees;
    rotaryPrimaryMillidegrees = 0;
    PodInputFrame input{sizeof(input), 0, 0, touches.data(),
                        static_cast<uint32_t>(touches.size()), rotaryPrimary, 0};
    if (pod_runtime_frame(runtime, &input) != 0) return false;
    const bool deliveredEvents = events.didFrame();
    if (effectSignal && (deliveredEvents || events.hasEffect(runtime)) && !effectSignal->queued.exchange(true)) {
      if (napi_call_threadsafe_function(effectSignal->function, nullptr, napi_tsfn_nonblocking) != napi_ok)
        effectSignal->queued = false;
    }
    PodDrawList list{};
    const int32_t snapshotResult = pod_runtime_snapshot(runtime, &list);
    if (snapshotResult == 0 && accessibilityBound) pod_a11y::commit(runtime, width, height, offsetX, offsetY);
    if (rotaryPrimary != 0) {
      OH_LOG_Print(LOG_APP, LOG_INFO, 0xD002D00, "PodJS",
                   "axis outcome delta=%{public}d snapshot=%{public}d changed=%{public}d hash=%{public}llu",
                   rotaryPrimary, snapshotResult, list.changed,
                   static_cast<unsigned long long>(list.content_hash));
    }
    if (snapshotResult != 0 || !list.changed) return false;
    if (pod_runtime_render_rgba_incremental(
            runtime, GlesRenderer::kRasterScale, framebuffer.data(), framebuffer.size()) != 0) {
      return false;
    }
    return renderer.submitRgba(framebuffer.data(), framebuffer.size(), list.content_hash);
  }
};

Host g_host;
OH_NativeXComponent_Callback g_callbacks{};

void effectSignalCleanup(void* data) {
  auto* signal = static_cast<EffectSignal*>(data);
  std::lock_guard lock(g_host.mutex);
  signal->active = false;
  if (g_host.effectSignal == signal) g_host.effectSignal = nullptr;
  napi_release_threadsafe_function(signal->function, napi_tsfn_abort);
}

void deliverEffects(napi_env env, napi_value callback, void* context, void*) {
  auto* signal = static_cast<EffectSignal*>(context);
  signal->queued = false;
  if (!env || !callback || !signal->active) return;
  {
    std::lock_guard lock(g_host.mutex);
    if (g_host.effectSignal != signal || !g_host.surfaceAvailable || !g_host.surfaceVisible) return;
  }
  // Never invoke ArkTS while holding the runtime mutex: its consumer polls and
  // posts results through the same mutex. One pending wakeup per listener.
  napi_value receiver{}, result{};
  napi_get_undefined(env, &receiver);
  napi_call_function(env, receiver, callback, 0, nullptr, &result);
}

bool bytes(napi_env env, napi_value value, const uint8_t** data, size_t* length) {
  napi_typedarray_type type{};
  napi_value buffer{};
  size_t offset = 0;
  void* raw = nullptr;
  if (napi_get_typedarray_info(env, value, &type, length, &raw, &buffer, &offset) != napi_ok ||
      type != napi_uint8_array || raw == nullptr) {
    napi_throw_type_error(env, nullptr, "PodJS assets must be Uint8Array values");
    return false;
  }
  *data = static_cast<const uint8_t*>(raw);
  return true;
}

napi_value boolean(napi_env env, bool value) {
  napi_value result{};
  napi_get_boolean(env, value, &result);
  return result;
}

napi_value monotonicMillis(napi_env env, napi_callback_info) {
  const auto time = std::chrono::steady_clock::now().time_since_epoch();
  napi_value value{};
  napi_create_double(env, std::chrono::duration<double, std::milli>(time).count(), &value);
  return value;
}

napi_value setEffectListener(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value args[1]{}; napi_valuetype type = napi_undefined;
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 1 || napi_typeof(env, args[0], &type) != napi_ok ||
      (type != napi_function && type != napi_null)) {
    napi_throw_type_error(env, nullptr, "setEffectListener requires a callback or null"); return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  if (g_host.effectSignal) {
    auto* previous = g_host.effectSignal;
    if (previous->env != env || napi_remove_env_cleanup_hook(env, effectSignalCleanup, previous) != napi_ok) {
      napi_throw_error(env, nullptr, "Effect listener belongs to another environment"); return nullptr;
    }
    previous->active = false; g_host.effectSignal = nullptr;
    napi_release_threadsafe_function(previous->function, napi_tsfn_abort);
  }
  if (type == napi_null) return boolean(env, true);
  auto signal = std::make_unique<EffectSignal>();
  signal->env = env;
  napi_value name{};
  napi_create_string_utf8(env, "PodJS effects", NAPI_AUTO_LENGTH, &name);
  const auto status = napi_create_threadsafe_function(env, args[0], nullptr, name, 1, 1,
      signal.get(), [](napi_env, void* data, void*) { delete static_cast<EffectSignal*>(data); },
      signal.get(), deliverEffects, &signal->function);
  if (status != napi_ok) { napi_throw_error(env, nullptr, "Cannot create effect listener"); return nullptr; }
  // The listener must not keep the ArkTS environment alive on its own.
  napi_unref_threadsafe_function(env, signal->function);
  if (napi_add_env_cleanup_hook(env, effectSignalCleanup, signal.get()) != napi_ok) {
    signal->active = false;
    napi_release_threadsafe_function(signal->function, napi_tsfn_abort);
    signal.release(); // Finalizer owns it after successful TSFN creation.
    napi_throw_error(env, nullptr, "Cannot register effect listener cleanup"); return nullptr;
  }
  g_host.effectSignal = signal.release();
  return boolean(env, true);
}

// UI-thread queries serialize with XComponent frame callbacks. No query draws
// or applies pending guest mutations. Hashes cross ArkTS as exact hex strings.
napi_value pollEffect(napi_env env, napi_callback_info info) {
  std::optional<std::string> effect;
  { std::lock_guard lock(g_host.mutex); effect = g_host.events.poll(g_host.runtime); }
  napi_value value{};
  if (!effect) napi_get_null(env, &value);
  else napi_create_string_utf8(env, effect->data(), effect->size(), &value);
  return value;
}

napi_value postEvent(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value args[1]{};
  const uint8_t* data = nullptr; size_t length = 0;
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 1) {
    napi_throw_type_error(env, nullptr, "postEvent requires UTF-8 JSON bytes"); return nullptr;
  }
  if (!bytes(env, args[0], &data, &length)) return nullptr;
  std::lock_guard lock(g_host.mutex);
  return boolean(env, g_host.events.post(g_host.runtime,
      std::string_view(reinterpret_cast<const char*>(data), length)));
}

napi_value hasCapability(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value args[1]{}; size_t length = 0;
  char name[128]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 1 || napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) != napi_ok ||
      length >= sizeof(name) ||
      napi_get_value_string_utf8(env, args[0], name, sizeof(name), &length) != napi_ok) {
    napi_throw_type_error(env, nullptr, "hasCapability requires a bounded capability name"); return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  // runtime is published only after exact package capability validation and
  // successful guest boot; use the same host-owned list used for its config.
  return boolean(env, pod_host::hasCapability(std::string_view(name, length), g_host.runtime != nullptr));
}

napi_value accessibilityEnabled(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value args[1]{}; bool enabled = false;
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 1 || napi_get_value_bool(env, args[0], &enabled) != napi_ok) {
    napi_throw_type_error(env, nullptr, "accessibilityEnabled requires a boolean"); return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  return boolean(env, g_host.runtime && pod_runtime_set_accessibility_enabled(g_host.runtime, enabled ? 1 : 0) == 0);
}

napi_value accessibilityStateLabels(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value args[1]{}; bool array = false; uint32_t length = 0;
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 1 || napi_is_array(env, args[0], &array) != napi_ok || !array ||
      napi_get_array_length(env, args[0], &length) != napi_ok || length != 6) {
    napi_throw_type_error(env, nullptr, "accessibilityStateLabels requires six strings"); return nullptr;
  }
  std::array<std::string, 6> labels;
  for (uint32_t i = 0; i < 6; ++i) {
    napi_value value{}; size_t bytes = 0;
    if (napi_get_element(env, args[0], i, &value) != napi_ok ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &bytes) != napi_ok || bytes == 0 || bytes > 256) {
      napi_throw_type_error(env, nullptr, "State labels must be 1..256 UTF-8 bytes"); return nullptr;
    }
    std::vector<char> buffer(bytes + 1);
    if (napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &bytes) != napi_ok) return nullptr;
    labels[i].assign(buffer.data(), bytes);
    if (labels[i].find('\0') != std::string::npos) { napi_throw_type_error(env, nullptr, "State labels cannot contain NUL"); return nullptr; }
  }
  pod_a11y::setStateLabels(labels); pod_a11y::flush(); return boolean(env, true);
}

napi_value accessibilitySnapshot(napi_env env, napi_callback_info) {
  std::lock_guard lock(g_host.mutex);
  PodAccessibilitySnapshot snapshot{};
  if (!g_host.runtime || pod_runtime_accessibility_snapshot(g_host.runtime, &snapshot) != 0 || !snapshot.changed) {
    napi_value result{}; napi_get_null(env, &result); return result;
  }
  napi_value result{}, value{}; napi_create_object(env, &result);
  napi_create_string_utf8(env, reinterpret_cast<const char*>(snapshot.json), snapshot.byte_length, &value);
  napi_set_named_property(env, result, "json", value);
  char hash[17]{}; std::snprintf(hash, sizeof(hash), "%016llx", static_cast<unsigned long long>(snapshot.content_hash));
  napi_create_string_utf8(env, hash, 16, &value); napi_set_named_property(env, result, "hash", value);
  napi_create_uint32(env, g_host.logicalWidth, &value); napi_set_named_property(env, result, "logicalWidth", value);
  napi_create_uint32(env, g_host.logicalHeight, &value); napi_set_named_property(env, result, "logicalHeight", value);
  return result;
}

napi_value accessibilityAction(napi_env env, napi_callback_info info) {
  size_t count = 3; napi_value args[3]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  double rawId = 0, rawAction = 0; char hashText[17]{}; size_t length = 0;
  if (count != 3 || napi_get_value_double(env, args[0], &rawId) != napi_ok ||
      napi_get_value_string_utf8(env, args[1], nullptr, 0, &length) != napi_ok || length != 16 ||
      napi_get_value_double(env, args[2], &rawAction) != napi_ok ||
      !(rawId >= 1 && rawId <= INT32_MAX) ||
      (rawAction != 1 && rawAction != 2 && rawAction != 4)) {
    napi_throw_type_error(env, nullptr, "accessibilityAction requires node id, 16-digit hash and action 1/2/4"); return nullptr;
  }
  const auto id = static_cast<int32_t>(rawId);
  if (rawId != id) { napi_throw_type_error(env, nullptr, "node id must be integral"); return nullptr; }
  if (napi_get_value_string_utf8(env, args[1], hashText, sizeof(hashText), &length) != napi_ok) return nullptr;
  uint64_t hash = 0; const auto parsed = std::from_chars(hashText, hashText + 16, hash, 16);
  if (parsed.ec != std::errc{} || parsed.ptr != hashText + 16) {
    napi_throw_type_error(env, nullptr, "Invalid semantic hash"); return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  return boolean(env, g_host.runtime && pod_runtime_accessibility_action(g_host.runtime, id, hash, static_cast<int32_t>(rawAction)) == 0);
}

napi_value rotary(napi_env env, napi_callback_info info) {
  size_t count = 1;
  napi_value args[1]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  int32_t delta = 0;
  if (count != 1 || napi_get_value_int32(env, args[0], &delta) != napi_ok) {
    napi_throw_type_error(env, nullptr, "rotary requires a millidegree integer");
    return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  const int64_t accumulated = static_cast<int64_t>(g_host.rotaryPrimaryMillidegrees) + delta;
  g_host.rotaryPrimaryMillidegrees = static_cast<int32_t>(std::clamp(
      accumulated, static_cast<int64_t>(std::numeric_limits<int32_t>::min()),
      static_cast<int64_t>(std::numeric_limits<int32_t>::max())));
  return boolean(env, true);
}

void throwRuntime(napi_env env, const char* fallback) {
  const char* detail = pod_runtime_last_error();
  napi_throw_error(env, nullptr, detail && detail[0] ? detail : fallback);
}

void onSurfaceCreated(OH_NativeXComponent* component, void* window) {
  std::unique_lock lock(g_host.mutex);
  uint64_t width = 0;
  uint64_t height = 0;
  if (OH_NativeXComponent_GetXComponentSize(component, window, &width, &height) !=
      OH_NATIVEXCOMPONENT_RESULT_SUCCESS) return;
  g_host.width = static_cast<uint32_t>(width);
  g_host.height = static_cast<uint32_t>(height);
  g_host.surfaceAvailable = true;
  OH_NativeXComponent_GetXComponentOffset(component, window, &g_host.offsetX, &g_host.offsetY);
  if (!g_host.runtime) {
    g_host.logicalWidth = (g_host.width + GlesRenderer::kRasterScale - 1) /
                          GlesRenderer::kRasterScale;
    g_host.logicalHeight = (g_host.height + GlesRenderer::kRasterScale - 1) /
                           GlesRenderer::kRasterScale;
  }
  const uint32_t rasterWidth = g_host.logicalWidth * GlesRenderer::kRasterScale;
  const uint32_t rasterHeight = g_host.logicalHeight * GlesRenderer::kRasterScale;
  if (g_host.renderer.attach(window, g_host.width, g_host.height, rasterWidth, rasterHeight)) g_host.frame();
  lock.unlock(); pod_a11y::flush();
}

void onSurfaceChanged(OH_NativeXComponent* component, void* window) {
  onSurfaceCreated(component, window);
}

void onSurfaceDestroyed(OH_NativeXComponent*, void*) {
  { std::lock_guard lock(g_host.mutex);
    g_host.surfaceAvailable = false;
    g_host.renderer.detach(); g_host.touches.clear(); pod_a11y::hide(); }
  pod_a11y::flush();
}

void onTouch(OH_NativeXComponent* component, void* window) {
  OH_NativeXComponent_TouchEvent event{};
  if (OH_NativeXComponent_GetTouchEvent(component, window, &event) !=
      OH_NATIVEXCOMPONENT_RESULT_SUCCESS) return;
  std::lock_guard lock(g_host.mutex);
  g_host.touches.clear();
  const uint32_t count = std::min(event.numPoints, static_cast<uint32_t>(PODJS_MAX_TOUCHES));
  for (uint32_t index = 0; index < count; ++index) {
    const auto& point = event.touchPoints[index];
    if (!point.isPressed) continue;
    g_host.touches.push_back({static_cast<uint32_t>(point.id),
                              point.x * g_host.logicalWidth / std::max(1u, g_host.width),
                              point.y * g_host.logicalHeight / std::max(1u, g_host.height)});
  }
  // Some HarmonyOS wearable builds report isPressed=false for every entry in
  // touchPoints during MOVE even though the primary contact is still down.
  // Treating that snapshot as empty synthesizes an early UP, so a swipe turns
  // into a tap at its starting row. The event-level point is authoritative for
  // the active DOWN/MOVE contact and keeps its id stable until the real UP.
  if (g_host.touches.empty() &&
      (event.type == OH_NATIVEXCOMPONENT_DOWN || event.type == OH_NATIVEXCOMPONENT_MOVE)) {
    g_host.touches.push_back({static_cast<uint32_t>(event.id),
                              event.x * g_host.logicalWidth / std::max(1u, g_host.width),
                              event.y * g_host.logicalHeight / std::max(1u, g_host.height)});
  }
}

void onFrame(OH_NativeXComponent*, uint64_t, uint64_t) {
  { std::lock_guard lock(g_host.mutex); g_host.frame(); }
  pod_a11y::flush();
}

void onSurfaceShow(OH_NativeXComponent*, void*) {
  std::lock_guard lock(g_host.mutex);
  g_host.surfaceVisible = true;
  if (g_host.runtime) pod_runtime_set_lifecycle(g_host.runtime, POD_LIFECYCLE_ACTIVE);
}

void onSurfaceHide(OH_NativeXComponent*, void*) {
  std::unique_lock lock(g_host.mutex);
  g_host.surfaceVisible = false;
  g_host.touches.clear();
  g_host.rotaryPrimaryMillidegrees = 0;
  if (g_host.runtime) pod_runtime_set_lifecycle(g_host.runtime, POD_LIFECYCLE_BACKGROUND);
  pod_a11y::hide(); lock.unlock(); pod_a11y::flush();
}

napi_value preflight(napi_env env, napi_callback_info info) {
  size_t count = 2;
  napi_value args[2]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  char target[64]{};
  size_t length = 0;
  double abi = 0;
  if (count != 2 ||
      napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) != napi_ok ||
      length >= sizeof(target) ||
      napi_get_value_string_utf8(env, args[0], target, sizeof(target), &length) != napi_ok ||
      napi_get_value_double(env, args[1], &abi) != napi_ok) {
    napi_throw_type_error(env, nullptr, "preflight requires a target string and numeric ABI");
    return nullptr;
  }
  const bool ok = pod_host::accepts(std::string_view(target, length), abi, pod_runtime_abi_version());
  napi_value result{};
  napi_value value{};
  napi_create_object(env, &result);
  napi_get_boolean(env, ok, &value);
  napi_set_named_property(env, result, "ok", value);
  if (!ok) {
    napi_create_string_utf8(env, "target/ABI mismatch", NAPI_AUTO_LENGTH, &value);
    napi_set_named_property(env, result, "error", value);
  }
  return result;
}

napi_value boot(napi_env env, napi_callback_info info) {
  size_t count = 4;
  napi_value args[4]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 4) {
    napi_throw_type_error(env, nullptr, "boot requires JS, pak, manifest assets and host filesDir");
    return nullptr;
  }
  const uint8_t* js = nullptr;
  const uint8_t* pak = nullptr;
  const uint8_t* manifest = nullptr;
  size_t jsLength = 0;
  size_t pakLength = 0;
  size_t manifestLength = 0;
  if (!bytes(env, args[0], &js, &jsLength) || !bytes(env, args[1], &pak, &pakLength) ||
      !bytes(env, args[2], &manifest, &manifestLength)) return nullptr;

  std::vector<char> manifestText(manifestLength + 1, 0);
  std::memcpy(manifestText.data(), manifest, manifestLength);
  std::unique_lock lock(g_host.mutex);
  if (g_host.runtime) {
    napi_throw_error(env, nullptr, "PodJS runtime is already booted");
    return nullptr;
  }
  size_t directoryLength = 0;
  if (napi_get_value_string_utf8(env, args[3], nullptr, 0, &directoryLength) != napi_ok ||
      directoryLength == 0 || directoryLength > 4096) {
    napi_throw_type_error(env, nullptr, "Invalid host filesDir"); return nullptr;
  }
  std::vector<char> directory(directoryLength + 1);
  if (napi_get_value_string_utf8(env, args[3], directory.data(), directory.size(), &directoryLength) != napi_ok) return nullptr;
  std::string dataDirectory;
  std::unique_ptr<pod_guest::IoGate> guestIo;
  try {
    const std::string filesDir(directory.data(), directoryLength);
    dataDirectory = pod_guest::openStorage(filesDir);
    guestIo = std::make_unique<pod_guest::IoGate>(filesDir);
  }
  catch (const std::exception&) { napi_throw_error(env, nullptr, "Cannot initialize private guest storage"); return nullptr; }
  pod_guest::IoGuard fileIo(guestIo.get());
  if (!fileIo.held()) { napi_throw_error(env, nullptr, "Guest file IO is busy"); return nullptr; }
  const std::string capabilities = pod_host::capabilitiesJson();
  PodRuntimeConfig config{sizeof(config), "harmonyos-watch", PODJS_RUNTIME_ABI_VERSION, 2,
                          g_host.width, g_host.height, 2.0f, POD_DISPLAY_ROUND,
                          0, 0, 0, 0, dataDirectory.c_str(), capabilities.c_str()};
  std::unique_ptr<PodRuntime, decltype(&pod_runtime_destroy)> runtime(
      pod_runtime_create(&config), pod_runtime_destroy);
  if (!runtime || pod_runtime_load_pak(runtime.get(), pak, pakLength) != 0 ||
      pod_runtime_validate_package(runtime.get(), manifestText.data()) != 0 ||
      pod_runtime_eval_bundle(runtime.get(), js, jsLength, "app:///main.js") != 0) {
    throwRuntime(env, "PodJS boot failed");
    return nullptr;
  }
  g_host.runtime = runtime.release();
  g_host.guestIo = std::move(guestIo);
  if (g_host.accessibilityBound) pod_runtime_set_accessibility_enabled(g_host.runtime, 1);
  g_host.logicalWidth = pod_runtime_logical_width(g_host.runtime);
  g_host.logicalHeight = pod_runtime_logical_height(g_host.runtime);
  g_host.framebuffer.resize(
      static_cast<size_t>(g_host.logicalWidth) * GlesRenderer::kRasterScale *
      g_host.logicalHeight * GlesRenderer::kRasterScale * 4);
  fileIo.release(); g_host.frame();
  lock.unlock(); pod_a11y::flush();
  return boolean(env, true);
}

napi_value init(napi_env env, napi_value exports) {
  if (!pod_background::install(env, exports)) return nullptr;
  if (!pod_background_store::install(env, exports)) return nullptr;
  napi_value xcomponentValue{};
  if (napi_get_named_property(env, exports, OH_NATIVE_XCOMPONENT_OBJ, &xcomponentValue) == napi_ok) {
    OH_NativeXComponent* component = nullptr;
    if (napi_unwrap(env, xcomponentValue, reinterpret_cast<void**>(&component)) == napi_ok && component) {
      g_host.component = component;
      g_callbacks.OnSurfaceCreated = onSurfaceCreated;
      g_callbacks.OnSurfaceChanged = onSurfaceChanged;
      g_callbacks.OnSurfaceDestroyed = onSurfaceDestroyed;
      g_callbacks.DispatchTouchEvent = onTouch;
      OH_NativeXComponent_RegisterCallback(component, &g_callbacks);
      OH_NativeXComponent_ExpectedRateRange rate{30, 60, 60};
      OH_NativeXComponent_SetExpectedFrameRateRange(component, &rate);
      OH_NativeXComponent_RegisterOnFrameCallback(component, onFrame);
      OH_NativeXComponent_RegisterSurfaceShowCallback(component, onSurfaceShow);
      OH_NativeXComponent_RegisterSurfaceHideCallback(component, onSurfaceHide);
      ArkUI_AccessibilityProvider* accessibilityProvider = nullptr;
      OH_NativeXComponent_GetNativeAccessibilityProvider(component, &accessibilityProvider);
      const bool bound = pod_a11y::bind(accessibilityProvider, [](int32_t id, uint64_t hash, uint8_t action) {
        std::lock_guard lock(g_host.mutex);
        return g_host.runtime && g_host.surfaceAvailable && g_host.surfaceVisible &&
            pod_runtime_accessibility_action(g_host.runtime, id, hash, action) == 0;
      });
      { std::lock_guard lock(g_host.mutex);
        g_host.accessibilityBound = bound;
        if (bound && g_host.runtime) pod_runtime_set_accessibility_enabled(g_host.runtime, 1);
      }
      if (!bound) OH_LOG_Print(LOG_APP, LOG_WARN, 0xD002D00, "PodJSA11y", "Native accessibility provider unavailable");
    }
  }
  napi_property_descriptor properties[] = {
      {"preflight", nullptr, preflight, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"boot", nullptr, boot, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"rotary", nullptr, rotary, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"pollEffect", nullptr, pollEffect, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"postEvent", nullptr, postEvent, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"setEffectListener", nullptr, setEffectListener, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"hasCapability", nullptr, hasCapability, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"journalOpen", nullptr, pod_store::open, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"monotonicMillis", nullptr, monotonicMillis, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"journalRead", nullptr, pod_store::read, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"journalWrite", nullptr, pod_store::write, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"accessibilityEnabled", nullptr, accessibilityEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"accessibilitySnapshot", nullptr, accessibilitySnapshot, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"accessibilityAction", nullptr, accessibilityAction, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"accessibilityStateLabels", nullptr, accessibilityStateLabels, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties);
  return exports;
}
}

static napi_module module = {1, 0, nullptr, init, "podjs_harmony", nullptr, {0}};

extern "C" __attribute__((constructor)) void register_module() {
  napi_module_register(&module);
}
