#include "render_thread.h"
#include <android/log.h>
#include <chrono>

namespace {
double nowMs() { return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
}
RenderThread::RenderThread(ANativeWindow* window, uint32_t w, uint32_t h, uint32_t rw, uint32_t rh)
    : rasterBytes_(size_t{rw}*rh*4), worker_([this]{ run(); }) {
  ANativeWindow_acquire(window);
  control([&, window] {
    renderer_ = std::make_unique<VulkanRenderer>(window,w,h,rw,rh);
    ANativeWindow_release(window);
    valid_ = renderer_->valid();
    attached_ = window != nullptr;
  });
}
RenderThread::~RenderThread() {
  control([this] { renderer_.reset(); fallback_.clear(); valid_=false; });
  {
    std::lock_guard lock(mutex_);
    stopping_=true;
  }
  wake_.notify_one();
  worker_.join();
}
void RenderThread::control(std::function<void()> fn, bool cancelPending) {
  auto ack=std::make_shared<std::promise<void>>();
  auto done=ack->get_future();
  std::unique_ptr<Frame> discarded;
  {
    std::lock_guard lock(mutex_);
    accepting_=false;
    if(cancelPending) { discarded=std::move(pending_); acceptedHash_=0; }
    command_=[this, ack, fn=std::move(fn)] {
      fn();
      {
        std::lock_guard lock(mutex_);
        accepting_=attached_ && !paused_ && valid_.load();
      }
      ack->set_value();
    };
  }
  discarded.reset();
  wake_.notify_one();
  done.get();
}
void RenderThread::replaceWindow(ANativeWindow* window,uint32_t w,uint32_t h) {
  if(window) ANativeWindow_acquire(window);
  control([&, window] {
    renderer_->replaceWindow(window,w,h);
    if(window) ANativeWindow_release(window);
    attached_=window!=nullptr;
    valid_=renderer_->valid();
  });
}
void RenderThread::setPaused(bool paused) {
  control([this,paused] {
    paused_=paused;
    if(paused) renderer_->drain();
    valid_=renderer_->valid();
  });
}
bool RenderThread::handoff(Snapshot snapshot,uint64_t sequence) {
  if(!snapshot) return false;
  PodDrawList list{};
  if(pod_render_snapshot_draw_list(snapshot.get(),&list)) return false;
  auto frame=std::make_unique<Frame>();
  frame->snapshot=std::move(snapshot); frame->sequence=sequence; frame->hash=list.content_hash;
  std::unique_ptr<Frame> discarded;
  {
    std::lock_guard lock(mutex_);
    if(!accepting_) return false;
    latestSequence_=sequence;
    if(pending_) ++replaced_;
    discarded=std::move(pending_);
    pending_=std::move(frame);
    acceptedHash_=list.content_hash;
  }
  // Drop old revision/font pins promptly, outside the queue lock.
  discarded.reset();
  wake_.notify_one();
  return true;
}
RenderThread::Poll RenderThread::poll(uint64_t sequence) {
  std::lock_guard lock(mutex_);
  latestSequence_=sequence;
  Poll out{completed_,replaced_,sequence>=lastCompleted_ && lastCompleted_ ? sequence-lastCompleted_ : 0};
  completed_.count=0; replaced_=0;
  return out;
}
void RenderThread::run() {
  for(;;) {
    std::function<void()> command;
    std::unique_ptr<Frame> frame;
    {
      std::unique_lock lock(mutex_);
      wake_.wait(lock,[this]{return stopping_||command_||pending_;});
      if(stopping_) return;
      if(command_) command=std::move(command_);
      else frame=std::move(pending_);
    }
    if(command) { command(); continue; }
    const double began=nowMs();
    Completion result{}; result.sequence=frame->sequence;
    PodDrawList list{};
    pod_render_snapshot_draw_list(frame->snapshot.get(),&list);
    auto host=renderer_->beginHostFrame(frame->sequence);
    if(renderer_->needsRedraw(list.content_hash)) {
      result.gpu=renderer_->submitDrawList(GpuResourceSource(frame->snapshot.get()),list);
      const auto attempt=renderer_->lastSubmitPerf();
      result.perf=attempt;
      if(!result.gpu && renderer_->valid()) {
        fallback_.resize(rasterBytes_);
        fallbackCapacity_=fallback_.capacity();
        result.cpu=pod_render_snapshot_rgba(frame->snapshot.get(),VulkanRenderer::kRasterScale,fallback_.data(),fallback_.size())==0 &&
            renderer_->submitRgba(fallback_.data(),fallback_.size(),list.content_hash);
        if(result.cpu) {
          result.perf=renderer_->lastSubmitPerf();
#define ADD(field) result.perf.field+=attempt.field
          ADD(decodeMs); ADD(uploadFenceMs); ADD(uploadMs); ADD(frameFenceMs); ADD(acquireMs);
          ADD(recordMs); ADD(queueSubmitMs); ADD(presentWaitMs);
#undef ADD
          result.perf.waitedForSlot|=attempt.waitedForSlot;
        }
      }
      if(!result.gpu && !result.cpu) acceptedHash_=0;
    }
    host.setFlags((int64_t{1}<<18) | (result.gpu ? int64_t{1}<<16 : 0) | (result.cpu ? int64_t{1}<<17 : 0));
    host.finish(result.gpu||result.cpu,0);
    result.totalMs=nowMs()-began;
    valid_=renderer_->valid();
    glyphBytes_=renderer_->glyphPageBytes(); glyphPages_=renderer_->glyphPageCount();
    {
      std::lock_guard lock(mutex_);
      result.lagFrames=latestSequence_>=frame->sequence?latestSequence_-frame->sequence:0;
      lastCompleted_=frame->sequence;
      // Between host polls only executing+pending can complete. Lifecycle/debug
      // loops can run without polls; diagnostics history is explicitly bounded.
      completed_.push(result);
    }
    __android_log_print(ANDROID_LOG_DEBUG,"PodJSRenderer",
        "frame seq=%llu rendererTotalMs=%.2f gpuDecodeMs=%.2f textureUploadMs=%.2f "
        "frameFenceMs=%.2f acquireMs=%.2f gpuRecordMs=%.2f queueSubmitMs=%.2f presentWaitMs=%.2f "
        "gpuUploads=%u gpuUploadBytes=%llu frameSlot=%u inFlight=%u slotReuse=%d gpu=%d cpu=%d rendererLagFrames=%llu",
        (unsigned long long)result.sequence,result.totalMs,result.perf.decodeMs,result.perf.uploadMs,
        result.perf.frameFenceMs,result.perf.acquireMs,result.perf.recordMs,result.perf.queueSubmitMs,result.perf.presentWaitMs,
        result.perf.uploadCount,(unsigned long long)result.perf.uploadBytes,result.perf.slotIndex,result.perf.inFlightFrames,
        result.perf.waitedForSlot,result.gpu,result.cpu,(unsigned long long)result.lagFrames);

  }
}
bool RenderThread::beginRendererTiming(uint32_t capacity) {
  bool ok=false; control([&]{ok=renderer_->beginRendererTiming(capacity);},false); return ok;
}
std::unique_ptr<podjs_gpu::RendererTimingTrace> RenderThread::endRendererTiming() {
  std::unique_ptr<podjs_gpu::RendererTimingTrace> trace;
  control([&]{trace=renderer_->endRendererTiming();},false); return trace;
}
VulkanRenderer::MemoryStats RenderThread::memoryStats() {
  VulkanRenderer::MemoryStats stats;
  control([&]{stats=renderer_->memoryStats();},false); return stats;
}
