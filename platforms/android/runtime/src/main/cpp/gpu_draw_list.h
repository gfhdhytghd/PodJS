#pragma once

#include "podjs_runtime.h"
#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

// Resolve resources from either the legacy synchronous runtime or a pinned
// immutable snapshot. Production renderer threads always use the latter.
struct GpuResourceSource {
  PodRuntime* runtime{};
  PodRenderSnapshot* snapshot{};
  GpuResourceSource(PodRuntime* r) : runtime(r) {}
  GpuResourceSource(PodRenderSnapshot* s) : snapshot(s) {}
  explicit operator bool() const { return runtime || snapshot; }
  int texture(int32_t handle, PodTextureView* out) const;
  int font(uint32_t slot, PodFontView* out) const;
  uint64_t epoch(uint32_t slot) const;
  int glyph(uint32_t slot, uint32_t gid, PodGlyphImageView* out) const;
};

enum class GpuPrimitiveType : uint8_t {
  Rect,
  GradientRect,
  Tri,
  TexQuad,
  TexTri,
  GlyphRun
};
struct GpuClip {
  int32_t x0{}, y0{}, x1{}, y1{};
};
struct GpuRoundedClip {
  // Original world-space bounds, before intersection with ancestor clips.
  float x0{}, y0{}, x1{}, y1{};
  float radiusX{}, radiusY{};
};
struct GpuVertex {
  int32_t x{}, y{};
  uint32_t color{};
  float u{}, v{};
};
struct GpuPrimitive {
  GpuPrimitiveType type{};
  GpuClip clip{};
  GpuVertex vertex[3]{};
  uint32_t width{}, height{}, textureSlot{};
  uint64_t textureKey{};
  uint32_t glyphSlot{};
  uint32_t glyphOrigin{},glyphSize{};
  bool linearCoverage{};
  bool subpixelCoverage{};
  float floatCoordinates[6]{};
  uint32_t roundedClipOffset{}, roundedClipCount{};
  std::vector<uint16_t> glyphs;
  uint32_t from{}, to{}, gradientDirection{};
};
struct GpuImage {
  uint32_t slot{}, width{}, height{};
  uint32_t rasterDensity{1};
  uint64_t revision{}, stableKey{};
  bool linear{};
  bool coverage{};
  bool colorGlyph{};
  uint32_t glyphOrigin{},glyphSize{};
  std::shared_ptr<const std::vector<uint8_t>> rgba;
};
struct GpuFrame {
  std::vector<GpuPrimitive> primitives;
  std::vector<GpuImage> images;
  // Active rounded clips are flattened per primitive so arbitrary nesting is
  // evaluated by the fragment shader without changing the 128-byte push ABI.
  std::vector<GpuRoundedClip> roundedClips;
};

bool gpuRoundedClipContainsPixel(const GpuRoundedClip& clip, float pixelX,
                                 float pixelY);

class GpuDecodeCache {
public:
  static constexpr uint32_t kFramesInFlight = 2;
  void pinFrame(uint32_t slot, const std::vector<GpuImage> &images);
  void releaseFrame(uint32_t slot);
  bool inFlight(uint64_t key) const;
  void pruneStale(GpuResourceSource runtime);
  void finishFrame(GpuFrame *frame);
  void memoryImages(std::unordered_set<const std::vector<uint8_t>*> &out) const {
    for (const auto &entry : entries_) if (entry.rgba) out.insert(entry.rgba.get());
    for (const auto &page : pages_) if (page.image.rgba) out.insert(page.image.rgba.get());
  }
  size_t textureSlots() const { return entries_.size(); }
  size_t glyphPageBytes() const {size_t bytes=0;for(const auto &p:pages_) bytes+=p.image.rgba->size();return bytes;}
  size_t glyphPageCount() const {return pages_.size();}
  bool get(GpuResourceSource runtime, uint32_t slot, GpuImage *out,
           std::string *error);
  bool getGlyph(GpuResourceSource runtime, uint32_t slot, uint32_t glyph,
                GpuImage *out, std::string *error);

private:
  struct Page {GpuImage image; uint64_t epoch{},last{},pinned{}; uint32_t cellW{},cellH{};std::vector<uint16_t> glyphs;std::vector<bool> colors;};
  std::array<std::unordered_set<uint64_t>, kFramesInFlight> inFlightPages_;
  std::vector<Page> pages_;
  uint64_t frame_{},clock_{},nextPage_{1};
  std::vector<GpuImage> entries_;
};

// Decodes the canonical DrawList into painter-ordered GPU primitives. The
// decoder owns copied texture/glyph data in out; all coordinates are physical
// pixels at `scale`, while clip rectangles remain half-open physical bounds.
bool decodeGpuDrawList(GpuResourceSource runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, std::string *error);
bool decodeGpuDrawList(GpuResourceSource runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, GpuDecodeCache *cache,
                       std::string *error);
