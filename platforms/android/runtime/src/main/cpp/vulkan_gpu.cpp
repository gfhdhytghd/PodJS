#include "vulkan_gpu.h"
#include <bit>
namespace podjs_gpu {
RoundedClipData makeRoundedClipData(const GpuRoundedClip& clip) {
  RoundedClipData out{};
  out.rect[0] = clip.x0;
  out.rect[1] = clip.y0;
  out.rect[2] = clip.x1;
  out.rect[3] = clip.y1;
  out.radii[0] = clip.radiusX;
  out.radii[1] = clip.radiusY;
  return out;
}
PrimitivePush makePrimitivePush(const GpuPrimitive& p, uint32_t rasterWidth,
                                uint32_t rasterHeight, bool linear) {
  PrimitivePush out{};
  out.bounds[0] = p.clip.x0;
  out.bounds[1] = p.clip.y0;
  out.bounds[2] = p.clip.x1;
  out.bounds[3] = p.clip.y1;
  int32_t mode = 0;
  switch (p.type) {
    case GpuPrimitiveType::Rect: mode = 0; break;
    case GpuPrimitiveType::Tri: mode = 1; break;
    case GpuPrimitiveType::GradientRect: mode = 2; break;
    case GpuPrimitiveType::GlyphRun: mode = 3; break;
    case GpuPrimitiveType::TexQuad: mode = 4; break;
    case GpuPrimitiveType::TexTri: mode = 5; break;
  }
  if (mode == 1 || mode == 5) {
    out.xy01[0] = p.vertex[0].x;
    out.xy01[1] = p.vertex[0].y;
    out.xy01[2] = p.vertex[1].x;
    out.xy01[3] = p.vertex[1].y;
    out.xy2Mode[0] = p.vertex[2].x;
    out.xy2Mode[1] = p.vertex[2].y;
  } else {
    out.xy01[0] = p.vertex[0].x;
    out.xy01[1] = p.vertex[0].y;
    out.xy01[2] = p.vertex[0].x + static_cast<int32_t>(p.width);
    out.xy01[3] = p.vertex[0].y + static_cast<int32_t>(p.height);
  }
  if (p.subpixelCoverage) {
    mode = 6;
    for (int i = 0; i < 4; ++i) out.xy01[i] = std::bit_cast<int32_t>(p.floatCoordinates[i]);
    out.xy2Mode[0] = std::bit_cast<int32_t>(p.floatCoordinates[4]);
    out.xy2Mode[1] = std::bit_cast<int32_t>(p.floatCoordinates[5]);
  }
  out.xy2Mode[2] = mode;
  out.xy2Mode[3] = static_cast<int32_t>(p.gradientDirection);
  if (p.type == GpuPrimitiveType::GradientRect) {
    out.colors[0] = p.from;
    out.colors[1] = p.to;
  } else {
    for (int i = 0; i < 3; ++i) out.colors[i] = p.vertex[i].color;
  }
  out.uv01[0] = p.vertex[0].u;
  out.uv01[1] = p.vertex[0].v;
  out.uv01[2] = p.vertex[1].u;
  out.uv01[3] = p.vertex[1].v;
  out.uv2Extra[0] = p.vertex[2].u;
  out.uv2Extra[1] = p.vertex[2].v;
  if (mode == 1 || mode == 5) {
    const int64_t ax = int64_t{p.vertex[0].x} * 2;
    const int64_t ay = int64_t{p.vertex[0].y} * 2;
    const int64_t bx = int64_t{p.vertex[1].x} * 2;
    const int64_t by = int64_t{p.vertex[1].y} * 2;
    const int64_t cx = int64_t{p.vertex[2].x} * 2;
    const int64_t cy = int64_t{p.vertex[2].y} * 2;
    int64_t area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    if (area < 0) area = -area;
    out.uv2Extra[2] = area ? 1.0f / static_cast<float>(area) : 0.0f;
  } else if (mode == 2 || mode == 3 || mode == 4) {
    out.uv2Extra[2] = p.width ? 1.0f / static_cast<float>(p.width) : 0.0f;
    out.uv2Extra[3] = p.height ? 1.0f / static_cast<float>(p.height) : 0.0f;
  }
  out.texInfo[0] = (linear || p.linearCoverage) ? 1 : 0;
  out.texInfo[1] = static_cast<int32_t>(p.roundedClipOffset);
  out.texInfo[2] = static_cast<int32_t>(p.roundedClipCount);
  out.texInfo[3] = p.glyphSize ? 2 : (p.linearCoverage ? 1 : 0);
  // Reuse unused screen lanes; UVs remain cell-local, so fixed-point sampling
  // keeps exactly the same float operations as an individual glyph texture.
  out.screen[2] = std::bit_cast<float>(p.glyphOrigin);
  out.screen[3] = std::bit_cast<float>(p.glyphSize);
  out.screen[0] = static_cast<float>(rasterWidth);
  out.screen[1] = static_cast<float>(rasterHeight);
  return out;
}
}
