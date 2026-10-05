#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace podjs_perf {

struct Distribution {
  double p50{};
  double p95{};
  double max{};
};

struct FrameSample {
  double nativeTotalMs{};
  double jsTurnMs{};
  double layoutMs{};
  double drawListMs{};
  double gpuDecodeMs{};
  double uploadFenceMs{};
  double textureUploadMs{};
  double frameFenceMs{};
  double acquireMs{};
  double gpuRecordMs{};
  double queueSubmitMs{};
  double presentWaitMs{};
  uint64_t uploadBytes{};
  uint32_t uploadCount{};
  bool gpuSubmitted{};
  bool cpuSubmitted{};
};

struct WindowSummary {
  double windowMs{};
  uint32_t ticks{};
  uint32_t draws{};
  uint32_t gpuDraws{};
  uint32_t cpuDraws{};
  uint32_t dropped{};
  uint32_t overBudget{};
  uint32_t uploadFrames{};
  uint64_t uploadBytes{};
  Distribution nativeTotal;
  Distribution jsTurn;
  Distribution layout;
  Distribution drawList;
  Distribution gpuDecode;
  Distribution uploadFence;
  Distribution textureUpload;
  Distribution frameFence;
  Distribution acquire;
  Distribution gpuRecord;
  Distribution queueSubmit;
  Distribution presentWait;
};

// Bounded by the one-second reporting interval in normal operation. Keeping the
// samples rather than running averages makes p50/p95 useful for short scroll
// bursts and keeps the metric independent of Android's tracing facilities.
class FramePerfWindow {
public:
  static constexpr double kTargetFrameMs = 1000.0 / 60.0;
  static constexpr double kReportIntervalMs = 1000.0;

  FramePerfWindow() {
    nativeTotal_.reserve(128);
    jsTurn_.reserve(128);
    layout_.reserve(128);
    drawList_.reserve(128);
    gpuDecode_.reserve(128);
    uploadFence_.reserve(128);
    textureUpload_.reserve(128);
    frameFence_.reserve(128);
    acquire_.reserve(128);
    gpuRecord_.reserve(128);
    queueSubmit_.reserve(128);
    presentWait_.reserve(128);
  }

  bool add(double nowMs, int64_t frameTimeNanos, const FrameSample &sample,
           WindowSummary *out) {
    if (reportStartedMs_ == 0)
      reportStartedMs_ = nowMs;
    updateCadence(frameTimeNanos);

    nativeTotal_.push_back(sample.nativeTotalMs);
    jsTurn_.push_back(sample.jsTurnMs);
    layout_.push_back(sample.layoutMs);
    drawList_.push_back(sample.drawListMs);
    ++ticks_;
    if (sample.nativeTotalMs > kTargetFrameMs)
      ++overBudget_;
    if (sample.gpuSubmitted || sample.cpuSubmitted)
      ++draws_;
    if (sample.gpuSubmitted) {
      ++gpuDraws_;
      gpuDecode_.push_back(sample.gpuDecodeMs);
      uploadFence_.push_back(sample.uploadFenceMs);
      textureUpload_.push_back(sample.textureUploadMs);
      frameFence_.push_back(sample.frameFenceMs);
      acquire_.push_back(sample.acquireMs);
      gpuRecord_.push_back(sample.gpuRecordMs);
      queueSubmit_.push_back(sample.queueSubmitMs);
      presentWait_.push_back(sample.presentWaitMs);
      if (sample.uploadCount > 0)
        ++uploadFrames_;
      uploadBytes_ += sample.uploadBytes;
    }
    if (sample.cpuSubmitted)
      ++cpuDraws_;

    if (nowMs - reportStartedMs_ < kReportIntervalMs || !out)
      return false;
    out->windowMs = nowMs - reportStartedMs_;
    out->ticks = ticks_;
    out->draws = draws_;
    out->gpuDraws = gpuDraws_;
    out->cpuDraws = cpuDraws_;
    out->dropped = dropped_;
    out->overBudget = overBudget_;
    out->uploadFrames = uploadFrames_;
    out->uploadBytes = uploadBytes_;
    out->nativeTotal = summarize(nativeTotal_);
    out->jsTurn = summarize(jsTurn_);
    out->layout = summarize(layout_);
    out->drawList = summarize(drawList_);
    out->gpuDecode = summarize(gpuDecode_);
    out->uploadFence = summarize(uploadFence_);
    out->textureUpload = summarize(textureUpload_);
    out->frameFence = summarize(frameFence_);
    out->acquire = summarize(acquire_);
    out->gpuRecord = summarize(gpuRecord_);
    out->queueSubmit = summarize(queueSubmit_);
    out->presentWait = summarize(presentWait_);
    clearSamples();
    reportStartedMs_ = nowMs;
    return true;
  }

  // Lifecycle pauses are not dropped rendering frames. Keep accumulated window
  // samples, but start cadence accounting again at the next Choreographer tick.
  void resetCadence() { lastFrameTimeNanos_ = 0; }

private:
  static Distribution summarize(const std::vector<double> &values) {
    if (values.empty())
      return {};
    std::vector<double> sorted(values);
    std::sort(sorted.begin(), sorted.end());
    const auto nearestRank = [&sorted](double percentile) {
      const size_t rank = static_cast<size_t>(
          std::ceil(percentile * static_cast<double>(sorted.size())));
      return sorted[std::max<size_t>(1, rank) - 1];
    };
    return {nearestRank(0.50), nearestRank(0.95), sorted.back()};
  }

  void updateCadence(int64_t frameTimeNanos) {
    if (frameTimeNanos <= 0)
      return;
    if (lastFrameTimeNanos_ > 0 && frameTimeNanos > lastFrameTimeNanos_) {
      constexpr double targetNanos = kTargetFrameMs * 1'000'000.0;
      const double delta =
          static_cast<double>(frameTimeNanos - lastFrameTimeNanos_);
      const long long intervals = std::llround(delta / targetNanos);
      if (intervals > 1) {
        const uint64_t missed = static_cast<uint64_t>(intervals - 1);
        dropped_ = static_cast<uint32_t>(
            std::min<uint64_t>(std::numeric_limits<uint32_t>::max(),
                               static_cast<uint64_t>(dropped_) + missed));
      }
    }
    lastFrameTimeNanos_ = frameTimeNanos;
  }

  void clearSamples() {
    nativeTotal_.clear();
    jsTurn_.clear();
    layout_.clear();
    drawList_.clear();
    gpuDecode_.clear();
    uploadFence_.clear();
    textureUpload_.clear();
    frameFence_.clear();
    acquire_.clear();
    gpuRecord_.clear();
    queueSubmit_.clear();
    presentWait_.clear();
    ticks_ = draws_ = gpuDraws_ = cpuDraws_ = dropped_ = overBudget_ = 0;
    uploadFrames_ = 0;
    uploadBytes_ = 0;
  }

  double reportStartedMs_{};
  int64_t lastFrameTimeNanos_{};
  uint32_t ticks_{};
  uint32_t draws_{};
  uint32_t gpuDraws_{};
  uint32_t cpuDraws_{};
  uint32_t dropped_{};
  uint32_t overBudget_{};
  uint32_t uploadFrames_{};
  uint64_t uploadBytes_{};
  std::vector<double> nativeTotal_;
  std::vector<double> jsTurn_;
  std::vector<double> layout_;
  std::vector<double> drawList_;
  std::vector<double> gpuDecode_;
  std::vector<double> uploadFence_;
  std::vector<double> textureUpload_;
  std::vector<double> frameFence_;
  std::vector<double> acquire_;
  std::vector<double> gpuRecord_;
  std::vector<double> queueSubmit_;
  std::vector<double> presentWait_;
};

} // namespace podjs_perf
