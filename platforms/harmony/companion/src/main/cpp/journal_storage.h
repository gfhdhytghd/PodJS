#pragma once
// Shared durable storage for companion and watch-host ledgers.
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace pod_store {
class Fd {
 public:
  int value = -1;
  explicit Fd(int fd = -1) : value(fd) {}
  ~Fd() { if (value >= 0) ::close(value); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
};

inline void require(bool ok, const char* error) { if (!ok) throw std::runtime_error(error); }
inline bool privateFile(const struct stat& st) {
  return S_ISREG(st.st_mode) && st.st_uid == geteuid() && st.st_nlink == 1 && !(st.st_mode & 0077);
}
inline bool utf8(const std::string& text) {
  for (size_t i = 0; i < text.size();) {
    const auto first = static_cast<uint8_t>(text[i++]);
    if (first < 0x80) { if (first == 0) return false; continue; }
    uint32_t cp; size_t more; uint32_t minimum;
    if (first >= 0xc2 && first <= 0xdf) { cp = first & 31; more = 1; minimum = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { cp = first & 15; more = 2; minimum = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { cp = first & 7; more = 3; minimum = 0x10000; }
    else return false;
    if (more > text.size() - i) return false;
    while (more--) {
      const auto next = static_cast<uint8_t>(text[i++]);
      if ((next & 0xc0) != 0x80) return false;
      cp = (cp << 6) | (next & 63);
    }
    if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return false;
  }
  return true;
}

inline std::string companionStateNamespace(const std::string& app) {
  require(!app.empty() && app.size() <= 128, "Invalid companion app identity");
  std::string result = "podjs-companion-state-";
  for (const unsigned char ch : app) {
    require((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
        (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || ch == ':' || ch == '-', "Invalid companion app identity");
    // Case-preserving, path-safe component bounded below NAME_MAX.
    result += ch;
  }
  return result;
}

class JournalStorage {
 public:
  static constexpr size_t maxBytes = 16 * 1024 * 1024;
  explicit JournalStorage(const std::string& root, const std::string& directory = "podjs-notifications") {
    require(!root.empty() && root.front() == '/' && root.find('\0') == std::string::npos, "Invalid journal root");
    const std::string companionPrefix = "podjs-companion-state-";
    const bool companion = directory.rfind(companionPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(companionPrefix.size())) == directory;
    const std::string outboxPrefix = "podjs-companion-outbox-";
    const bool outbox = directory.rfind(outboxPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(outboxPrefix.size())) == companionPrefix + directory.substr(outboxPrefix.size());
    const std::string inboxPrefix = "podjs-companion-inbox-";
    const bool inbox = directory.rfind(inboxPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(inboxPrefix.size())) == companionPrefix + directory.substr(inboxPrefix.size());
    const std::string requestsPrefix = "podjs-companion-file-requests-";
    const bool requests = directory.rfind(requestsPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(requestsPrefix.size())) == companionPrefix + directory.substr(requestsPrefix.size());
    const std::string pairingsPrefix = "podjs-companion-pairings-";
    const bool pairings = directory.rfind(pairingsPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(pairingsPrefix.size())) == companionPrefix + directory.substr(pairingsPrefix.size());
    const std::string transfersPrefix = "podjs-companion-outgoing-transfers-";
    const bool transfers = directory.rfind(transfersPrefix, 0) == 0 &&
        companionStateNamespace(directory.substr(transfersPrefix.size())) == companionPrefix + directory.substr(transfersPrefix.size());
    require(directory == "podjs-notifications" || directory == "podjs-background" || directory == "podjs-execution" || directory == "podjs-scheduler" || companion || outbox || inbox || requests || pairings || transfers, "Invalid journal namespace");
    Fd parent(open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    require(parent.value >= 0, "Cannot open private files directory");
    if (mkdirat(parent.value, directory.c_str(), 0700) != 0) require(errno == EEXIST, "Cannot create journal directory");
    dir_.value = openat(parent.value, directory.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st{};
    require(dir_.value >= 0 && fstat(dir_.value, &st) == 0 && st.st_uid == geteuid() && !(st.st_mode & 0077), "Unsafe journal directory");
    require(fsync(parent.value) == 0, "Cannot commit journal directory");
    lock_.value = openat(dir_.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    require(lock_.value >= 0 && fstat(lock_.value, &st) == 0 && privateFile(st), "Unsafe journal lock");
    int locked;
    do { locked = flock(lock_.value, LOCK_EX | LOCK_NB); } while (locked != 0 && errno == EINTR);
    if (locked != 0) {
      require(errno != EWOULDBLOCK && errno != EAGAIN, "Journal already owned");
      throw std::runtime_error("Cannot lock journal");
    }
  }

  std::optional<std::string> read() {
    std::lock_guard lock(mutex_);
    Fd input(openat(dir_.value, "journal.json", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    if (input.value < 0 && errno == ENOENT) return std::nullopt;
    struct stat st{};
    require(input.value >= 0 && fstat(input.value, &st) == 0 && privateFile(st) && st.st_size >= 0 &&
        static_cast<uint64_t>(st.st_size) <= maxBytes, "Unsafe or oversized journal");
    std::string result(static_cast<size_t>(st.st_size), '\0');
    size_t offset = 0;
    while (offset < result.size()) {
      const auto count = ::read(input.value, result.data() + offset, result.size() - offset);
      if (count < 0 && errno == EINTR) continue;
      require(count > 0, "Cannot read complete journal"); offset += static_cast<size_t>(count);
    }
    char extra = 0; ssize_t end;
    do { end = ::read(input.value, &extra, 1); } while (end < 0 && errno == EINTR);
    require(end == 0 && utf8(result), "Invalid journal bytes");
    return result;
  }

  void write(const std::string& text) {
    require(!text.empty() && text.size() <= maxBytes && utf8(text), "Invalid journal bytes");
    std::lock_guard lock(mutex_);
    // A crash may leave our private temporary file. Never truncate an existing
    // inode or follow links: validate the stale name, unlink, then create EXCL.
    struct stat st{};
    if (fstatat(dir_.value, "journal.pending", &st, AT_SYMLINK_NOFOLLOW) == 0) {
      require(privateFile(st), "Unsafe pending journal");
      require(unlinkat(dir_.value, "journal.pending", 0) == 0, "Cannot remove stale pending journal");
    } else require(errno == ENOENT, "Cannot inspect pending journal");
    Fd output(openat(dir_.value, "journal.pending", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    require(output.value >= 0, "Cannot create pending journal");
    size_t offset = 0;
    while (offset < text.size()) {
      const auto count = ::write(output.value, text.data() + offset, text.size() - offset);
      if (count < 0 && errno == EINTR) continue;
      require(count > 0, "Cannot write complete journal"); offset += static_cast<size_t>(count);
    }
    require(fsync(output.value) == 0, "Cannot sync pending journal");
    require(renameat(dir_.value, "journal.pending", dir_.value, "journal.json") == 0, "Cannot replace journal");
    // Failure here is deliberately reported as uncertain; callers must reread.
    require(fsync(dir_.value) == 0, "Cannot commit journal replacement");
  }
 private:
  Fd dir_, lock_;
  std::mutex mutex_;
};
}
