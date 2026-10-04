#pragma once

#include "podjs_runtime.h"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

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
  void pruneStale(PodRuntime *runtime);
  bool get(PodRuntime *runtime, uint32_t slot, GpuImage *out,
           std::string *error);
  bool getGlyph(PodRuntime *runtime, uint32_t slot, uint32_t glyph,
                GpuImage *out, std::string *error);

private:
  std::vector<GpuImage> entries_;
};

// Decodes the canonical DrawList into painter-ordered GPU primitives. The
// decoder owns copied texture/glyph data in out; all coordinates are physical
// pixels at `scale`, while clip rectangles remain half-open physical bounds.
bool decodeGpuDrawList(PodRuntime *runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, std::string *error);
bool decodeGpuDrawList(PodRuntime *runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, GpuDecodeCache *cache,
                       std::string *error);
