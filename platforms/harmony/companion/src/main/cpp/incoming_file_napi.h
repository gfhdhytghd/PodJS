#pragma once
#include "incoming_file_storage.h"
#include "state_store_napi.h"
#include <unordered_set>

namespace pod_incoming_napi {
struct Lease {
  std::unique_ptr<pod_incoming::Storage> storage;
  std::unique_ptr<pod_incoming::GuestSource> source;
  std::atomic<bool> closed{false}, busy{false};
};
struct Holder { std::shared_ptr<Lease> lease; };
inline std::mutex holdersMutex;
inline std::unordered_set<Holder*> holders;
inline std::atomic<unsigned> jobs{0};
struct Work {
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  std::shared_ptr<Lease> lease;
  bool opening = false;
  bool outgoing = false;
  bool guestSource = false;
  std::string root, app, method, peer, text, error;
  pod_incoming::Manifest manifest;
  size_t index = 0;
  std::vector<unsigned char> bytes;
  std::optional<std::string> output;
  std::vector<size_t> missing;
  ~Work() { if (!opening && lease) lease->busy = false; --jobs; }
};
inline napi_value property(napi_env env, napi_value object, const char* key) {
  napi_value value{}; pod_store::require(napi_get_named_property(env, object, key, &value) == napi_ok, "Invalid incoming arguments"); return value;
}
inline std::string string(napi_env env, napi_value value, size_t maximum) {
  std::string text; pod_store::require(pod_background_store::string(env, value, maximum, text), "Invalid incoming string"); return text;
}
inline size_t number(napi_env env, napi_value value, size_t maximum) {
  double result = 0; pod_store::require(napi_get_value_double(env, value, &result) == napi_ok && result >= 0 && result <= maximum && result == static_cast<double>(static_cast<size_t>(result)), "Invalid incoming number"); return static_cast<size_t>(result);
}
inline std::shared_ptr<Lease> get(napi_env env, napi_value value) {
  void* pointer = nullptr; pod_store::require(napi_get_value_external(env, value, &pointer) == napi_ok, "Invalid incoming lease");
  auto* holder = static_cast<Holder*>(pointer); std::lock_guard lock(holdersMutex);
  pod_store::require(holders.count(holder) && holder->lease && !holder->lease->closed, "Incoming lease closed"); return holder->lease;
}
inline void finalize(napi_env, void* pointer, void*) {
  auto* holder = static_cast<Holder*>(pointer);
  { std::lock_guard lock(holdersMutex); holders.erase(holder); }
  if (holder->lease) holder->lease->closed = true; delete holder;
}
inline void execute(napi_env, void* pointer) {
  auto& job = *static_cast<Work*>(pointer);
  try {
    if (job.opening) {
      job.lease = std::make_shared<Lease>();
      if (job.guestSource) job.lease->source = std::make_unique<pod_incoming::GuestSource>(job.root, job.text);
      else job.lease->storage = std::make_unique<pod_incoming::Storage>(job.root, job.app, job.outgoing);
      return;
    }
    pod_store::require(!job.lease->closed, "Incoming lease closed");
    if (job.method == "readGuestChunk") {
      pod_store::require(job.lease->source != nullptr, "Not a guest source lease");
      job.bytes = job.lease->source->readChunk(job.index); return;
    }
    pod_store::require(job.lease->storage != nullptr, "Not a file storage lease"); auto& storage = *job.lease->storage;
    if (job.method == "readJournal") job.output = storage.readJournal();
    else if (job.method == "writeJournal") storage.writeJournal(job.text);
    else if (job.method == "reserve") storage.reserve(job.peer, job.manifest);
    else if (job.method == "remove") storage.remove(job.peer, job.manifest);
    else if (job.method == "saveComplete") storage.saveComplete(job.peer, job.manifest, job.text);
    else if (job.method == "chunk") storage.chunk(job.peer, job.manifest, job.index, job.bytes);
    else if (job.method == "missing") job.missing = storage.missing(job.peer, job.manifest);
    else if (job.method == "finish") storage.finish(job.peer, job.manifest);
    else if (job.method == "readCompleteChunk") job.bytes = storage.readCompleteChunk(job.peer, job.manifest, job.index);
    else throw std::runtime_error("Unknown incoming operation");
    pod_store::require(!job.lease->closed, "Incoming lease closed");
  } catch (const std::exception& error) { job.error = error.what(); }
  catch (...) { job.error = "Incoming operation failed"; }
}
inline void complete(napi_env env, napi_status status, void* pointer) {
  std::unique_ptr<Work> job(static_cast<Work*>(pointer)); napi_value value{};
  if (status != napi_ok && job->error.empty()) job->error = "Incoming work cancelled";
  if (!job->opening && job->lease && job->lease->closed && job->error.empty()) job->error = "Incoming lease closed";
  if (!job->error.empty()) {
    napi_value message{}; napi_create_string_utf8(env, job->error.data(), job->error.size(), &message); napi_create_error(env, nullptr, message, &value); napi_reject_deferred(env, job->deferred, value);
  } else {
    if (job->opening) {
      auto holder = std::make_unique<Holder>(); holder->lease = job->lease;
      if (napi_create_external(env, holder.get(), finalize, nullptr, &value) != napi_ok) {
        napi_value message{}, error{}; napi_create_string_utf8(env, "Cannot create incoming lease", NAPI_AUTO_LENGTH, &message); napi_create_error(env, nullptr, message, &error);
        napi_reject_deferred(env, job->deferred, error); napi_delete_async_work(env, job->work); return;
      }
      { std::lock_guard lock(holdersMutex); holders.insert(holder.get()); } holder.release();
    } else if (job->method == "readJournal") {
      if (job->output) napi_create_string_utf8(env, job->output->data(), job->output->size(), &value); else napi_get_null(env, &value);
    } else if (job->method == "readCompleteChunk" || job->method == "readGuestChunk") {
      void* bytes = nullptr; napi_value buffer{};
      if (napi_create_arraybuffer(env, job->bytes.size(), &bytes, &buffer) != napi_ok ||
          napi_create_typedarray(env, napi_uint8_array, job->bytes.size(), buffer, 0, &value) != napi_ok) {
        napi_value message{}, error{}; napi_create_string_utf8(env, "Cannot allocate completed chunk", NAPI_AUTO_LENGTH, &message); napi_create_error(env, nullptr, message, &error);
        napi_reject_deferred(env, job->deferred, error); napi_delete_async_work(env, job->work); return;
      }
      std::copy(job->bytes.begin(), job->bytes.end(), static_cast<unsigned char*>(bytes));
    } else if (job->method == "missing") {
      napi_create_array_with_length(env, job->missing.size(), &value);
      for (size_t i = 0; i < job->missing.size(); ++i) { napi_value index{}; napi_create_uint32(env, job->missing[i], &index); napi_set_element(env, value, i, index); }
    } else napi_get_undefined(env, &value);
    if (!job->opening && job->lease) job->lease->busy = false;
    napi_resolve_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline napi_value queue(napi_env env, std::unique_ptr<Work> job) {
  napi_value promise{}, name{}; pod_store::require(napi_create_promise(env, &job->deferred, &promise) == napi_ok, "Cannot create incoming promise");
  napi_create_string_utf8(env, "PodJS incoming file IO", NAPI_AUTO_LENGTH, &name);
  if (napi_create_async_work(env, nullptr, name, execute, complete, job.get(), &job->work) != napi_ok || napi_queue_async_work(env, job->work) != napi_ok) {
    if (job->work) napi_delete_async_work(env, job->work);
    napi_value message{}, error{}; napi_create_string_utf8(env, "Cannot queue incoming IO", NAPI_AUTO_LENGTH, &message); napi_create_error(env, nullptr, message, &error); napi_reject_deferred(env, job->deferred, error); return promise;
  }
  job.release(); return promise;
}
inline std::unique_ptr<Work> work() {
  const auto count = jobs.fetch_add(1); if (count >= 16) { --jobs; throw std::runtime_error("Incoming IO queue full"); }
  try { return std::make_unique<Work>(); } catch (...) { --jobs; throw; }
}
inline napi_value openStore(napi_env env, napi_callback_info info, bool outgoing) {
  try {
    auto job = work(); job->opening = true; job->outgoing = outgoing; size_t count = 2; napi_value args[2]{}; napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    pod_store::require(count == 2, "Invalid incoming open arguments"); job->root = string(env, args[0], 4096); job->app = string(env, args[1], 128);
    return queue(env, std::move(job));
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline napi_value open(napi_env env, napi_callback_info info) { return openStore(env, info, false); }
inline napi_value openOutgoing(napi_env env, napi_callback_info info) { return openStore(env, info, true); }
inline napi_value openGuest(napi_env env, napi_callback_info info) {
  try {
    auto job = work(); job->opening = true; job->guestSource = true;
    size_t count = 2; napi_value args[2]{}; napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    pod_store::require(count == 2, "Invalid guest source arguments"); job->root = string(env, args[0], 4096); job->text = string(env, args[1], 1024);
    return queue(env, std::move(job));
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline napi_value guestSize(napi_env env, napi_callback_info info) {
  try {
    size_t count = 1; napi_value args[1]{}; napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    pod_store::require(count == 1, "Invalid guest size arguments"); const auto lease = get(env, args[0]);
    pod_store::require(lease->source != nullptr, "Not a guest source lease"); napi_value value{};
    napi_create_uint32(env, lease->source->size(), &value); return value;
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline napi_value close(napi_env env, napi_callback_info info) {
  try {
    size_t count = 1; napi_value args[1]{}; napi_get_cb_info(env, info, &count, args, nullptr, nullptr); pod_store::require(count == 1, "Invalid incoming close arguments");
    void* pointer = nullptr; pod_store::require(napi_get_value_external(env, args[0], &pointer) == napi_ok, "Invalid incoming lease"); auto* holder = static_cast<Holder*>(pointer);
    { std::lock_guard lock(holdersMutex); pod_store::require(holders.count(holder), "Invalid incoming lease"); if (holder->lease) { holder->lease->closed = true; holder->lease.reset(); } }
    napi_value value{}; napi_get_undefined(env, &value); return value;
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline napi_value run(napi_env env, napi_callback_info info) {
  try {
    auto job = work(); size_t count = 2; napi_value args[2]{}; napi_get_cb_info(env, info, &count, args, nullptr, nullptr); pod_store::require(count == 2, "Invalid incoming operation arguments");
    auto lease = get(env, args[0]); bool expected = false; pod_store::require(lease->busy.compare_exchange_strong(expected, true), "Incoming lease already busy"); job->lease = lease;
    job->method = string(env, property(env, args[1], "method"), 32);
    if (job->method == "writeJournal") job->text = string(env, property(env, args[1], "text"), 4194304);
    else if (job->method == "readGuestChunk") job->index = number(env, property(env, args[1], "index"), 255);
    else if (job->method != "readJournal") {
      pod_store::require(job->method == "reserve" || job->method == "remove" || job->method == "chunk" || job->method == "missing" || job->method == "finish" || job->method == "readCompleteChunk" || job->method == "saveComplete", "Unknown incoming operation");
      if (job->method == "saveComplete") job->text = string(env, property(env, args[1], "text"), 1024);
      job->peer = string(env, property(env, args[1], "peer"), 128); pod_store::companionStateNamespace(job->peer);
      const auto manifest = property(env, args[1], "manifest");
      job->manifest.id = string(env, property(env, manifest, "transfer_id"), 128);
      job->manifest.size = number(env, property(env, manifest, "size"), 16777216);
      job->manifest.sha256 = string(env, property(env, manifest, "sha256"), 64);
      // MIME can be empty; serialize it with its explicit byte length.
      const auto mimeValue = property(env, manifest, "mime"); size_t mimeLength = 0;
      pod_store::require(napi_get_value_string_utf8(env, mimeValue, nullptr, 0, &mimeLength) == napi_ok && mimeLength <= 128, "Invalid file MIME");
      std::vector<char> mime(mimeLength + 1); napi_get_value_string_utf8(env, mimeValue, mime.data(), mime.size(), &mimeLength);
      std::string mimeText(mime.data(), mimeLength); pod_store::require(pod_store::utf8(mimeText), "Invalid file MIME");
      for (size_t i = 0; i < mimeText.size(); ++i) { const auto ch = static_cast<unsigned char>(mimeText[i]); pod_store::require(ch >= 32 && ch != 127 && !(ch == 0xc2 && i + 1 < mimeText.size() && static_cast<unsigned char>(mimeText[i + 1]) >= 0x80 && static_cast<unsigned char>(mimeText[i + 1]) <= 0x9f), "Invalid file MIME"); }
      const auto chunks = property(env, manifest, "chunk_hashes"); bool array = false; uint32_t length = 0;
      pod_store::require(napi_is_array(env, chunks, &array) == napi_ok && array && napi_get_array_length(env, chunks, &length) == napi_ok && length <= 256, "Invalid chunk hashes");
      for (uint32_t i = 0; i < length; ++i) { napi_value item{}; napi_get_element(env, chunks, i, &item); job->manifest.chunks.push_back(string(env, item, 64)); }
      job->manifest.descriptor = job->manifest.id + "\n" + std::to_string(job->manifest.size) + "\n" + job->manifest.sha256 + "\n" + std::to_string(mimeLength) + ":" + mimeText + "\n";
      for (const auto& hash : job->manifest.chunks) job->manifest.descriptor += hash + "\n";
      job->manifest.validate();
      if (job->method == "readCompleteChunk") job->index = number(env, property(env, args[1], "index"), 255);
      if (job->method == "chunk") {
        job->index = number(env, property(env, args[1], "index"), 255);
        napi_typedarray_type type{}; size_t length = 0, offset = 0; void* data = nullptr; napi_value buffer{};
        pod_store::require(napi_get_typedarray_info(env, property(env, args[1], "data"), &type, &length, &data, &buffer, &offset) == napi_ok && type == napi_uint8_array && length <= 65536, "Invalid incoming chunk bytes");
        if (length) job->bytes.assign(static_cast<unsigned char*>(data), static_cast<unsigned char*>(data) + length);
      }
    }
    return queue(env, std::move(job));
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline bool install(napi_env env, napi_value exports) {
  napi_property_descriptor properties[] = {
    {"incomingFilesOpen", nullptr, open, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"outgoingFilesOpen", nullptr, openOutgoing, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"guestFileOpen", nullptr, openGuest, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"guestFileSize", nullptr, guestSize, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"incomingFilesClose", nullptr, close, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"incomingFilesRun", nullptr, run, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  return napi_define_properties(env, exports, 6, properties) == napi_ok;
}
}
