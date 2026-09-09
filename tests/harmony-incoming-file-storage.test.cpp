#define PODJS_FILE_STORAGE_HOST_TEST
#include "../platforms/harmony/companion/src/main/cpp/incoming_file_storage.h"
#include "../platforms/harmony/entry/src/main/cpp/guest_storage.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <sys/wait.h>
using namespace pod_incoming;
template<class Work> void rejects(Work work) { bool failed = false; try { work(); } catch (const std::exception&) { failed = true; } assert(failed); }
std::string hash(const std::vector<unsigned char>& bytes) { Digest digest; digest.update(bytes.data(), bytes.size()); return digest.finish(); }
int main() {
  char pattern[] = "/tmp/podjs-incoming-native-XXXXXX"; char* path = mkdtemp(pattern); assert(path); const std::string root(path);
  try {
    std::vector<unsigned char> data(65539); for (size_t i = 0; i < data.size(); ++i) data[i] = i % 251;
    std::vector<unsigned char> a(data.begin(), data.begin() + 65536), b(data.begin() + 65536, data.end());
    Manifest manifest; manifest.id = "file"; manifest.descriptor = "immutable manifest"; manifest.size = data.size(); manifest.sha256 = hash(data); manifest.chunks = {hash(a), hash(b)};
    {
      Storage store(root, "app"); store.writeJournal("{\"phase\":\"accepted\"}");
      rejects([&] { Storage other(root, "app"); });
      pid_t child = fork(); assert(child >= 0);
      if (child == 0) { bool refused = false; try { Storage other(root, "app"); } catch (...) { refused = true; } _exit(refused ? 0 : 1); }
      int status = 0; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
      store.reserve("phone", manifest); store.chunk("phone", manifest, 0, a);
      assert(store.missing("phone", manifest) == std::vector<size_t>{1});
      rejects([&] { store.finish("phone", manifest); });
      auto changed = b; changed[0] ^= 1; rejects([&] { store.chunk("phone", manifest, 1, changed); });
    }
    {
      Storage reopened(root, "app"); assert(reopened.readJournal() == "{\"phase\":\"accepted\"}");
      assert(reopened.missing("phone", manifest) == std::vector<size_t>{1}); reopened.chunk("phone", manifest, 1, b); reopened.finish("phone", manifest);
      assert(reopened.missing("phone", manifest).empty()); reopened.finish("phone", manifest);
      std::ifstream input(root + "/podjs-companion-incoming-app/peer-phone/file/complete", std::ios::binary);
      std::vector<unsigned char> actual((std::istreambuf_iterator<char>(input)), {}); assert(actual == data);
      const auto guest = root + "/podjs-guest";
      assert(mkdir(guest.c_str(), 0700) == 0); assert(mkdir((guest + "/files").c_str(), 0700) == 0);
      assert(mkdir((guest + "/files/received").c_str(), 0700) == 0);
      reopened.saveComplete("phone", manifest, "received/file.bin");
      { pod_guest::IoGate frame(root); pod_guest::IoGuard executing(&frame); assert(executing.held());
        rejects([&] { reopened.saveComplete("phone", manifest, "received/blocked.bin"); });
        assert(!std::filesystem::exists(guest + "/files/received/blocked.bin")); }
      assert(chmod((guest + "/files/received/file.bin").c_str(), 0644) == 0);
      reopened.saveComplete("phone", manifest, "received/file.bin");
      std::ifstream saved(guest + "/files/received/file.bin", std::ios::binary);
      std::vector<unsigned char> savedBytes((std::istreambuf_iterator<char>(saved)), {}); assert(savedBytes == data);
      for (const auto& unsafe : {"../escape", "/absolute", "received/../escape", "received//x", "received/", "x\\y"})
        rejects([&] { reopened.saveComplete("phone", manifest, unsafe); });
      { std::ofstream conflict(guest + "/files/received/conflict"); conflict << "existing"; }
      assert(chmod((guest + "/files/received/conflict").c_str(), 0600) == 0);
      rejects([&] { reopened.saveComplete("phone", manifest, "received/conflict"); });
      assert(std::filesystem::file_size(guest + "/files/received/conflict") == 8);
      assert(symlink("file.bin", (guest + "/files/received/link").c_str()) == 0);
      rejects([&] { reopened.saveComplete("phone", manifest, "received/link"); });
      assert(unlink((guest + "/files/received/link").c_str()) == 0);
      assert(symlink("received", (guest + "/files/linked-dir").c_str()) == 0);
      rejects([&] { reopened.saveComplete("phone", manifest, "linked-dir/copy"); });
      assert(unlink((guest + "/files/linked-dir").c_str()) == 0);
      { std::ofstream stale(guest + "/sync-save/pending"); stale << "interrupted"; }
      assert(chmod((guest + "/sync-save/pending").c_str(), 0600) == 0);
      reopened.saveComplete("phone", manifest, "received/file.bin");
      assert(!std::filesystem::exists(guest + "/sync-save/pending"));
      auto different = manifest; different.descriptor = "changed"; rejects([&] { reopened.reserve("phone", different); });
      rejects([&] { reopened.reserve("../escape", manifest); });
      auto wrongWhole = manifest; wrongWhole.id = "wrong"; wrongWhole.sha256 = std::string(64, '0');
      reopened.reserve("phone", wrongWhole); reopened.chunk("phone", wrongWhole, 0, a); reopened.chunk("phone", wrongWhole, 1, b);
      rejects([&] { reopened.finish("phone", wrongWhole); }); assert(!std::filesystem::exists(root + "/podjs-companion-incoming-app/peer-phone/wrong/complete"));
      reopened.remove("phone", wrongWhole); reopened.remove("phone", wrongWhole);
      const auto unexpected = root + "/podjs-companion-incoming-app/peer-phone/file/unknown";
      { std::ofstream extra(unexpected); extra << "preserve"; }
      rejects([&] { reopened.remove("phone", manifest); }); assert(std::filesystem::exists(unexpected));
      std::filesystem::remove(unexpected); reopened.remove("phone", manifest);
      assert(!std::filesystem::exists(root + "/podjs-companion-incoming-app/peer-phone/file"));
    }
    const auto journal = root + "/podjs-companion-incoming-app/journal.json"; assert(chmod(journal.c_str(), 0644) == 0);
    { Storage store(root, "app"); rejects([&] { store.readJournal(); }); }
    std::filesystem::remove_all(root);
  } catch (...) { std::filesystem::remove_all(root); throw; }
}
