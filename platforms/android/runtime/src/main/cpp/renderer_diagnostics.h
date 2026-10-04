#pragma once

#include <array>
#include <bit>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <utility>
#include <time.h>

namespace podjs_gpu {

enum class RendererTimingStage : int64_t {
  HostFrame = 1,
  Guest = 2,
  Snapshot = 3,
  DrawList = 9,
  Decode = 10,
  UploadImages = 11,
  UploadPriorFenceWait = 12,
  CreateTexture = 13,
  StagingCopy = 14,
  UploadRecord = 15,
  UploadSubmit = 16,
  UploadFenceWait = 17,
  FrameFenceWait = 18,
  AcquireImage = 19,
  SceneRecord = 20,
  FrameSubmit = 21,
  Present = 22,
  DestroyTexture = 23,
  CacheHit = 24,
  UploadCommitted = 25,
  StagingCapacity = 26,
  CpuFrame = 27,
};

struct RendererTimingFields {
  uint64_t textureKey{};
  uint64_t revision{};
  uint32_t width{};
  uint32_t height{};
  uint64_t bytes{};
  uint64_t count{};
  uint64_t flags{};
};

struct RendererTimingTrace {
  static constexpr uint32_t kSchemaVersion = 1;
  static constexpr uint32_t kFieldCount = 15;
  static constexpr uint32_t kHeaderFields = 7;
  static constexpr uint32_t kMaximumCapacity = 4096;

  // Schema, field count, capacity, completed row count, dropped row count,
  // CLOCK_MONOTONIC start ns, CLOCK_MONOTONIC stop ns.
  std::array<int64_t, kHeaderFields> header{};
  const int64_t* data() const { return rows_.get(); }

 private:
  friend class RendererTimingRecorder;
  std::unique_ptr<int64_t[]> rows_;
};

// A serialized host-thread recorder. Allocations occur only when enabled;
// active scopes use stack rows and append into a preallocated bounded buffer.
class RendererTimingRecorder {
 public:
  using Clock = uint64_t (*)();

  class Scope {
   public:
    Scope() = default;
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
    Scope(Scope&& other) noexcept
        : owner_(std::exchange(other.owner_, nullptr)), epoch_(other.epoch_),
          index_(other.index_), restoreHostContext_(other.restoreHostContext_),
          previousHostFrameSequence_(other.previousHostFrameSequence_) {
      if (owner_) row_ = other.row_;
    }
    Scope& operator=(Scope&&) = delete;
    ~Scope() {
      // Early returns and unwinding still produce a completed failure row.
      finish(false, std::numeric_limits<int64_t>::min());
    }

    void setFields(RendererTimingFields fields) {
      if (!owner_) return;
      row_[8] = std::bit_cast<int64_t>(fields.textureKey);
      row_[9] = std::bit_cast<int64_t>(fields.revision);
      row_[10] = fields.width;
      row_[11] = fields.height;
      row_[12] = std::bit_cast<int64_t>(fields.bytes);
      row_[13] = std::bit_cast<int64_t>(fields.count);
      row_[14] = std::bit_cast<int64_t>(fields.flags);
      publish();
    }

    void setFlags(int64_t flags) {
      if (owner_) {
        row_[14] = flags;
        publish();
      }
    }

    void finish(bool ok, int64_t resultCode = 0) {
      auto* owner = std::exchange(owner_, nullptr);
      if (!owner || !owner->current(epoch_)) return;
      if (index_ != kNoRow) {
        row_[5] = std::bit_cast<int64_t>(owner->clock_());
        row_[6] = ok ? 1 : -1;
        row_[7] = resultCode;
        owner->write(index_, row_);
      }
      if (restoreHostContext_) {
        owner->hostFrameSequence_ = previousHostFrameSequence_;
      }
    }

   private:
    friend class RendererTimingRecorder;
    Scope(RendererTimingRecorder* owner, RendererTimingStage stage,
          RendererTimingFields fields, bool restoreHostContext = false,
          uint64_t previousHostFrameSequence = 0)
        : owner_(owner), epoch_(owner->epoch_),
          restoreHostContext_(restoreHostContext),
          previousHostFrameSequence_(previousHostFrameSequence) {
      row_.fill(0);
      row_[1] = std::bit_cast<int64_t>(owner->hostFrameSequence_);
      row_[2] = std::bit_cast<int64_t>(owner->rendererFrameSerial_);
      row_[3] = static_cast<int64_t>(stage);
      row_[4] = std::bit_cast<int64_t>(owner->clock_());
      row_[6] = -1;
      row_[7] = std::numeric_limits<int64_t>::min();
      setFields(fields);
      index_ = owner->reserve(row_);
    }

    void publish() {
      if (index_ != kNoRow && owner_->current(epoch_)) {
        owner_->write(index_, row_);
      }
    }

    static constexpr size_t kNoRow = std::numeric_limits<size_t>::max();
    RendererTimingRecorder* owner_ = nullptr;
    uint64_t epoch_ = 0;
    size_t index_ = kNoRow;
    bool restoreHostContext_ = false;
    uint64_t previousHostFrameSequence_ = 0;
    // Intentionally uninitialized for disabled scopes: no row is ever read.
    std::array<int64_t, RendererTimingTrace::kFieldCount> row_;
  };

  explicit RendererTimingRecorder(Clock clock = monotonicNs) : clock_(clock) {}
  RendererTimingRecorder(const RendererTimingRecorder&) = delete;
  RendererTimingRecorder& operator=(const RendererTimingRecorder&) = delete;

  bool enabled() const { return active_ != nullptr; }

  bool begin(uint32_t capacity) {
    if (active_ || capacity == 0 ||
        capacity > RendererTimingTrace::kMaximumCapacity) {
      return false;
    }
    auto trace = std::unique_ptr<RendererTimingTrace>(
        new (std::nothrow) RendererTimingTrace);
    if (!trace) return false;
    trace->rows_.reset(new (std::nothrow)
                          int64_t[size_t{capacity} * RendererTimingTrace::kFieldCount]{});
    if (!trace->rows_) return false;
    trace->header = {RendererTimingTrace::kSchemaVersion,
                     RendererTimingTrace::kFieldCount, capacity, 0, 0, 0, 0};
    ++epoch_;
    sequence_ = 0;
    hostFrameSequence_ = 0;
    rendererFrameSerial_ = 0;
    active_ = std::move(trace);
    active_->header[5] = std::bit_cast<int64_t>(clock_());
    return true;
  }

  std::unique_ptr<RendererTimingTrace> end() {
    // Disable before reading the stop clock or any caller allocates/export rows.
    // A live scope from the prior session cannot write into a later session.
    auto trace = std::move(active_);
    ++epoch_;
    hostFrameSequence_ = 0;
    if (trace) {
      trace->header[6] = std::bit_cast<int64_t>(clock_());
      for (int64_t i = 0; i < trace->header[3]; ++i) {
        auto* row = trace->rows_.get() + i * RendererTimingTrace::kFieldCount;
        if (row[5] == 0) row[5] = trace->header[6];
      }
    }
    return trace;
  }

  void setContext(uint64_t hostFrameSequence, uint64_t rendererFrameSerial) {
    hostFrameSequence_ = hostFrameSequence;
    rendererFrameSerial_ = rendererFrameSerial;
  }

  void setRendererFrameSerial(uint64_t rendererFrameSerial) {
    rendererFrameSerial_ = rendererFrameSerial;
  }

  Scope scope(RendererTimingStage stage, RendererTimingFields fields = {}) {
    if (!active_) return {};
    return Scope(this, stage, fields);
  }

  Scope beginHostFrame(uint64_t hostFrameSequence, uint64_t rendererFrameSerial) {
    if (!active_) return {};
    const uint64_t previousHostFrameSequence = hostFrameSequence_;
    setContext(hostFrameSequence, rendererFrameSerial);
    return Scope(this, RendererTimingStage::HostFrame, {}, true,
                 previousHostFrameSequence);
  }

 private:
  static uint64_t monotonicNs() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<uint64_t>(value.tv_sec) * 1000000000u +
           static_cast<uint64_t>(value.tv_nsec);
  }

  bool current(uint64_t epoch) const { return active_ && epoch == epoch_; }

  size_t reserve(std::array<int64_t, RendererTimingTrace::kFieldCount>& row) {
    row[0] = std::bit_cast<int64_t>(++sequence_);
    const auto count = static_cast<size_t>(active_->header[3]);
    if (count == static_cast<size_t>(active_->header[2])) {
      ++active_->header[4];
      return Scope::kNoRow;
    }
    write(count, row);
    ++active_->header[3];
    return count;
  }

  void write(size_t index,
             const std::array<int64_t, RendererTimingTrace::kFieldCount>& row) {
    auto* destination = active_->rows_.get() +
                        index * RendererTimingTrace::kFieldCount;
    for (size_t i = 0; i < row.size(); ++i) destination[i] = row[i];
  }

  Clock clock_;
  uint64_t epoch_ = 0;
  uint64_t sequence_ = 0;
  uint64_t hostFrameSequence_ = 0;
  uint64_t rendererFrameSerial_ = 0;
  std::unique_ptr<RendererTimingTrace> active_;
};

}  // namespace podjs_gpu
