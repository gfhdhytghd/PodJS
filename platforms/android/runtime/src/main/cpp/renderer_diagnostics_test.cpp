#include "renderer_diagnostics.h"

#include <array>
#include <bit>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <optional>
#include <type_traits>
#include <utility>

namespace {
using podjs_gpu::RendererTimingFields;
using podjs_gpu::RendererTimingRecorder;
using podjs_gpu::RendererTimingStage;
using podjs_gpu::RendererTimingTrace;
using Scope = decltype(std::declval<RendererTimingRecorder&>().scope(
    RendererTimingStage::Guest));

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition);       \
      std::abort();                                                            \
    }                                                                          \
  } while (false)

enum Field : size_t {
  Sequence, HostFrame, RendererFrame, Stage, Begin, End, Outcome, Result,
  Key, Revision, Width, Height, Bytes, Count, Flags, FieldCount
};
static_assert(FieldCount == 15);
static_assert(!std::is_copy_constructible_v<Scope>);
static_assert(!std::is_copy_assignable_v<Scope>);
static_assert(std::is_move_constructible_v<Scope>);
static_assert(static_cast<int64_t>(RendererTimingStage::HostFrame) == 1);
static_assert(static_cast<int64_t>(RendererTimingStage::Snapshot) == 3);
static_assert(static_cast<int64_t>(RendererTimingStage::DrawList) == 9);
static_assert(static_cast<int64_t>(RendererTimingStage::CpuFrame) == 27);

uint64_t clockCalls = 0;
uint64_t nextTime = 1000;
RendererTimingRecorder* stoppingRecorder = nullptr;
bool observedDisabledAtStop = false;

uint64_t fakeClock() {
  if (stoppingRecorder) {
    CHECK(!stoppingRecorder->enabled());
    observedDisabledAtStop = true;
  }
  ++clockCalls;
  const uint64_t value = nextTime;
  nextTime += 10;
  return value;
}

void resetClock() {
  CHECK(stoppingRecorder == nullptr);
  clockCalls = 0;
  nextTime = 1000;
  observedDisabledAtStop = false;
}

const int64_t* row(const RendererTimingTrace& trace, size_t index) {
  CHECK(index < static_cast<size_t>(trace.header[3]));
  CHECK(trace.data() != nullptr);
  return trace.data() + index * FieldCount;
}

void checkHeader(const RendererTimingTrace& trace, int64_t capacity,
                 int64_t count, int64_t dropped) {
  CHECK(trace.header[0] == 1);
  CHECK(trace.header[1] == FieldCount);
  CHECK(trace.header[2] == capacity);
  CHECK(trace.header[3] == count);
  CHECK(trace.header[4] == dropped);
  CHECK(trace.header[5] == 1000);
  CHECK(trace.header[6] >= trace.header[5]);
}

void disabledRecorderNeverReadsClock() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(!recorder.enabled());
  recorder.setContext(12, 34);
  {
    auto completed = recorder.scope(RendererTimingStage::Decode);
    completed.setFields({.textureKey = 91, .bytes = 32});
    completed.setFlags(-7);
    completed.finish(true);
    auto abandoned = recorder.scope(RendererTimingStage::UploadImages);
  }
  CHECK(recorder.end() == nullptr);
  CHECK(clockCalls == 0);
}

void invalidAndRepeatedBeginPreserveTheActiveTrace() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  for (uint32_t capacity : {0u, 4097u, std::numeric_limits<uint32_t>::max()}) {
    CHECK(!recorder.begin(capacity));
    CHECK(!recorder.enabled());
  }
  CHECK(clockCalls == 0);
  CHECK(recorder.begin(4));
  recorder.setContext(17, 23);
  CHECK(!recorder.begin(2));
  CHECK(!recorder.begin(0));
  CHECK(!recorder.begin(4097));
  CHECK(recorder.enabled());
  CHECK(clockCalls == 1);
  auto scope = recorder.scope(RendererTimingStage::HostFrame);
  scope.finish(true);
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 4, 1, 0);
  CHECK(row(*trace, 0)[HostFrame] == 17);
  CHECK(row(*trace, 0)[RendererFrame] == 23);
  CHECK(trace->header[6] == 1030);
}

void completedAndAbandonedScopesHaveExplicitOutcomes() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(8));
  recorder.setContext(11, 22);
  {
    auto success = recorder.scope(RendererTimingStage::FrameSubmit);
    success.finish(true, 7);
    success.finish(false, -99);
    auto failure = recorder.scope(RendererTimingStage::UploadSubmit);
    failure.finish(false, -4);
    auto abandoned = recorder.scope(RendererTimingStage::Present);
  }
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 8, 3, 0);
  for (size_t i = 0; i < 3; ++i) {
    const auto* event = row(*trace, i);
    CHECK(event[Sequence] == static_cast<int64_t>(i + 1));
    CHECK(event[HostFrame] == 11 && event[RendererFrame] == 22);
    CHECK(event[Begin] == 1010 + static_cast<int64_t>(i) * 20);
    CHECK(event[End] == event[Begin] + 10);
  }
  CHECK(row(*trace, 0)[Outcome] == 1 && row(*trace, 0)[Result] == 7);
  CHECK(row(*trace, 1)[Outcome] == -1 && row(*trace, 1)[Result] == -4);
  CHECK(row(*trace, 2)[Outcome] == -1);
  CHECK(row(*trace, 2)[Result] == std::numeric_limits<int64_t>::min());
  CHECK(trace->header[6] == 1070);
  CHECK(clockCalls == 8);
}

void nestedScopesReserveRowsInBeginOrder() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4));
  recorder.setContext(2, 3);
  auto outer = recorder.scope(RendererTimingStage::DrawList);
  auto inner = recorder.scope(RendererTimingStage::Decode);
  inner.finish(true);
  outer.finish(true);
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 4, 2, 0);
  const auto* drawn = row(*trace, 0);
  const auto* decoded = row(*trace, 1);
  CHECK(drawn[Sequence] == 1 && decoded[Sequence] == 2);
  CHECK(decoded[Stage] == static_cast<int64_t>(RendererTimingStage::Decode));
  CHECK(drawn[Stage] == static_cast<int64_t>(RendererTimingStage::DrawList));
  CHECK(decoded[Begin] == 1020 && decoded[End] == 1030);
  CHECK(drawn[Begin] == 1010 && drawn[End] == 1040);
  CHECK(trace->header[6] == 1050);
}

void fieldsPreserveUnsignedBitPatternsAndLateMetadata() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4));
  const RendererTimingFields fields{
      .textureKey = 0x8000000000010007ULL,
      .revision = 0xfedcba9876543210ULL,
      .width = 37,
      .height = 31,
      .bytes = 37 * 31 * 4,
      .count = 8,
      .flags = 0x8000000000000005ULL};
  auto initial = recorder.scope(RendererTimingStage::CreateTexture, fields);
  initial.finish(true);
  auto updated = recorder.scope(RendererTimingStage::UploadCommitted);
  updated.setFields(fields);
  const int64_t updatedFlags = std::numeric_limits<int64_t>::min() + 42;
  updated.setFlags(updatedFlags);
  updated.finish(true);
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 4, 2, 0);
  for (size_t i = 0; i < 2; ++i) {
    const auto* event = row(*trace, i);
    CHECK(std::bit_cast<uint64_t>(event[Key]) == fields.textureKey);
    CHECK(std::bit_cast<uint64_t>(event[Revision]) == fields.revision);
    CHECK(event[Width] == 37 && event[Height] == 31);
    CHECK(event[Bytes] == 37 * 31 * 4 && event[Count] == 8);
  }
  CHECK(std::bit_cast<uint64_t>(row(*trace, 0)[Flags]) == fields.flags);
  CHECK(row(*trace, 1)[Flags] == updatedFlags);
}

void boundedTraceKeepsFirstRowsAndCountsOverflow() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(2));
  for (uint64_t i = 0; i < 5; ++i) {
    recorder.setContext(100 + i, 200 + i);
    auto scope = recorder.scope(RendererTimingStage::CacheHit,
                                {.textureKey = 300 + i, .revision = 400 + i});
    scope.finish(i % 2 == 0, -static_cast<int64_t>(i));
  }
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 2, 2, 3);
  for (size_t i = 0; i < 2; ++i) {
    const auto* event = row(*trace, i);
    CHECK(event[Sequence] == static_cast<int64_t>(i + 1));
    CHECK(event[HostFrame] == static_cast<int64_t>(100 + i));
    CHECK(event[RendererFrame] == static_cast<int64_t>(200 + i));
    CHECK(event[Key] == static_cast<int64_t>(300 + i));
    CHECK(event[Revision] == static_cast<int64_t>(400 + i));
    CHECK(event[Outcome] == (i == 0 ? 1 : -1));
  }
}

void endDisablesBeforeReadingItsStopTimestamp() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4096));
  stoppingRecorder = &recorder;
  auto trace = recorder.end();
  stoppingRecorder = nullptr;
  CHECK(observedDisabledAtStop);
  CHECK(!recorder.enabled());
  CHECK(trace != nullptr);
  checkHeader(*trace, 4096, 0, 0);
  CHECK(trace->header[6] == 1010);
  CHECK(recorder.end() == nullptr);
  CHECK(clockCalls == 2);
}

void staleScopesCannotWriteIntoAStoppedOrNewTrace() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4));
  auto staleFinished = recorder.beginHostFrame(77, 88);
  std::optional<Scope> staleAbandoned;
  staleAbandoned.emplace(recorder.scope(RendererTimingStage::DrawList));
  auto stopped = recorder.end();
  CHECK(stopped != nullptr);
  checkHeader(*stopped, 4, 2, 0);
  const auto stoppedHeader = stopped->header;
  std::array<int64_t, 2 * FieldCount> stoppedRows{};
  for (size_t i = 0; i < stoppedRows.size(); ++i) {
    stoppedRows[i] = stopped->data()[i];
  }
  for (size_t i = 0; i < 2; ++i) {
    CHECK(row(*stopped, i)[Sequence] == static_cast<int64_t>(i + 1));
    CHECK(row(*stopped, i)[Begin] == 1010 + static_cast<int64_t>(i) * 10);
    CHECK(row(*stopped, i)[End] == stopped->header[6]);
    CHECK(row(*stopped, i)[Outcome] == -1);
    CHECK(row(*stopped, i)[Result] == std::numeric_limits<int64_t>::min());
  }
  CHECK(recorder.begin(4));
  recorder.setContext(91, 92);
  const uint64_t callsBeforeStale = clockCalls;
  staleFinished.setFields({.textureKey = 987, .bytes = 123});
  staleFinished.setFlags(-456);
  staleFinished.finish(true);
  staleAbandoned.reset();
  CHECK(clockCalls == callsBeforeStale);
  auto fresh = recorder.scope(RendererTimingStage::CpuFrame);
  fresh.finish(true);
  auto restarted = recorder.end();
  CHECK(restarted != nullptr);
  CHECK(restarted->header[3] == 1 && restarted->header[4] == 0);
  CHECK(row(*restarted, 0)[Sequence] == 1);
  CHECK(row(*restarted, 0)[HostFrame] == 91);
  CHECK(row(*restarted, 0)[RendererFrame] == 92);
  CHECK(row(*restarted, 0)[Stage] == static_cast<int64_t>(RendererTimingStage::CpuFrame));
  CHECK(stopped->header == stoppedHeader);
  for (size_t i = 0; i < stoppedRows.size(); ++i) {
    CHECK(stopped->data()[i] == stoppedRows[i]);
  }
}

void hostFrameFinishRestoresDefaultHostContext() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4));
  auto frame = recorder.beginHostFrame(41, 100);
  auto guest = recorder.scope(RendererTimingStage::Guest);
  guest.finish(true);
  frame.finish(true);
  auto external = recorder.scope(RendererTimingStage::CreateTexture);
  external.finish(true);
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 4, 3, 0);
  CHECK(row(*trace, 0)[Stage] == static_cast<int64_t>(RendererTimingStage::HostFrame));
  CHECK(row(*trace, 0)[HostFrame] == 41);
  CHECK(row(*trace, 0)[RendererFrame] == 100);
  CHECK(row(*trace, 0)[Outcome] == 1);
  CHECK(row(*trace, 1)[HostFrame] == 41);
  CHECK(row(*trace, 1)[RendererFrame] == 100);
  CHECK(row(*trace, 2)[HostFrame] == 0);
}

void nestedHostFrameDestructorsRestorePreviousHostContexts() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(8));
  recorder.setContext(17, 23);
  {
    auto outer = recorder.beginHostFrame(41, 100);
    {
      auto inner = recorder.beginHostFrame(42, 101);
      auto inside = recorder.scope(RendererTimingStage::Decode);
      inside.finish(true);
    }
    auto restoredOuter = recorder.scope(RendererTimingStage::CacheHit);
    restoredOuter.finish(true);
  }
  auto restoredPrevious = recorder.scope(RendererTimingStage::StagingCapacity);
  restoredPrevious.finish(true);
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 8, 5, 0);
  const int64_t expectedHosts[]{41, 42, 42, 41, 17};
  for (size_t i = 0; i < 5; ++i) {
    CHECK(row(*trace, i)[Sequence] == static_cast<int64_t>(i + 1));
    CHECK(row(*trace, i)[HostFrame] == expectedHosts[i]);
    CHECK(row(*trace, i)[Outcome] == (i < 2 ? -1 : 1));
  }
}

void movedScopesCompleteExactlyOnce() {
  resetClock();
  RendererTimingRecorder recorder(fakeClock);
  CHECK(recorder.begin(4));
  {
    auto source = recorder.scope(RendererTimingStage::StagingCopy,
                                 {.textureKey = 42, .bytes = 1024});
    auto destination = std::move(source);
    source.finish(false, -88);
    destination.finish(true);
    destination.finish(false, -99);
  }
  auto trace = recorder.end();
  CHECK(trace != nullptr);
  checkHeader(*trace, 4, 1, 0);
  CHECK(row(*trace, 0)[Key] == 42 && row(*trace, 0)[Bytes] == 1024);
  CHECK(row(*trace, 0)[Outcome] == 1 && row(*trace, 0)[Result] == 0);
  CHECK(trace->header[6] == 1030);
  CHECK(clockCalls == 4);
}
}  // namespace

int main() {
  disabledRecorderNeverReadsClock();
  invalidAndRepeatedBeginPreserveTheActiveTrace();
  completedAndAbandonedScopesHaveExplicitOutcomes();
  nestedScopesReserveRowsInBeginOrder();
  fieldsPreserveUnsignedBitPatternsAndLateMetadata();
  boundedTraceKeepsFirstRowsAndCountsOverflow();
  endDisablesBeforeReadingItsStopTimestamp();
  staleScopesCannotWriteIntoAStoppedOrNewTrace();
  hostFrameFinishRestoresDefaultHostContext();
  nestedHostFrameDestructorsRestorePreviousHostContexts();
  movedScopesCompleteExactlyOnce();
  std::puts("renderer diagnostics: 11 tests passed");
}
