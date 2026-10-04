#include "touch_snapshot_queue.h"

#include <cassert>

static std::vector<PodTouch> point(float x) { return {{0, x, 10}}; }

int main() {
  TouchSnapshotQueue taps;
  taps.submit(point(20));
  taps.submit({});
  assert(taps.nextFrame().size() == 1);  // DOWN survives an UP before vsync.
  assert(taps.nextFrame().empty());

  TouchSnapshotQueue drag;
  drag.submit(point(20));
  drag.submit(point(40));
  drag.submit(point(80));
  drag.submit({});
  assert(drag.nextFrame()[0].x == 20);  // First position is not coalesced.
  assert(drag.nextFrame()[0].x == 80);  // Only intermediate moves coalesce.
  assert(drag.nextFrame().empty());

  TouchSnapshotQueue multitouch;
  multitouch.submit(point(20));
  multitouch.submit({{0, 20, 10}, {1, 30, 10}});
  multitouch.submit({{0, 40, 10}, {1, 50, 10}});
  multitouch.submit(point(40));
  assert(multitouch.nextFrame().size() == 1);
  assert(multitouch.nextFrame().size() == 2);
  assert(multitouch.nextFrame()[1].x == 50);
  assert(multitouch.nextFrame().size() == 1);
  multitouch.clear();
  assert(multitouch.nextFrame().empty());
}
