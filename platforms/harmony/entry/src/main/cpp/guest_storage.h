#pragma once
#include "journal_storage.h"

namespace pod_guest {
/** OS-supplied app filesDir only, never a guest path. Keep runtime files/tmp/KV
 * isolated from host identity, pairing, notification and sync journals. */
inline std::string openStorage(const std::string& filesDir) {
  using pod_store::Fd;
  using pod_store::require;
  require(!filesDir.empty() && filesDir.front() == '/' && filesDir.size() <= 4096 &&
          filesDir.find('\0') == std::string::npos, "Invalid guest storage root");
  Fd parent(open(filesDir.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  require(parent.value >= 0, "Cannot open application files directory");
  const auto child = [](int directory, const char* name, mode_t forbidden = 0077) {
    if (mkdirat(directory, name, 0700) != 0) require(errno == EEXIST, "Cannot create guest storage");
    Fd result(openat(directory, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    struct stat status{};
    require(result.value >= 0 && fstat(result.value, &status) == 0 && status.st_uid == geteuid() &&
            !(status.st_mode & forbidden), "Unsafe guest storage directory");
    require(fsync(directory) == 0, "Cannot commit guest storage directory");
    const int value = result.value; result.value = -1; return value;
  };
  Fd root(child(parent.value, "podjs-guest"));
  // pocket-fs owns/recreates tmp using its platform default mode. It remains
  // inside our 0700 root; accept non-writable-to-others modes on reopen.
  Fd files(child(root.value, "files")), temporary(child(root.value, "tmp", 0022));
  return filesDir + "/podjs-guest";
}
/** Separate open-file descriptions intentionally conflict even in one process.
 * The companion save worker uses this exact lock before quota scan/publication. */
class IoGate {
  pod_store::Fd lock_;
 public:
  explicit IoGate(const std::string& filesDir) {
    const auto root = openStorage(filesDir);
    pod_store::Fd parent(open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    pod_store::require(parent.value >= 0, "Cannot open guest IO root");
    if (mkdirat(parent.value, "sync-save", 0700) != 0) pod_store::require(errno == EEXIST, "Cannot create guest IO directory");
    pod_store::Fd directory(openat(parent.value, "sync-save", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    struct stat status{};
    pod_store::require(directory.value >= 0 && fstat(directory.value, &status) == 0 && status.st_uid == geteuid() && !(status.st_mode & 0077), "Unsafe guest IO directory");
    lock_.value = openat(directory.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    pod_store::require(lock_.value >= 0 && fstat(lock_.value, &status) == 0 && pod_store::privateFile(status), "Unsafe guest IO lock");
    pod_store::require(fsync(directory.value) == 0 && fsync(parent.value) == 0, "Cannot commit guest IO lock");
  }
  bool enter() {
    int result; do { result = flock(lock_.value, LOCK_EX | LOCK_NB); } while (result != 0 && errno == EINTR);
    return result == 0;
  }
  void leave() { flock(lock_.value, LOCK_UN); }
};
class IoGuard {
  IoGate* gate_;
  bool held_;
 public:
  explicit IoGuard(IoGate* gate) : gate_(gate), held_(gate && gate->enter()) {}
  ~IoGuard() { release(); }
  IoGuard(const IoGuard&) = delete;
  IoGuard& operator=(const IoGuard&) = delete;
  bool held() const { return held_; }
  void release() { if (held_) { gate_->leave(); held_ = false; } }
};
}
