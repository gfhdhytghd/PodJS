#pragma once

#include <deque>
#include <utility>
#include <vector>

#include "podjs_runtime.h"

// Android can deliver a complete DOWN/UP tap between two Choreographer frames.
// Preserve changes in the active pointer set until the guest has seen each edge.
class TouchSnapshotQueue {
 public:
  void submit(std::vector<PodTouch> next) {
    if (pending_.empty()) {
      if (sameTouches(current_, next)) return;
      pending_.push_back(std::move(next));
      return;
    }
    auto& last = pending_.back();
    if (sameTouches(last, next)) return;
    if (sameIds(last, next)) {
      const auto& previous = pending_.size() == 1 ? current_ : pending_[pending_.size() - 2];
      if (sameIds(previous, last)) {
        // Repeated MOVE snapshots need only their newest coordinates.
        last = std::move(next);
        return;
      }
      // The first snapshot after a pointer-set change is an edge; keep it.
    }
    pending_.push_back(std::move(next));
  }

  const std::vector<PodTouch>& nextFrame() {
    if (!pending_.empty()) {
      current_ = std::move(pending_.front());
      pending_.pop_front();
    }
    return current_;
  }

  void clear() {
    pending_.clear();
    current_.clear();
  }

 private:
  static bool sameIds(const std::vector<PodTouch>& a, const std::vector<PodTouch>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) if (a[i].id != b[i].id) return false;
    return true;
  }

  static bool sameTouches(const std::vector<PodTouch>& a, const std::vector<PodTouch>& b) {
    if (!sameIds(a, b)) return false;
    for (size_t i = 0; i < a.size(); ++i)
      if (a[i].x != b[i].x || a[i].y != b[i].y) return false;
    return true;
  }

  std::vector<PodTouch> current_;
  std::deque<std::vector<PodTouch>> pending_;
};
