#include "gpu_draw_list.h"
#include "vulkan_gpu.h"
#include <bit>
#include <cmath>
#include <cassert>
#include <limits>
#include <string>
#include <vector>
static uint64_t revision = 3;
static bool truncated = false;
static uint8_t linearSampling = 0;
static bool textureAvailable = true;
extern "C" int32_t pod_runtime_texture_for_handle(PodRuntime *, int32_t handle,
                                                  PodTextureView *out) {
  static const uint8_t px[] = {0, 1};
  static const uint8_t pal[1024] = {0, 0, 255, 255, 0, 255, 0, 128};
  if (handle != 0x10007 || !textureAvailable)
    return 1;
  *out = {handle, revision, px,  truncated ? 1u : 2u, 2,
          1,      5,        pal, sizeof(pal),         linearSampling};
  return 0;
}
extern "C" int32_t pod_runtime_font(PodRuntime *, uint32_t slot,
                                    PodFontView *out) {
  // Two logical 1x1 cells, each a 2x2 coverage bitmap.
  static const uint8_t bits[] = {64, 128, 255, 32, 10, 20, 30, 40};
  if (slot != 2)
    return 1;
  *out = {2, 1, 1, 1, 1, 2, 2, bits, sizeof(bits)};
  return 0;
}
static uint32_t xy(int x, int y) {
  return uint16_t(x) | (uint32_t(uint16_t(y)) << 16);
}
static uint32_t f32(float value) { return std::bit_cast<uint32_t>(value); }
int main() {
  auto *runtime = reinterpret_cast<PodRuntime *>(1);
  std::string error;
  GpuFrame frame;
  GpuDecodeCache cache;
  auto decode = [&](const std::vector<uint32_t> &words, uint32_t scale = 2) {
    return decodeGpuDrawList(runtime, {words.data(), words.size(), 0, 0, 0},
                             scale, &frame, &cache, &error);
  };
  const std::vector<uint32_t> fractionalImage={14,0x10007,f32(10.5f),f32(14.25f),f32(18.5f),f32(22.25f),f32(0),f32(0),f32(1),f32(1),0x80ffffff};
  assert(decode(fractionalImage));assert(frame.primitives.size()==1&&frame.images.size()==1);
  const auto& fq=frame.primitives[0];assert(fq.subpixelCoverage&&!fq.linearCoverage);
  assert(fq.floatCoordinates[0]==21&&fq.floatCoordinates[1]==28.5f&&fq.floatCoordinates[2]==37&&fq.floatCoordinates[5]==44.5f);
  assert(fq.clip.x0==21&&fq.clip.y0==28&&fq.clip.x1==37&&fq.clip.y1==45);
  auto push=podjs_gpu::makePrimitivePush(fq,466,466,false);assert(push.xy2Mode[2]==6&&push.texInfo[3]==0&&std::bit_cast<float>(push.xy01[1])==28.5f);
  for(size_t n=1;n<fractionalImage.size();n++){std::vector<uint32_t> cut(fractionalImage.begin(),fractionalImage.begin()+n);assert(!decode(cut));}
  for(size_t index:{2u,3u,4u,5u,6u,7u,8u,9u}){auto bad=fractionalImage;bad[index]=f32(std::numeric_limits<float>::quiet_NaN());assert(!decode(bad));}
  auto emptyImage=fractionalImage;emptyImage[4]=emptyImage[2];assert(!decode(emptyImage));
  assert(decode({5, xy(4, 5), xy(2, 3), 1, xy(3, 4), xy(5, 6), 0xff112233, 6}));
  assert(frame.primitives.size() == 1 && frame.primitives[0].width == 10 &&
         frame.primitives[0].height == 12);
  assert(
      frame.primitives[0].clip.x0 == 8 && frame.primitives[0].clip.y0 == 10 &&
      frame.primitives[0].clip.x1 == 12 && frame.primitives[0].clip.y1 == 16);
  assert(decode({3, (2u << 16) | 2, 0xffabcdef, xy(1, 2), 0, xy(3, 4), 1}, 1));
  assert(frame.primitives.size() == 2 && frame.images.size() == 2);
  assert(frame.primitives[0].width == 1 &&
         frame.primitives[1].vertex[0].x == 3);
  assert(frame.images[0].width == 2 && (*frame.images[0].rgba)[3] == 64 &&
         (*frame.images[1].rgba)[3] == 10);
  // Extended transformed runs carry cell basis vectors, so each glyph lowers
  // to two textured triangles with both its position and bitmap rotated.
  assert(decode({12, (2u << 16) | 2, 0x80abcdef, xy(0, 2), xy(-4, 0), xy(10, 5),
                 0, xy(10, 8), 1},
                1));
  assert(frame.primitives.size() == 4 && frame.images.size() == 2);
  const auto &firstGlyphA = frame.primitives[0];
  const auto &firstGlyphB = frame.primitives[1];
  const auto filtered = podjs_gpu::makePrimitivePush(firstGlyphA, 466, 466, false);
  assert(filtered.texInfo[0] == 1 && filtered.texInfo[3] == 1);
  assert(firstGlyphA.type == GpuPrimitiveType::TexTri &&
         firstGlyphA.vertex[0].x == 10 && firstGlyphA.vertex[0].y == 5 &&
         firstGlyphA.vertex[1].x == 10 && firstGlyphA.vertex[1].y == 7 &&
         firstGlyphA.vertex[2].x == 6 && firstGlyphA.vertex[2].y == 7);
  assert(firstGlyphA.vertex[0].u == 0 && firstGlyphA.vertex[0].v == 0 &&
         firstGlyphA.vertex[1].u == 1 && firstGlyphA.vertex[1].v == 0 &&
         firstGlyphA.vertex[2].u == 1 && firstGlyphA.vertex[2].v == 1);
  assert(firstGlyphB.vertex[0].x == 10 && firstGlyphB.vertex[0].y == 5 &&
         firstGlyphB.vertex[1].x == 6 && firstGlyphB.vertex[1].y == 7 &&
         firstGlyphB.vertex[2].x == 6 && firstGlyphB.vertex[2].y == 5 &&
         firstGlyphB.vertex[2].u == 0 && firstGlyphB.vertex[2].v == 1);
  assert(firstGlyphA.vertex[0].color == 0x80abcdef &&
         frame.primitives[2].vertex[0].y == 8 &&
         frame.primitives[0].textureKey != frame.primitives[2].textureKey);
  auto glyphPixels = frame.images[0].rgba;
  assert(decode({3, (1u << 16) | 2, 0xffffffff, xy(1, 2), 0}, 2));
  assert(frame.primitives[0].width == 2 && frame.images[0].rgba == glyphPixels);
  const auto unscaled = podjs_gpu::makePrimitivePush(frame.primitives[0], 466, 466, false);
  assert(unscaled.texInfo[0] == 0 && unscaled.texInfo[3] == 0);
  // Half logical pixels must survive both decoding and Vulkan push packing.
  assert(decode({13, (1u << 16) | 2, 0xffffffff,
                 f32(1.25f), f32(0), f32(0), f32(1.5f),
                 f32(10.5f), f32(14.5f), 0}));
  assert(frame.primitives.size() == 1);
  const auto precise = podjs_gpu::makePrimitivePush(frame.primitives[0], 466, 466, false);
  assert(precise.xy2Mode[2] == 6);
  assert(std::bit_cast<float>(precise.xy01[0]) == 21.0f);
  assert(std::bit_cast<float>(precise.xy01[1]) == 29.0f);
  assert(std::bit_cast<float>(precise.xy01[2]) == 23.5f);
  assert(std::bit_cast<float>(precise.xy2Mode[1]) == 32.0f);
  assert(frame.primitives[0].clip.x1 == 24);
  assert(!decode({13, (1u << 16) | 2, 0xffffffff, f32(1), f32(0), f32(0), f32(1)}));
  assert(!decode({13, (1u << 16) | 2, 0xffffffff,
                 f32(1), f32(0), f32(0), f32(1), f32(NAN), f32(0), 0}));
  const std::vector<uint32_t> tex = {4, 0x10007,    xy(0, 0),   xy(2, 1),  0,
                                     0, 0x3f800000, 0x3f800000, 0xffffffff};
  assert(decode(tex));
  assert(frame.primitives[0].textureKey == ((1ull << 63) | 0x10007));
  assert(!frame.images[0].linear);
  const auto nearestImage = podjs_gpu::makePrimitivePush(frame.primitives[0], 466, 466, frame.images[0].linear);
  assert(nearestImage.texInfo[0] == 0 && nearestImage.texInfo[3] == 0);
  assert((*frame.images[0].rgba)[2] == 255 &&
         (*frame.images[0].rgba)[7] == 128);
  auto first = frame.images[0].rgba;
  assert(decode(tex) && frame.images[0].rgba == first);
  revision = 4;
  linearSampling = 1;
  assert(decode(tex) && frame.images[0].rgba != first);
  assert(frame.images[0].revision == 4);
  assert(frame.images[0].linear);
  const auto linearImage = podjs_gpu::makePrimitivePush(frame.primitives[0], 466, 466, frame.images[0].linear);
  assert(linearImage.texInfo[0] == 1 && linearImage.texInfo[3] == 0);
  assert(decode(fractionalImage) && frame.images[0].linear);
  const auto scaledLinearImage = podjs_gpu::makePrimitivePush(frame.primitives[0], 466, 466, frame.images[0].linear);
  assert(scaledLinearImage.xy2Mode[2] == 6 && scaledLinearImage.texInfo[0] == 1 && scaledLinearImage.texInfo[3] == 0);
  const std::vector<uint32_t> tri = {
      8,          0x10007, xy(0, 0), 0, 0,          xy(2, 0),
      0x3f800000, 0,       xy(0, 2), 0, 0x3f800000, 0xffffffff};
  assert(decode(tri));
  assert(frame.images.size() == 1 &&
         frame.primitives[0].textureKey == ((1ull << 63) | 0x10007));
  assert(frame.primitives[0].clip.x1 == 4 && frame.primitives[0].clip.y1 == 4);
  revision = 5;
  truncated = true;
  assert(!decode(tex));
  truncated = false;
  assert(!decode({8, 0x10007}));
  assert(!decode({3, (1u << 16) | 2, 0xffffffff, xy(0, 0)}));
  assert(
      !decode({12, (1u << 16) | 2, 0xffffffff, xy(0, 1), xy(-1, 0), xy(0, 0)}));
  assert(!decode({9}) && error.find("unsupported") != std::string::npos);
  assert(!decode({10}));
  assert(!decode({6}));

  // Rounded bounds retain their original offscreen world geometry while the
  // hardware scissor uses the ancestor intersection. Every primitive records
  // the complete active rounded chain, including glyphs and textures.
  const std::vector<uint32_t> rounded = {5,
                                         xy(0, 0),
                                         xy(20, 20),
                                         11,
                                         xy(-5, -3),
                                         xy(20, 14),
                                         f32(8.0f),
                                         f32(4.0f),
                                         1,
                                         xy(-8, -8),
                                         xy(30, 30),
                                         0xffffffff,
                                         11,
                                         xy(2, 1),
                                         xy(8, 10),
                                         f32(3.0f),
                                         f32(5.0f),
                                         3,
                                         (1u << 16) | 2,
                                         0xffffffff,
                                         xy(3, 4),
                                         0,
                                         4,
                                         0x10007,
                                         xy(1, 1),
                                         xy(2, 1),
                                         0,
                                         0,
                                         f32(1),
                                         f32(1),
                                         0xffffffff,
                                         6,
                                         1,
                                         xy(0, 0),
                                         xy(20, 20),
                                         0xffffffff,
                                         6,
                                         1,
                                         xy(0, 0),
                                         xy(20, 20),
                                         0xffffffff,
                                         6};
  assert(decode(rounded, 2));
  assert(frame.primitives.size() == 5);
  assert(frame.primitives[0].roundedClipCount == 1);
  assert(frame.primitives[1].type == GpuPrimitiveType::GlyphRun &&
         frame.primitives[1].roundedClipCount == 2);
  assert(frame.primitives[2].type == GpuPrimitiveType::TexQuad &&
         frame.primitives[2].roundedClipCount == 2);
  assert(frame.primitives[3].roundedClipCount == 1);
  assert(frame.primitives[4].roundedClipCount == 0);
  assert(frame.primitives[0].clip.x0 == 0 && frame.primitives[0].clip.y0 == 0 &&
         frame.primitives[0].clip.x1 == 30 &&
         frame.primitives[0].clip.y1 == 22);
  const GpuRoundedClip outer = frame.roundedClips[0];
  assert(outer.x0 == -10 && outer.y0 == -6 && outer.x1 == 30 &&
         outer.y1 == 22 && outer.radiusX == 16 && outer.radiusY == 8);

  // These are physical pixel centers and match the shader's hard elliptical
  // corner equation. The horizontal and vertical radii are intentionally
  // unequal.
  const GpuRoundedClip ellipse{0, 0, 20, 12, 6, 3};
  assert(!gpuRoundedClipContainsPixel(ellipse, 0.5f, 0.5f));
  assert(gpuRoundedClipContainsPixel(ellipse, 5.5f, 0.5f));
  assert(gpuRoundedClipContainsPixel(ellipse, 10.5f, 0.5f));
  assert(!gpuRoundedClipContainsPixel(ellipse, 19.5f, 11.5f));
  assert(!gpuRoundedClipContainsPixel(ellipse, 20.5f, 6.5f));
  assert(!decode({11, xy(0, 0), xy(10, 10),
                  f32(std::numeric_limits<float>::quiet_NaN()), f32(2)}));
  assert(!decode({11, xy(0, 0), xy(10, 10), f32(-1), f32(2)}));
  assert(!decode({11, xy(0, 0), xy(10, 10), f32(2)}));

  // Releasing a texture must also release the decoded RGBA copy on the next
  // frame, even when no image is drawn in that frame.
  GpuDecodeCache releaseCache;
  GpuFrame releaseFrame;
  assert(decodeGpuDrawList(runtime, {tex.data(), tex.size(), 0, 0, 0}, 1,
                           &releaseFrame, &releaseCache, &error));
  std::weak_ptr<const std::vector<uint8_t>> releasedPixels =
      releaseFrame.images[0].rgba;
  textureAvailable = false;
  const std::vector<uint32_t> rect = {1, xy(0, 0), xy(1, 1), 0xffffffff};
  assert(decodeGpuDrawList(runtime, {rect.data(), rect.size(), 0, 0, 0}, 1,
                           &releaseFrame, &releaseCache, &error));
  assert(releasedPixels.expired());
}
