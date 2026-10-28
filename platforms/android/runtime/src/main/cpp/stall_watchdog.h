#pragma once
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <optional>

namespace podjs_health {
// No draw demand means no renderer deadline. Replacing queued frames cannot
// reset the oldest outstanding deadline and hide a stuck GPU/worker.
class StallWatchdog {
 public:
  enum class Stage { idle, queued, render, control };
  struct Report { Stage stage; uint64_t delayNs, stalls, logs; };
  static constexpr uint64_t thresholdNs=1'000'000'000, maxLogs=16;
  void demand(uint64_t now) {
    std::lock_guard lock(mutex_);
    if(!active_) {active_=true;since_=now;reported_=false;stage_=Stage::queued;}
  }
  void stage(Stage value,uint64_t now) {
    demand(now);std::lock_guard lock(mutex_);stage_=value;
  }
  void progress(uint64_t now,bool more) {
    std::lock_guard lock(mutex_);
    active_=more;since_=now;reported_=false;stage_=more?Stage::queued:Stage::idle;
  }
  std::optional<Report> observe(uint64_t now) {
    std::lock_guard lock(mutex_);
    if(!active_)return {};
    const auto delay=now>=since_?now-since_:0;
    maxDelay_=std::max(maxDelay_,delay);
    if(reported_||delay<=thresholdNs)return {};
    reported_=true;++stalls_;
    if(logs_>=maxLogs)return {};
    return Report{stage_,delay,stalls_,++logs_};
  }
  Report summary() const {std::lock_guard lock(mutex_);return {stage_,maxDelay_,stalls_,logs_};}
  static const char* label(Stage stage) {
    switch(stage){case Stage::queued:return "queued";case Stage::render:return "render";
      case Stage::control:return "control";default:return "idle";}
  }
 private:
  mutable std::mutex mutex_;
  bool active_{},reported_{};
  Stage stage_{Stage::idle};
  uint64_t since_{},stalls_{},logs_{},maxDelay_{};
};
}
