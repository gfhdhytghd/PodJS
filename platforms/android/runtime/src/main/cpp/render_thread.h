#pragma once
#include "vulkan_renderer.h"
#include "stall_watchdog.h"
#include <atomic>
#include <condition_variable>
#include <functional>
#include <future>
#include <mutex>
#include <thread>

// One executing frame and one replaceable pending frame, in addition to K11's
// two submitted GPU slots. No runtime/JS object is ever touched on this lane.
class RenderThread {
 public:
  struct SnapshotDeleter { void operator()(PodRenderSnapshot* s) const { pod_render_snapshot_destroy(s); } };
  using Snapshot = std::unique_ptr<PodRenderSnapshot, SnapshotDeleter>;
  struct Completion {
    uint64_t sequence{}, lagFrames{};
    double totalMs{},startedMonoMs{},finishedMonoMs{};
    uint64_t hash{};
    VulkanRenderer::SubmitPerf perf{};
    bool gpu{}, cpu{};
  };
  struct CompletionBatch {
    std::array<Completion,4> entries{};
    size_t count{};
    const Completion* begin() const { return entries.data(); }
    const Completion* end() const { return entries.data()+count; }
    bool empty() const { return count==0; }
    const Completion& back() const { return entries[count-1]; }
    void push(const Completion& value) {
      if(count==entries.size()) {
        std::move(entries.begin()+1,entries.end(),entries.begin()); --count;
      }
      entries[count++]=value;
    }
  };
  struct Poll {
    CompletionBatch completed;
    uint64_t replaced{}, lagFrames{};
  };
  RenderThread(ANativeWindow*, uint32_t, uint32_t, uint32_t, uint32_t);
  ~RenderThread();
  RenderThread(const RenderThread&) = delete;
  RenderThread& operator=(const RenderThread&) = delete;
  bool valid() const { return valid_.load(); }
  bool needsRedraw(uint64_t hash) const { return hash == 0 || acceptedHash_.load() != hash; }
  size_t rasterBytes() const { return rasterBytes_; }
  size_t fallbackCapacityBytes() const { return fallbackCapacity_.load(); }
  // Consumes ownership even if suspended; returned bool means queued, not displayed.
  bool handoff(Snapshot snapshot, uint64_t sequence);
  Poll poll(uint64_t sequence);
  void traceCheckpoint(uint64_t id);
  void replaceWindow(ANativeWindow*, uint32_t, uint32_t);
  void setPaused(bool);
  bool beginRendererTiming(uint32_t);
  std::unique_ptr<podjs_gpu::RendererTimingTrace> endRendererTiming();
  VulkanRenderer::MemoryStats memoryStats();
  size_t glyphPageBytes() const { return glyphBytes_.load(); }
  size_t glyphPageCount() const { return glyphPages_.load(); }
 private:
  struct Frame { Snapshot snapshot; uint64_t sequence{}, hash{}; };
  // Lifecycle/debug controls run on the owner lane after the executing frame.
  // Callers are serialized by Android's main host lane; controls stop handoff,
  // discard pending pins, drain Vulkan via replaceWindow/destruction, then ack.
  void control(std::function<void()>, bool cancelPending=true);
  void run();
  void observeStalls();
  podjs_health::StallWatchdog health_;
  std::atomic<bool> healthStopping_{};
  std::mutex healthMutex_;
  std::condition_variable healthWake_;
  mutable std::mutex mutex_;
  std::condition_variable wake_;
  std::unique_ptr<Frame> pending_;
  std::function<void()> command_;
  bool accepting_{}, attached_{}, paused_{}, stopping_{};
  CompletionBatch completed_;
  Completion lastCompletion_;
  std::atomic<uint64_t> traceCheckpointId_{};
  std::atomic<int> traceFrames_{};
  uint64_t replaced_{}, latestSequence_{}, lastCompleted_{};
  std::atomic<bool> valid_{};
  std::atomic<uint64_t> acceptedHash_{};
  std::atomic<size_t> glyphBytes_{}, glyphPages_{}, fallbackCapacity_{};
  const size_t rasterBytes_;
  std::unique_ptr<VulkanRenderer> renderer_; // worker only
  std::vector<uint8_t> fallback_; // worker only, allocated on demand
  std::thread worker_;
  std::thread observer_;
};
