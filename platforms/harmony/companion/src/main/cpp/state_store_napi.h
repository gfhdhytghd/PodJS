#pragma once
#include <napi/native_api.h>
#include <atomic>
#include <memory>
#include <vector>
#include "journal_storage.h"

namespace pod_background_store {
inline std::atomic<unsigned> active{0};
struct Work {
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  bool compare = false, exchanged = false;
  std::string root, desired, error, directory = "podjs-background";
  std::optional<std::string> expected, output;
  ~Work() { --active; }
};
inline void execute(napi_env, void* data) {
  auto& job = *static_cast<Work*>(data);
  try {
    // Lifetime holds flock across read+conditional write, then releases it.
    // Another process may retry a busy operation; no JS callback owns the lock.
    pod_store::JournalStorage store(job.root, job.directory);
    job.output = store.read();
    if (job.compare && job.output == job.expected) {
      store.write(job.desired); job.exchanged = true;
    }
  } catch (const std::exception& error) { job.error = error.what(); }
  catch (...) { job.error = "Background store IO failed"; }
}
inline void complete(napi_env env, napi_status status, void* data) {
  std::unique_ptr<Work> job(static_cast<Work*>(data));
  napi_value value{};
  if (status != napi_ok && job->error.empty()) job->error = "Background store IO cancelled";
  if (!job->error.empty()) {
    napi_value message{}, code{};
    const char* label = job->error == "Journal already owned" ? "busy" : "storage_error";
    napi_create_string_utf8(env, label, NAPI_AUTO_LENGTH, &code);
    napi_create_string_utf8(env, job->error.data(), job->error.size(), &message);
    napi_create_error(env, code, message, &value); napi_reject_deferred(env, job->deferred, value);
  } else {
    if (job->compare) napi_get_boolean(env, job->exchanged, &value);
    else if (job->output) napi_create_string_utf8(env, job->output->data(), job->output->size(), &value);
    else napi_get_null(env, &value);
    napi_resolve_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline bool string(napi_env env, napi_value value, size_t limit, std::string& output) {
  size_t size = 0;
  if (napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok || !size || size > limit) return false;
  std::vector<char> bytes(size + 1);
  if (napi_get_value_string_utf8(env, value, bytes.data(), bytes.size(), &size) != napi_ok) return false;
  output.assign(bytes.data(), size);
  return pod_store::utf8(output);
}
enum class MessageBox { None, Outbox, Inbox, FileRequests, Pairings, OutgoingTransfers };
inline napi_value enqueue(napi_env env, napi_callback_info info, bool compare, bool companion = false, MessageBox box = MessageBox::None) {
  unsigned count = active.load();
  do {
    if (count >= 16) { napi_throw_error(env, "busy", "Background store queue full"); return nullptr; }
  } while (!active.compare_exchange_weak(count, count + 1));
  auto job = std::make_unique<Work>(); job->compare = compare;
  size_t argc = 5; napi_value args[5]{};
  napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  const size_t offset = companion ? 1u : 0u;
  bool valid = argc == (compare ? 3u : 1u) + offset && string(env, args[0], 4096, job->root);
  if (valid && companion) {
    std::string app;
    valid = string(env, args[1], 128, app);
    if (valid) {
      try { job->directory = pod_store::companionStateNamespace(app); }
      catch (...) { valid = false; }
      if (valid && box == MessageBox::Outbox) job->directory = "podjs-companion-outbox-" + app;
      if (valid && box == MessageBox::Inbox) job->directory = "podjs-companion-inbox-" + app;
      if (valid && box == MessageBox::FileRequests) job->directory = "podjs-companion-file-requests-" + app;
      if (valid && box == MessageBox::OutgoingTransfers) job->directory = "podjs-companion-outgoing-transfers-" + app;
      if (valid && box == MessageBox::Pairings) job->directory = "podjs-companion-pairings-" + app;
    }
  }
  if (valid && compare) {
    napi_valuetype type{};
    valid = napi_typeof(env, args[1 + offset], &type) == napi_ok;
    if (valid && type != napi_null) {
      std::string expected;
      valid = string(env, args[1 + offset], pod_store::JournalStorage::maxBytes, expected);
      if (valid) job->expected = std::move(expected);
    }
    valid = valid && string(env, args[2 + offset], pod_store::JournalStorage::maxBytes, job->desired);
  }
  if (!valid) { napi_throw_type_error(env, nullptr, "Invalid background store arguments"); return nullptr; }
  napi_value promise{}, name{};
  if (napi_create_promise(env, &job->deferred, &promise) != napi_ok) return nullptr;
  napi_create_string_utf8(env, "PodJS background store", NAPI_AUTO_LENGTH, &name);
  if (napi_create_async_work(env, nullptr, name, execute, complete, job.get(), &job->work) != napi_ok ||
      napi_queue_async_work(env, job->work) != napi_ok) {
    napi_value message{}, error{};
    napi_create_string_utf8(env, "Cannot queue background store IO", NAPI_AUTO_LENGTH, &message);
    napi_create_error(env, nullptr, message, &error); napi_reject_deferred(env, job->deferred, error);
    if (job->work) napi_delete_async_work(env, job->work);
    return promise;
  }
  job.release(); return promise;
}
inline napi_value read(napi_env env, napi_callback_info info) { return enqueue(env, info, false); }
inline napi_value compareExchange(napi_env env, napi_callback_info info) { return enqueue(env, info, true); }
inline napi_value companionRead(napi_env env, napi_callback_info info) { return enqueue(env, info, false, true); }
inline napi_value companionCompareExchange(napi_env env, napi_callback_info info) { return enqueue(env, info, true, true); }
inline napi_value outboxRead(napi_env env, napi_callback_info info) { return enqueue(env, info, false, true, MessageBox::Outbox); }
inline napi_value outboxCompareExchange(napi_env env, napi_callback_info info) { return enqueue(env, info, true, true, MessageBox::Outbox); }
inline napi_value inboxRead(napi_env env, napi_callback_info info) { return enqueue(env, info, false, true, MessageBox::Inbox); }
inline napi_value inboxCompareExchange(napi_env env, napi_callback_info info) { return enqueue(env, info, true, true, MessageBox::Inbox); }
inline napi_value fileRequestsRead(napi_env env, napi_callback_info info) { return enqueue(env, info, false, true, MessageBox::FileRequests); }
inline napi_value fileRequestsCompareExchange(napi_env env, napi_callback_info info) { return enqueue(env, info, true, true, MessageBox::FileRequests); }
inline bool install(napi_env env, napi_value exports, bool companionOnly = false) {
  napi_property_descriptor properties[] = {
    {"backgroundStoreRead", nullptr, read, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundStoreCompareExchange", nullptr, compareExchange, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionStateRead", nullptr, companionRead, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionStateCompareExchange", nullptr, companionCompareExchange, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionOutboxRead", nullptr, outboxRead, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionOutboxCompareExchange", nullptr, outboxCompareExchange, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionInboxRead", nullptr, inboxRead, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionInboxCompareExchange", nullptr, inboxCompareExchange, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionFileRequestsRead", nullptr, fileRequestsRead, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionOutgoingTransfersRead", nullptr, [](napi_env e, napi_callback_info i) { return enqueue(e, i, false, true, MessageBox::OutgoingTransfers); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionOutgoingTransfersCompareExchange", nullptr, [](napi_env e, napi_callback_info i) { return enqueue(e, i, true, true, MessageBox::OutgoingTransfers); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionFileRequestsCompareExchange", nullptr, fileRequestsCompareExchange, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingsRead", nullptr, [](napi_env e, napi_callback_info i) { return enqueue(e, i, false, true, MessageBox::Pairings); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingsCompareExchange", nullptr, [](napi_env e, napi_callback_info i) { return enqueue(e, i, true, true, MessageBox::Pairings); }, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  constexpr size_t count = sizeof(properties) / sizeof(properties[0]);
  return napi_define_properties(env, exports, count - (companionOnly ? 2 : 0), properties + (companionOnly ? 2 : 0)) == napi_ok;
}
}
