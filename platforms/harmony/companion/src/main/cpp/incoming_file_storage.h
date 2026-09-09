#pragma once
#include "journal_storage.h"
#include <array>
#include <vector>
#include <dirent.h>
#include <sys/syscall.h>
#ifdef PODJS_FILE_STORAGE_HOST_TEST
#include <openssl/evp.h>
#else
#include <CryptoArchitectureKit/crypto_digest.h>
#endif

namespace pod_incoming {
using pod_store::Fd;
using pod_store::require;
inline std::string hex(const unsigned char* bytes, size_t size) {
  static const char digits[] = "0123456789abcdef";
  std::string result; result.reserve(size * 2);
  for (size_t i = 0; i < size; ++i) { result += digits[bytes[i] >> 4]; result += digits[bytes[i] & 15]; } return result;
}
class Digest {
#ifdef PODJS_FILE_STORAGE_HOST_TEST
  EVP_MD_CTX* context_ = nullptr;
#else
  OH_CryptoDigest* context_ = nullptr;
#endif
 public:
  Digest() {
#ifdef PODJS_FILE_STORAGE_HOST_TEST
    context_ = EVP_MD_CTX_new();
    if (!context_ || EVP_DigestInit_ex(context_, EVP_sha256(), nullptr) != 1) {
      EVP_MD_CTX_free(context_); context_ = nullptr; throw std::runtime_error("Digest unavailable");
    }
#else
    require(OH_CryptoDigest_Create("SHA256", &context_) == CRYPTO_SUCCESS, "Digest unavailable");
#endif
  }
  ~Digest() {
#ifdef PODJS_FILE_STORAGE_HOST_TEST
    EVP_MD_CTX_free(context_);
#else
    OH_DigestCrypto_Destroy(context_);
#endif
  }
  Digest(const Digest&) = delete;
  Digest& operator=(const Digest&) = delete;
  void update(const void* bytes, size_t size) {
    if (!size) return;
#ifdef PODJS_FILE_STORAGE_HOST_TEST
    require(EVP_DigestUpdate(context_, bytes, size) == 1, "Digest update failed");
#else
    Crypto_DataBlob input{reinterpret_cast<uint8_t*>(const_cast<void*>(bytes)), size};
    require(OH_CryptoDigest_Update(context_, &input) == CRYPTO_SUCCESS, "Digest update failed");
#endif
  }
  std::string finish() {
#ifdef PODJS_FILE_STORAGE_HOST_TEST
    unsigned char bytes[EVP_MAX_MD_SIZE]; unsigned length = 0;
    require(EVP_DigestFinal_ex(context_, bytes, &length) == 1 && length == 32, "Digest final failed"); return hex(bytes, length);
#else
    Crypto_DataBlob output{};
    const auto status = OH_CryptoDigest_Final(context_, &output);
    if (status != CRYPTO_SUCCESS || output.len != 32 || !output.data) { OH_Crypto_FreeDataBlob(&output); throw std::runtime_error("Digest final failed"); }
    const std::string result = hex(output.data, output.len); OH_Crypto_FreeDataBlob(&output); return result;
#endif
  }
};
inline bool hashValid(const std::string& hash) {
  if (hash.size() != 64) return false;
  for (char ch : hash) if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false; return true;
}
struct Manifest {
  std::string id, descriptor, sha256;
  size_t size = 0;
  std::vector<std::string> chunks;
  void validate() const {
    require(!id.empty() && id.size() <= 128, "Invalid transfer identity");
    for (char ch : id) require((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_', "Invalid transfer identity");
    require(size <= 16777216 && chunks.size() == (size + 65535) / 65536 && hashValid(sha256), "Invalid file manifest");
    for (const auto& hash : chunks) require(hashValid(hash), "Invalid chunk hash");
    require(!descriptor.empty() && descriptor.size() <= 32768 && pod_store::utf8(descriptor), "Invalid manifest descriptor");
  }
};
/** Owns a cross-process nonblocking flock for its lifetime. Call on worker
 * threads and retain ownership across a complete high-level journal operation. */
class Storage {
  friend class GuestSource;
  Fd root_, lock_, parent_, quotaLock_;
  std::string app_;
  static int directory(int parent, const std::string& name, bool create) {
    if (create && mkdirat(parent, name.c_str(), 0700) != 0) require(errno == EEXIST, "Cannot create incoming directory");
    Fd dir(openat(parent, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)); struct stat st{};
    require(dir.value >= 0 && fstat(dir.value, &st) == 0 && st.st_uid == geteuid() && !(st.st_mode & 0077), "Unsafe incoming directory");
    if (create) require(fsync(parent) == 0, "Cannot commit incoming directory");
    const int value = dir.value; dir.value = -1; return value;
  }
  static int file(int parent, const std::string& name, bool privateMode = true) {
    Fd input(openat(parent, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    if (input.value < 0 && errno == ENOENT) return -1;
    struct stat st{}; require(input.value >= 0 && fstat(input.value, &st) == 0 &&
      (privateMode ? pod_store::privateFile(st) : S_ISREG(st.st_mode) && st.st_uid == geteuid() && st.st_nlink == 1 && !(st.st_mode & 0022)), "Unsafe incoming file");
    const int value = input.value; input.value = -1; return value;
  }
  static void removeFile(int parent, const std::string& name) {
    Fd prior(file(parent, name)); if (prior.value < 0) return;
    require(unlinkat(parent, name.c_str(), 0) == 0, "Cannot remove incoming file");
  }
  static void writeAll(int fd, const void* data, size_t size) {
    const auto* bytes = static_cast<const unsigned char*>(data);
    while (size) { const ssize_t count = write(fd, bytes, size); if (count < 0 && errno == EINTR) continue; require(count > 0, "Incoming write failed"); bytes += count; size -= count; }
  }
  static std::optional<std::string> readText(int parent, const std::string& name, size_t maximum) {
    Fd input(file(parent, name)); if (input.value < 0) return std::nullopt;
    struct stat st{}; require(fstat(input.value, &st) == 0 && st.st_size >= 0 && static_cast<uint64_t>(st.st_size) <= maximum, "Incoming file too large");
    std::string text(static_cast<size_t>(st.st_size), '\0'); size_t offset = 0;
    while (offset < text.size()) { const ssize_t count = read(input.value, text.data() + offset, text.size() - offset); if (count < 0 && errno == EINTR) continue; require(count > 0, "Incoming read failed"); offset += count; } return text;
  }
  static void atomic(int parent, const std::string& name, const void* bytes, size_t size) {
    // Validate destination and stale staging before changing anything.
    Fd prior(file(parent, name)); removeFile(parent, name + ".tmp");
    Fd output(openat(parent, (name + ".tmp").c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    require(output.value >= 0, "Cannot stage incoming file"); writeAll(output.value, bytes, size); require(fsync(output.value) == 0, "Cannot sync incoming file");
    require(renameat(parent, (name + ".tmp").c_str(), parent, name.c_str()) == 0 && fsync(parent) == 0, "Cannot publish incoming file");
  }
  static std::string peerName(const std::string& peer) { pod_store::companionStateNamespace(peer); return "peer-" + peer; }
  int transfer(const std::string& peer, const Manifest& manifest, bool create) {
    manifest.validate(); Fd parent(directory(root_.value, peerName(peer), create)); return directory(parent.value, manifest.id, create);
  }
  static bool verified(int parent, const std::string& name, size_t size, const std::string& hash, bool privateMode = true) {
    Fd input(file(parent, name, privateMode)); if (input.value < 0) return false;
    struct stat st{}; require(fstat(input.value, &st) == 0, "Cannot stat incoming file"); if (st.st_size < 0 || static_cast<uint64_t>(st.st_size) != size) return false;
    Digest digest; std::array<unsigned char, 65536> buffer{}; size_t total = 0;
    while (total < size) { const ssize_t count = read(input.value, buffer.data(), std::min(buffer.size(), size - total)); if (count < 0 && errno == EINTR) continue; require(count > 0, "Incoming read failed"); digest.update(buffer.data(), count); total += count; }
    return digest.finish() == hash;
  }
  static size_t guestBytes(int dir, size_t& entries, size_t depth = 0) {
    require(depth <= 16, "Guest directory nesting limit"); size_t total = 0;
    for (const auto& name : children(dir)) {
      require(++entries <= 4096, "Guest file count limit"); struct stat st{};
      require(fstatat(dir, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) == 0 && st.st_uid == geteuid(), "Unsafe guest entry");
      size_t size;
      if (S_ISDIR(st.st_mode)) {
        Fd child(openat(dir, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        require(child.value >= 0, "Cannot inspect guest directory"); size = guestBytes(child.value, entries, depth + 1);
      } else {
        require(S_ISREG(st.st_mode) && st.st_nlink == 1 && st.st_size >= 0, "Unsafe guest file");
        size = static_cast<size_t>(st.st_size);
      }
      require(size <= 16777216 && total <= 16777216 - size, "Guest file quota exceeded"); total += size;
    }
    return total;
  }
  static std::vector<std::string> guestPath(const std::string& path) {
    require(!path.empty() && path.size() <= 1024 && path.front() != '/' && pod_store::utf8(path), "Invalid guest path");
    for (const auto ch : path) require(static_cast<unsigned char>(ch) >= 32 && ch != 127 && ch != '\\' && ch != ':', "Invalid guest path");
    std::vector<std::string> parts; size_t start = 0;
    while (start <= path.size()) {
      const auto end = path.find('/', start), length = end == std::string::npos ? path.size() - start : end - start;
      const auto part = path.substr(start, length);
      require(!part.empty() && part != "." && part != ".." && part.size() <= 255 && parts.size() < 16, "Invalid guest path");
      parts.push_back(part); if (end == std::string::npos) break; start = end + 1;
    }
    return parts;
  }
  static void checkManifest(int dir, const Manifest& manifest) {
    const auto saved = readText(dir, "manifest", 32768); require(saved && *saved == manifest.descriptor, "Incoming manifest changed");
    const auto reservation = readText(dir, "reservation", 16);
    require(reservation && *reservation == std::to_string(manifest.size * 2), "Missing or changed file reservation");
  }
  static std::vector<std::string> children(int dir) {
    Fd duplicate(openat(dir, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    DIR* listing = fdopendir(duplicate.value); require(listing != nullptr, "Cannot inspect file quota"); duplicate.value = -1;
    std::vector<std::string> names;
    try {
      errno = 0;
      while (const auto* entry = readdir(listing)) {
        const std::string name(entry->d_name); if (name == "." || name == "..") continue;
        require(names.size() < 4096, "File quota directory too large"); names.push_back(name); errno = 0;
      }
      require(errno == 0, "Cannot enumerate file quota");
    } catch (...) { closedir(listing); throw; }
    closedir(listing); return names;
  }
  size_t reservedBytes() {
    size_t total = 0;
    for (const auto* prefix : {"podjs-companion-incoming-", "podjs-companion-outgoing-"}) {
      const std::string name = prefix + app_; struct stat st{};
      if (fstatat(parent_.value, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) { require(errno == ENOENT, "Cannot inspect quota namespace"); continue; }
      Fd scope(directory(parent_.value, name, false));
      for (const auto& peer : children(scope.value)) {
        if (peer == "owner.lock" || peer == "journal.json" || peer == "journal.json.tmp") continue;
        require(peer.rfind("peer-", 0) == 0, "Unknown quota namespace entry");
        pod_store::companionStateNamespace(peer.substr(5)); Fd peerDir(directory(scope.value, peer, false));
        for (const auto& transfer : children(peerDir.value)) {
          Fd dir(directory(peerDir.value, transfer, false));
          const auto reservation = readText(dir.value, "reservation", 16);
          if (!reservation) {
            // Interrupted directory creation is safe; legacy or unexpected
            // content without an accounting record must never count as free.
            for (const auto& child : children(dir.value)) require(child == "reservation.tmp", "Unaccounted companion file contents");
            continue;
          }
          require(!reservation->empty(), "Invalid file reservation"); size_t bytes = 0;
          for (const auto ch : *reservation) {
            require(ch >= '0' && ch <= '9' && bytes <= 33554432 / 10, "Invalid file reservation");
            bytes = bytes * 10 + static_cast<size_t>(ch - '0'); require(bytes <= 33554432, "Invalid file reservation");
          }
          require(bytes % 2 == 0 && total <= 33554432 - bytes, "Companion app file quota exceeded"); total += bytes;
        }
      }
    }
    return total;
  }
 public:
  Storage(const std::string& root, const std::string& app, bool outgoing = false) {
    pod_store::companionStateNamespace(app); require(!root.empty() && root.front() == '/' && root.find('\0') == std::string::npos, "Invalid incoming root");
    parent_.value = open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); require(parent_.value >= 0, "Cannot open incoming root"); app_ = app;
    Fd quota(directory(parent_.value, "podjs-companion-quota-" + app, true));
    quotaLock_.value = openat(quota.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    struct stat quotaStat{}; require(quotaLock_.value >= 0 && fstat(quotaLock_.value, &quotaStat) == 0 && pod_store::privateFile(quotaStat), "Unsafe companion quota lock");
    int quotaResult; do { quotaResult = flock(quotaLock_.value, LOCK_EX | LOCK_NB); } while (quotaResult != 0 && errno == EINTR);
    require(quotaResult == 0, "Companion app file store already owned");
    root_.value = directory(parent_.value, (outgoing ? "podjs-companion-outgoing-" : "podjs-companion-incoming-") + app, true);
    lock_.value = openat(root_.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    struct stat st{}; require(lock_.value >= 0 && fstat(lock_.value, &st) == 0 && pod_store::privateFile(st), "Unsafe incoming lock");
    int result; do { result = flock(lock_.value, LOCK_EX | LOCK_NB); } while (result != 0 && errno == EINTR);
    require(result == 0, "Incoming store already owned");
  }
  std::optional<std::string> readJournal() { return readText(root_.value, "journal.json", 4194304); }
  void writeJournal(const std::string& text) { require(!text.empty() && text.size() <= 4194304 && pod_store::utf8(text), "Invalid incoming journal"); atomic(root_.value, "journal.json", text.data(), text.size()); }
  /** Caller must hold a complete-file journal lease. Fixed host-private staging
   * never accepts a guest directory; publication is atomic and no-clobber.
   * Quota is a snapshot here: the host must serialize guest FS mutations too. */
  void saveComplete(const std::string& peer, const Manifest& manifest, const std::string& path) {
    const auto parts = guestPath(path); manifest.validate();
    Fd sourceDir(transfer(peer, manifest, false)); checkManifest(sourceDir.value, manifest);
    require(verified(sourceDir.value, "complete", manifest.size, manifest.sha256), "Incoming complete file failed verification");
    Fd guest(directory(parent_.value, "podjs-guest", false));
    Fd files(directory(guest.value, "files", false));
    Fd staging(directory(guest.value, "sync-save", true));
    Fd saveLock(openat(staging.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600));
    struct stat lockStatus{};
    require(saveLock.value >= 0 && fstat(saveLock.value, &lockStatus) == 0 && pod_store::privateFile(lockStatus), "Unsafe guest save lock");
    int locked; do { locked = flock(saveLock.value, LOCK_EX | LOCK_NB); } while (locked != 0 && errno == EINTR);
    require(locked == 0, "Guest save already active");
    // Only this precise private staging file is recoverable; never sweep guest files.
    removeFile(staging.value, "pending"); require(fsync(staging.value) == 0, "Cannot sync guest staging");
    Fd destination(dup(files.value)); require(destination.value >= 0, "Cannot open guest destination");
    for (size_t i = 0; i + 1 < parts.size(); ++i) {
      Fd next(openat(destination.value, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
      struct stat st{}; require(next.value >= 0 && fstat(next.value, &st) == 0 && st.st_uid == geteuid() && !(st.st_mode & 0022), "Unsafe guest destination directory");
      close(destination.value); destination.value = next.value; next.value = -1;
    }
    const auto& name = parts.back();
    { Fd existing(file(destination.value, name, false));
      if (existing.value >= 0) { require(verified(destination.value, name, manifest.size, manifest.sha256, false), "Guest destination exists with different content"); return; } }
    size_t entries = 0; const size_t used = guestBytes(files.value, entries);
    require(used <= 16777216 - manifest.size, "Guest file quota exceeded");
    Fd source(file(sourceDir.value, "complete")); require(source.value >= 0, "Incoming complete file missing");
    Fd output(openat(staging.value, "pending", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    require(output.value >= 0, "Cannot stage guest save");
    try {
      Digest digest; std::array<unsigned char, 65536> buffer{}; size_t total = 0;
      while (total < manifest.size) {
        const ssize_t count = read(source.value, buffer.data(), std::min(buffer.size(), manifest.size - total));
        if (count < 0 && errno == EINTR) continue; require(count > 0, "Incoming save read failed");
        digest.update(buffer.data(), count); writeAll(output.value, buffer.data(), count); total += count;
      }
      require(digest.finish() == manifest.sha256 && fsync(output.value) == 0, "Guest save verification failed");
#ifdef __NR_renameat2
      if (syscall(__NR_renameat2, staging.value, "pending", destination.value, name.c_str(), 1 /* RENAME_NOREPLACE */) != 0) {
        require(errno == EEXIST && verified(destination.value, name, manifest.size, manifest.sha256, false), "Cannot publish guest save without overwrite");
        removeFile(staging.value, "pending");
      }
#else
      throw std::runtime_error("Atomic no-clobber save unavailable");
#endif
      require(fsync(destination.value) == 0 && fsync(staging.value) == 0, "Cannot commit guest save");
    } catch (...) { try { removeFile(staging.value, "pending"); } catch (...) {} throw; }
  }
  void reserve(const std::string& peer, const Manifest& manifest) {
    manifest.validate(); const size_t total = reservedBytes();
    Fd dir(transfer(peer, manifest, true));
    const std::string reservation = std::to_string(manifest.size * 2);
    const auto priorReservation = readText(dir.value, "reservation", 16);
    if (priorReservation) require(*priorReservation == reservation, "File reservation changed");
    else {
      require(total <= 33554432 - manifest.size * 2, "Companion app file quota exceeded");
      atomic(dir.value, "reservation", reservation.data(), reservation.size());
    }
    const auto prior = readText(dir.value, "manifest", 32768);
    if (prior) require(*prior == manifest.descriptor, "Incoming manifest changed");
    else atomic(dir.value, "manifest", manifest.descriptor.data(), manifest.descriptor.size());
  }
  void chunk(const std::string& peer, const Manifest& manifest, size_t index, const std::vector<unsigned char>& bytes) {
    manifest.validate(); require(index < manifest.chunks.size() && bytes.size() == std::min<size_t>(65536, manifest.size - index * 65536), "Invalid incoming chunk");
    Digest digest; digest.update(bytes.data(), bytes.size()); require(digest.finish() == manifest.chunks[index], "Incoming chunk hash mismatch");
    Fd dir(transfer(peer, manifest, false)); checkManifest(dir.value, manifest);
    atomic(dir.value, "chunk-" + std::to_string(index), bytes.data(), bytes.size());
  }
  void remove(const std::string& peer, const Manifest& manifest) {
    manifest.validate(); const auto name = peerName(peer); struct stat st{};
    if (fstatat(root_.value, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) { require(errno == ENOENT, "Cannot inspect incoming peer"); return; }
    Fd parent(directory(root_.value, name, false));
    if (fstatat(parent.value, manifest.id.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) { require(errno == ENOENT, "Cannot inspect incoming transfer"); return; }
    Fd dir(directory(parent.value, manifest.id, false)); const auto saved = readText(dir.value, "manifest", 32768);
    if (saved) require(*saved == manifest.descriptor, "Incoming manifest changed");
    DIR* listing = fdopendir(dup(dir.value)); require(listing != nullptr, "Cannot list incoming transfer");
    std::vector<std::string> names;
    try {
      errno = 0;
      while (const auto* entry = readdir(listing)) {
        const std::string child(entry->d_name); if (child == "." || child == "..") continue;
        bool known = child == "manifest" || child == "manifest.tmp" || child == "complete" || child == "complete.tmp" || child == "reservation" || child == "reservation.tmp";
        for (size_t i = 0; !known && i < manifest.chunks.size(); ++i) known = child == "chunk-" + std::to_string(i) || child == "chunk-" + std::to_string(i) + ".tmp";
        require(known, "Unknown incoming transfer contents"); Fd checked(file(dir.value, child)); require(checked.value >= 0, "Missing incoming file"); names.push_back(child); errno = 0;
      }
      require(errno == 0, "Cannot read incoming directory");
    } catch (...) { closedir(listing); throw; }
    closedir(listing);
    // Retain reservation across any interrupted content deletion.
    for (const auto& child : names) if (child != "reservation" && child != "reservation.tmp") removeFile(dir.value, child);
    require(fsync(dir.value) == 0, "Cannot commit content removal");
    removeFile(dir.value, "reservation.tmp"); removeFile(dir.value, "reservation");
    require(fsync(dir.value) == 0 && unlinkat(parent.value, manifest.id.c_str(), AT_REMOVEDIR) == 0 && fsync(parent.value) == 0, "Cannot commit incoming removal");
  }
  std::vector<size_t> missing(const std::string& peer, const Manifest& manifest) {
    Fd dir(transfer(peer, manifest, false)); checkManifest(dir.value, manifest); std::vector<size_t> result;
    if (verified(dir.value, "complete", manifest.size, manifest.sha256)) return result;
    for (size_t i = 0; i < manifest.chunks.size(); ++i) if (!verified(dir.value, "chunk-" + std::to_string(i), std::min<size_t>(65536, manifest.size - i * 65536), manifest.chunks[i])) result.push_back(i);
    return result;
  }
  std::vector<unsigned char> readCompleteChunk(const std::string& peer, const Manifest& manifest, size_t index) {
    manifest.validate(); require(index < manifest.chunks.size(), "Invalid completed chunk index");
    Fd dir(transfer(peer, manifest, false)); checkManifest(dir.value, manifest);
    Fd input(file(dir.value, "complete")); require(input.value >= 0, "Incoming file not complete");
    struct stat st{}; require(fstat(input.value, &st) == 0 && st.st_size >= 0 && static_cast<uint64_t>(st.st_size) == manifest.size, "Invalid completed file size");
    const size_t start = index * 65536, size = std::min<size_t>(65536, manifest.size - start);
    std::vector<unsigned char> bytes(size); size_t offset = 0;
    while (offset < size) {
      const ssize_t count = pread(input.value, bytes.data() + offset, size - offset, start + offset);
      if (count < 0 && errno == EINTR) continue;
      require(count > 0, "Completed file read failed"); offset += count;
    }
    Digest digest; digest.update(bytes.data(), bytes.size());
    require(digest.finish() == manifest.chunks[index], "Completed chunk hash mismatch");
    return bytes;
  }
  void finish(const std::string& peer, const Manifest& manifest) {
    Fd dir(transfer(peer, manifest, false)); checkManifest(dir.value, manifest);
    if (verified(dir.value, "complete", manifest.size, manifest.sha256)) {
      // A crash after publication but before cleanup must not retain duplicate
      // chunk storage forever. Publishing is already durable, cleanup can retry.
      for (size_t i = 0; i < manifest.chunks.size(); ++i) { removeFile(dir.value, "chunk-" + std::to_string(i)); removeFile(dir.value, "chunk-" + std::to_string(i) + ".tmp"); }
      removeFile(dir.value, "complete.tmp"); require(fsync(dir.value) == 0, "Cannot commit chunk cleanup"); return;
    }
    Fd prior(file(dir.value, "complete")); removeFile(dir.value, "complete.tmp");
    Fd output(openat(dir.value, "complete.tmp", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600)); require(output.value >= 0, "Cannot stage complete file");
    Digest whole; std::array<unsigned char, 65536> buffer{};
    for (size_t i = 0; i < manifest.chunks.size(); ++i) {
      Fd input(file(dir.value, "chunk-" + std::to_string(i))); require(input.value >= 0, "Missing incoming chunk");
      const size_t expected = std::min<size_t>(65536, manifest.size - i * 65536); struct stat st{};
      require(fstat(input.value, &st) == 0 && st.st_size >= 0 && static_cast<uint64_t>(st.st_size) == expected, "Invalid chunk size");
      size_t offset = 0; while (offset < expected) { const ssize_t count = read(input.value, buffer.data() + offset, expected - offset); if (count < 0 && errno == EINTR) continue; require(count > 0, "Incoming chunk read failed"); offset += count; }
      Digest part; part.update(buffer.data(), expected); require(part.finish() == manifest.chunks[i], "Incoming chunk hash mismatch");
      whole.update(buffer.data(), expected); writeAll(output.value, buffer.data(), expected);
    }
    require(whole.finish() == manifest.sha256, "Incoming whole file hash mismatch");
    require(fsync(output.value) == 0 && renameat(dir.value, "complete.tmp", dir.value, "complete") == 0 && fsync(dir.value) == 0, "Cannot publish complete file");
    for (size_t i = 0; i < manifest.chunks.size(); ++i) removeFile(dir.value, "chunk-" + std::to_string(i));
    require(fsync(dir.value) == 0, "Cannot commit chunk cleanup");
  }
};
/** Indexed source lease. Holding the shared guest IO lock prevents frames from
 * changing bytes during the importer's two passes; the open fd pins identity. */
class GuestSource {
  Fd input_, lock_;
  size_t size_ = 0;
 public:
  GuestSource(const std::string& root, const std::string& path) {
    const auto parts = Storage::guestPath(path);
    require(!root.empty() && root.front() == '/' && root.size() <= 4096 && root.find('\0') == std::string::npos, "Invalid guest source root");
    Fd parent(open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)); require(parent.value >= 0, "Cannot open source root");
    Fd guest(Storage::directory(parent.value, "podjs-guest", false));
    Fd gate(Storage::directory(guest.value, "sync-save", true));
    lock_.value = openat(gate.value, "owner.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    struct stat status{};
    require(lock_.value >= 0 && fstat(lock_.value, &status) == 0 && pod_store::privateFile(status), "Unsafe guest source lock");
    int locked; do { locked = flock(lock_.value, LOCK_EX | LOCK_NB); } while (locked != 0 && errno == EINTR);
    require(locked == 0, "Guest file IO already active");
    Fd directory(Storage::directory(guest.value, "files", false));
    for (size_t i = 0; i + 1 < parts.size(); ++i) {
      Fd child(openat(directory.value, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
      require(child.value >= 0 && fstat(child.value, &status) == 0 && status.st_uid == geteuid() && !(status.st_mode & 0022), "Unsafe source directory");
      close(directory.value); directory.value = child.value; child.value = -1;
    }
    input_.value = Storage::file(directory.value, parts.back(), false);
    require(input_.value >= 0 && fstat(input_.value, &status) == 0 && status.st_size >= 0 && static_cast<uint64_t>(status.st_size) <= 16777216, "Invalid guest source size");
    size_ = static_cast<size_t>(status.st_size);
  }
  size_t size() const { return size_; }
  std::vector<unsigned char> readChunk(size_t index) {
    require(index < (size_ + 65535) / 65536, "Invalid guest source index");
    struct stat status{}; require(fstat(input_.value, &status) == 0 && status.st_size >= 0 && static_cast<uint64_t>(status.st_size) == size_, "Guest source size changed");
    const size_t start = index * 65536, size = std::min<size_t>(65536, size_ - start);
    std::vector<unsigned char> bytes(size); size_t offset = 0;
    while (offset < size) {
      const ssize_t count = pread(input_.value, bytes.data() + offset, size - offset, start + offset);
      if (count < 0 && errno == EINTR) continue; require(count > 0, "Guest source read failed"); offset += count;
    }
    return bytes;
  }
};
}
