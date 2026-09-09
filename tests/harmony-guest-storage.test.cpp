#include "guest_storage.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <sys/wait.h>
template<class F> void rejects(F work) { bool failed = false; try { work(); } catch (...) { failed = true; } assert(failed); }
int main() {
  char pattern[] = "/tmp/podjs-guest-storage-XXXXXX";
  assert(mkdtemp(pattern)); const std::filesystem::path root(pattern);
  const auto guest = root / "podjs-guest";
  assert(pod_guest::openStorage(root.string()) == guest.string());
  {
    pod_guest::IoGate frame(root.string()), save(root.string());
    { pod_guest::IoGuard lock(&frame); assert(lock.held());
      pod_guest::IoGuard blocked(&save); assert(!blocked.held());
      const pid_t child = fork(); assert(child >= 0);
      if (child == 0) { pod_guest::IoGate other(root.string()); pod_guest::IoGuard attempt(&other); _exit(attempt.held() ? 1 : 0); }
      int result = 0; assert(waitpid(child, &result, 0) == child && WIFEXITED(result) && WEXITSTATUS(result) == 0);
    }
    { pod_guest::IoGuard saveLock(&save); assert(saveLock.held()); assert(!frame.enter()); }
    { pod_guest::IoGuard resumed(&frame); assert(resumed.held()); }
  }
  struct stat status{};
  for (const auto& name : {guest, guest / "files", guest / "tmp"}) {
    assert(lstat(name.c_str(), &status) == 0 && S_ISDIR(status.st_mode) && !(status.st_mode & 0077));
  }
  { std::ofstream file(guest / "files/kept"); file << "persisted"; }
  assert(chmod((guest / "tmp").c_str(), 0755) == 0);
  assert(pod_guest::openStorage(root.string()) == guest.string());
  std::ifstream input(guest / "files/kept"); std::string value; input >> value; assert(value == "persisted");
  rejects([] { pod_guest::openStorage("relative"); });
  rejects([&] { pod_guest::openStorage(root.string() + std::string("\0bad", 4)); });
  assert(chmod((guest / "tmp").c_str(), 0777) == 0);
  rejects([&] { pod_guest::openStorage(root.string()); });
  assert(chmod((guest / "tmp").c_str(), 0700) == 0);
  assert(rmdir((guest / "tmp").c_str()) == 0);
  assert(symlink("files", (guest / "tmp").c_str()) == 0);
  rejects([&] { pod_guest::openStorage(root.string()); });
  assert(unlink((guest / "tmp").c_str()) == 0);
  const auto linked = root / "linked";
  assert(symlink(root.c_str(), linked.c_str()) == 0);
  rejects([&] { pod_guest::openStorage(linked.string()); });
  assert(unlink(linked.c_str()) == 0);
  std::filesystem::remove_all(guest);
  assert(symlink(".", guest.c_str()) == 0);
  rejects([&] { pod_guest::openStorage(root.string()); });
  assert(unlink(guest.c_str()) == 0);
  assert(rmdir(root.c_str()) == 0);
}
