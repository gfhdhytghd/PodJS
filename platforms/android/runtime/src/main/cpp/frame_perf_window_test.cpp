#include "renderer_diagnostics.h"
#include "frame_perf_window.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition);     \
      std::abort();                                                            \
    }                                                                          \
  } while (false)

namespace {

void percentilesAndDrawOnlyStagesAreExact() {
  podjs_perf::FramePerfWindow window;
  podjs_perf::WindowSummary summary;
  int64_t vsync = 1'000'000'000;
  for (int i = 1; i <= 20; ++i) {
    podjs_perf::FrameSample sample;
    sample.nativeTotalMs = i;
    sample.jsTurnMs = i * 0.1;
    sample.layoutMs = i * 0.2;
    sample.drawListMs = i * 0.3;
    sample.gpuSubmitted = i > 10;
    sample.waitedForSlot = i > 12;
    sample.inFlightFrames = i > 11 ? 2 : 1;
    sample.gpuDecodeMs = i;
    sample.presentWaitMs = i * 2;
    sample.uploadCount = i == 20 ? 2 : 0;
    sample.uploadBytes = i == 20 ? 4096 : 0;
    const double now = i == 20 ? 2100 : 1000 + i * 10;
    const bool reported = window.add(now, vsync, sample, &summary);
    vsync += 16'666'667;
    CHECK(reported == (i == 20));
  }
  CHECK(summary.ticks == 20);
  CHECK(summary.draws == 10 && summary.gpuDraws == 10);
  CHECK(summary.nativeTotal.p50 == 10);
  CHECK(summary.nativeTotal.p95 == 19);
  CHECK(summary.nativeTotal.max == 20);
  CHECK(summary.gpuDecode.p50 == 15);
  CHECK(summary.gpuDecode.p95 == 20);
  CHECK(summary.presentWait.max == 40);
  CHECK(summary.overBudget == 4);
  CHECK(summary.slotReuses == 8 && summary.maxInFlight == 2);
  CHECK(summary.uploadFrames == 1 && summary.uploadBytes == 4096);
}

void cadenceCountsMissedVsyncsAndLifecycleReset() {
  podjs_perf::FramePerfWindow window;
  podjs_perf::WindowSummary summary;
  podjs_perf::FrameSample sample;
  CHECK(!window.add(1000, 1'000'000'000, sample, &summary));
  CHECK(!window.add(1016, 1'016'666'667, sample, &summary));
  // Three target intervals between callbacks means two missed 60 Hz frames.
  CHECK(!window.add(1066, 1'066'666'668, sample, &summary));
  window.resetCadence();
  CHECK(window.add(2100, 9'000'000'000, sample, &summary));
  CHECK(summary.dropped == 2);
}

void spikesHaveStrictThresholdAndReset() {
  podjs_perf::FramePerfWindow window;
  podjs_perf::WindowSummary summary;
  podjs_perf::FrameSample sample;
  sample.nativeTotalMs = 33; sample.jsTurnMs = 33;
  CHECK(!window.add(1000, 1'000'000'000, sample, &summary));
  sample.nativeTotalMs = 33.01; sample.jsTurnMs = 34;
  CHECK(!window.add(1100, 1'033'000'000, sample, &summary));
  sample.nativeTotalMs = 50; sample.jsTurnMs = 10;
  CHECK(window.add(2100, 1'066'000'001, sample, &summary));
  CHECK(summary.nativeOver33 == 2 && summary.jsOver33 == 1);
  CHECK(summary.cadenceOver33 == 1);
  window.resetCadence();
  sample = {};
  CHECK(window.add(3100, 9'000'000'000, sample, &summary));
  CHECK(summary.nativeOver33 == 0 && summary.jsOver33 == 0);
  CHECK(summary.cadenceOver33 == 0);
}

void asynchronousDrawsDoNotIncreaseHostTicks() {
  podjs_perf::FramePerfWindow window;
  podjs_perf::WindowSummary summary;
  podjs_perf::FrameSample host;
  host.nativeTotalMs=2;host.handoffMs=0.1;host.queueReplaced=3;host.rendererLagFrames=4;
  CHECK(!window.add(1000,1'000'000'000,host,&summary));
  podjs_perf::FrameSample completed;
  completed.gpuSubmitted=true;completed.presentWaitMs=25;completed.waitedForSlot=true;completed.inFlightFrames=2;
  window.addDraw(completed);window.addDraw(completed);
  host.queueReplaced=0;
  CHECK(window.add(2100,1'016'666'667,host,&summary));
  CHECK(summary.ticks==2 && summary.draws==2 && summary.slotReuses==2);
  CHECK(summary.presentWait.max==25 && summary.nativeTotal.max==2);
  CHECK(summary.handoff.max==0.1 && summary.queueReplaced==3 && summary.rendererLagFrames==4);
}

void laneTraceMergeRetainsBoundedSchemaAndContext() {
  podjs_gpu::RendererTimingRecorder host, renderer;
  CHECK(host.begin(2) && renderer.begin(2));
  auto h=host.beginHostFrame(10,0);
  auto r=renderer.beginHostFrame(10,1);
  h.setFlags(int64_t{1}<<19);r.setFlags(int64_t{1}<<18);
  h.finish(true);r.finish(true);
  auto guest=host.scope(podjs_gpu::RendererTimingStage::Guest);guest.finish(true);
  auto present=renderer.scope(podjs_gpu::RendererTimingStage::Present);present.finish(true);
  auto merged=podjs_gpu::RendererTimingTrace::merge(host.end(),renderer.end());
  CHECK(merged && merged->header[0]==1 && merged->header[1]==15);
  CHECK(merged->header[2]==2 && merged->header[3]==2 && merged->header[4]==2);
  CHECK(merged->data()[1]==10 && merged->data()[16]==10);
}

} // namespace

int main() {
  laneTraceMergeRetainsBoundedSchemaAndContext();
  asynchronousDrawsDoNotIncreaseHostTicks();
  percentilesAndDrawOnlyStagesAreExact();
  cadenceCountsMissedVsyncsAndLifecycleReset();
  spikesHaveStrictThresholdAndReset();
  std::puts("frame_perf_window_test: ok");
  return 0;
}
