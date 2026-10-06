#include "gpu_draw_list.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
extern "C" int32_t pod_runtime_texture_for_handle(PodRuntime *, int32_t,
                                                  PodTextureView *);

namespace {
constexpr uint32_t RECT = 1, GRAD_RECT = 2, GLYPH_RUN = 3, TEX_QUAD = 4,
                   SCISSOR = 5, SCISSOR_POP = 6, TRI = 7, TEX_TRI = 8,
                   ROUNDED_CLIP = 11, GLYPH_RUN_XFORM = 12, GLYPH_RUN_F32 = 13, TEX_QUAD_F32 = 14;
constexpr size_t kMaxFlattenedRoundedClips = 65536;
GpuClip intersect(GpuClip a, GpuClip b) {
  return {std::max(a.x0, b.x0), std::max(a.y0, b.y0), std::min(a.x1, b.x1),
          std::min(a.y1, b.y1)};
}
GpuClip bounds(uint32_t xy, uint32_t wh, uint32_t s) {
  int32_t x = int32_t(int16_t(xy & 65535)) * int32_t(s),
          y = int32_t(int16_t(xy >> 16)) * int32_t(s);
  return {x, y, int32_t(x + int32_t(wh & 65535) * int32_t(s)),
          int32_t(y + int32_t(wh >> 16) * int32_t(s))};
}
GpuVertex vertex(uint32_t xy, uint32_t s, uint32_t color = 0) {
  return {(int16_t)(xy & 65535) * int32_t(s), (int16_t)(xy >> 16) * int32_t(s),
          color, 0, 0};
}
bool bad(GpuFrame *o, std::string *e, const char *m) {
  if (e)
    *e = m;
  if (o) {
    o->primitives.clear();
    o->images.clear();
    o->roundedClips.clear();
  }
  return false;
}
bool copyImage(PodRuntime *runtime, uint32_t slot, GpuImage *out,
               std::string *error) {
  PodTextureView v{};
  if (pod_runtime_texture_for_handle(runtime, int32_t(slot), &v) != 0 ||
      !v.pixels)
    return bad(nullptr, error, "texture unavailable");
  out->slot = slot;
  out->width = v.width;
  out->height = v.height;
  out->rasterDensity = 1;
  out->revision = v.revision;
  out->stableKey = (1ull << 63) | uint32_t(v.handle);
  out->linear = v.linear != 0;
  if (!v.width || !v.height || v.width > SIZE_MAX / v.height ||
      size_t(v.width) * v.height > SIZE_MAX / 4)
    return bad(nullptr, error, "invalid texture dimensions");
  size_t count = size_t(v.width) * v.height;
  if ((v.pixel_format == 0 || v.pixel_format == 2) && v.byte_length < count * 2)
    return bad(nullptr, error, "truncated texture");
  if ((v.pixel_format == 3) && v.byte_length < count * 4)
    return bad(nullptr, error, "truncated texture");
  if (v.pixel_format == 5 &&
      (v.byte_length < count || !v.palette || v.palette_length < 1024))
    return bad(nullptr, error, "truncated indexed texture");
  auto pixels = std::make_shared<std::vector<uint8_t>>(count * 4);
  for (size_t n = 0; n < count; n++) {
    uint32_t r = 0, g = 0, b = 0, a = 255;
    if (v.pixel_format == 0) {
      uint16_t q = v.pixels[n * 2] | (uint16_t(v.pixels[n * 2 + 1]) << 8);
      r = ((q & 31) << 3) | ((q & 31) >> 2);
      g = (((q >> 5) & 63) << 2) | (((q >> 5) & 63) >> 4);
      b = (((q >> 11) & 31) << 3) | (((q >> 11) & 31) >> 2);
    } else if (v.pixel_format == 2) {
      uint16_t q = v.pixels[n * 2] | (uint16_t(v.pixels[n * 2 + 1]) << 8);
      r = (q & 15) * 17;
      g = ((q >> 4) & 15) * 17;
      b = ((q >> 8) & 15) * 17;
      a = ((q >> 12) & 15) * 17;
    } else if (v.pixel_format == 3) {
      r = v.pixels[n * 4];
      g = v.pixels[n * 4 + 1];
      b = v.pixels[n * 4 + 2];
      a = v.pixels[n * 4 + 3];
    } else if (v.pixel_format == 5) {
      const uint8_t *p = v.palette + v.pixels[n] * 4;
      r = p[0];
      g = p[1];
      b = p[2];
      a = p[3];
    } else
      return bad(nullptr, error, "unsupported texture format");
    (*pixels)[n * 4 + 0] = r;
    (*pixels)[n * 4 + 1] = g;
    (*pixels)[n * 4 + 2] = b;
    (*pixels)[n * 4 + 3] = a;
  }
  out->rgba = pixels;
  return true;
}
} // namespace

bool gpuRoundedClipContainsPixel(const GpuRoundedClip &c, float x, float y) {
  if (x < c.x0 || x >= c.x1 || y < c.y0 || y >= c.y1)
    return false;
  const float rx = c.radiusX;
  const float ry = c.radiusY;
  if (rx <= 0.0f || ry <= 0.0f)
    return true;
  const float centerX = std::clamp(x, c.x0 + rx, c.x1 - rx);
  const float centerY = std::clamp(y, c.y0 + ry, c.y1 - ry);
  const float dx = (x - centerX) / rx;
  const float dy = (y - centerY) / ry;
  return dx * dx + dy * dy <= 1.0f;
}

void GpuDecodeCache::pruneStale(PodRuntime *runtime) {
  ++frame_;
  pages_.erase(std::remove_if(pages_.begin(),pages_.end(),[&](const Page &p){return pod_runtime_font_epoch(runtime,p.image.slot)!=p.epoch;}),pages_.end());
  entries_.erase(std::remove_if(entries_.begin(), entries_.end(),
                                [runtime](const GpuImage &entry) {
    if ((entry.stableKey & (1ull << 63)) == 0) return false;
    PodTextureView current{};
    const auto handle = static_cast<int32_t>(static_cast<uint32_t>(entry.stableKey));
    return pod_runtime_texture_for_handle(runtime, handle, &current) != 0 ||
           current.revision != entry.revision;
  }), entries_.end());
}

// Pages grow during decoding. All draw commands must refer to the final
// immutable snapshot, including glyphs emitted before a later cell was added.
void GpuDecodeCache::finishFrame(GpuFrame *frame) {
  for(auto &image:frame->images) for(const auto &p:pages_) if(image.stableKey==p.image.stableKey) {image.rgba=p.image.rgba;image.revision=p.image.revision;break;}
  std::vector<GpuImage> unique;
  for(const auto &image:frame->images) if(std::none_of(unique.begin(),unique.end(),[&](const GpuImage &known){return known.stableKey==image.stableKey;})) unique.push_back(image);
  frame->images=std::move(unique);
}

bool GpuDecodeCache::get(PodRuntime *runtime, uint32_t slot, GpuImage *out,
                         std::string *error) {
  PodTextureView v{};
  if (pod_runtime_texture_for_handle(runtime, int32_t(slot), &v) != 0)
    return false;
  uint64_t key = (1ull << 63) | uint32_t(v.handle);
  for (auto &e : entries_)
    if (e.stableKey == key && e.revision == v.revision) {
      *out = e;
      return true;
    }
  GpuImage fresh;
  if (!copyImage(runtime, slot, &fresh, error))
    return false;
  fresh.stableKey = key;
  for (auto &e : entries_)
    if (e.stableKey == key) {
      e = fresh;
      *out = e;
      return true;
    }
  // Bound CPU-side converted resources; active frame references remain alive.
  if (entries_.size() >= 1024) {
    auto unused = std::find_if(
        entries_.begin(), entries_.end(),
        [](const GpuImage &entry) { return entry.rgba.use_count() == 1; });
    if (unused != entries_.end())
      entries_.erase(unused);
  }
  entries_.push_back(fresh);
  *out = std::move(fresh);
  return true;
}
bool GpuDecodeCache::getGlyph(PodRuntime *runtime, uint32_t slot,
                              uint32_t glyph, GpuImage *out,
                              std::string *error) {
  PodFontView f{};
  if (pod_runtime_font(runtime, slot, &f) != 0 || !f.bitmap ||
      glyph >= f.glyph_count)
    return bad(nullptr, error, "glyph unavailable");
  uint32_t density = std::max(1u, f.raster_density),
           width = f.cell_width * density, height = f.cell_height * density;
  const uint64_t epoch=pod_runtime_font_epoch(runtime,slot);
  if(epoch) {
    auto output=[&](Page &p,size_t cell) {
      p.last=++clock_;p.pinned=frame_;*out=p.image;
      const uint32_t x=uint32_t(cell%4)*(width+2)+1,y=uint32_t(cell/4)*(height+2)+1;
      out->glyphOrigin=x|(y<<16);out->glyphSize=width|(height<<16);
    };
    Page *page=nullptr;
    for(auto &p:pages_) if(p.image.slot==slot&&p.epoch==epoch) {
      auto known=std::find(p.glyphs.begin(),p.glyphs.end(),uint16_t(glyph));
      if(known!=p.glyphs.end()) {output(p,size_t(known-p.glyphs.begin()));return true;}
      if(p.glyphs.size()<16) page=&p;
    }
    if(!page) {
      const size_t bytes=size_t(width+2)*4*(height+2)*4*4;
      constexpr size_t budget=16*1024*1024;
      auto used=[&](){size_t n=0;for(const auto &p:pages_) n+=p.image.rgba->size();return n;};
      while(used()+bytes>budget) {
        auto victim=pages_.end();for(auto it=pages_.begin();it!=pages_.end();++it) if(it->pinned!=frame_&&(victim==pages_.end()||it->last<victim->last)) victim=it;
        if(victim==pages_.end()) return bad(nullptr,error,"active glyph pages exceed cache budget");
        pages_.erase(victim);
      }
      Page fresh{};fresh.epoch=epoch;fresh.cellW=width;fresh.cellH=height;
      fresh.image.slot=slot;fresh.image.width=(width+2)*4;fresh.image.height=(height+2)*4;
      fresh.image.rasterDensity=density;fresh.image.coverage=true;fresh.image.stableKey=(1ull<<62)|nextPage_++;
      fresh.image.rgba=std::make_shared<std::vector<uint8_t>>(bytes,0);
      pages_.push_back(std::move(fresh));page=&pages_.back();
    }
    PodGlyphView cell{};
    if(pod_runtime_glyph(runtime,slot,glyph,&cell)!=0||!cell.coverage||cell.byte_length!=size_t(width)*height) return bad(nullptr,error,"runtime glyph unavailable");
    // Copy-on-write keeps the previous submitted frame immutable. Replicated
    // edge texels preserve single-cell clamp/bilinear sampling at UV 0 and 1.
    auto pixels=page->image.rgba.use_count()==1
        ? std::const_pointer_cast<std::vector<uint8_t>>(page->image.rgba)
        : std::make_shared<std::vector<uint8_t>>(*page->image.rgba);
    const size_t index=page->glyphs.size();const uint32_t x=uint32_t(index%4)*(width+2),y=uint32_t(index/4)*(height+2);
    for(uint32_t row=0;row<height+2;++row) for(uint32_t col=0;col<width+2;++col) {
      const uint32_t sx=std::min(width-1,col?col-1:0),sy=std::min(height-1,row?row-1:0);
      const size_t at=(size_t(y+row)*page->image.width+x+col)*4;
      (*pixels)[at]=(*pixels)[at+1]=(*pixels)[at+2]=255;(*pixels)[at+3]=cell.coverage[size_t(sy)*width+sx];
    }
    page->image.rgba=pixels;++page->image.revision;page->glyphs.push_back(uint16_t(glyph));output(*page,index);return true;
  }
  size_t stride = size_t(width), offset = size_t(glyph) * height * stride;
  if (offset + size_t(height) * stride > f.bitmap_length)
    return bad(nullptr, error, "glyph bitmap truncated");
  uint64_t key = (uint64_t(slot) << 32) | glyph;
  for (auto &e : entries_)
    if (e.stableKey == key && e.coverage) {
      *out = e;
      return true;
    }
  GpuImage fresh{};
  fresh.slot = slot;
  fresh.width = width;
  fresh.height = height;
  fresh.rasterDensity = density;
  fresh.coverage = true;
  fresh.stableKey = key;
  fresh.revision = 0;
  auto pixels =
      std::make_shared<std::vector<uint8_t>>(size_t(width) * height * 4);
  for (size_t n = 0; n < size_t(width) * height; ++n) {
    (*pixels)[n * 4] = 255;
    (*pixels)[n * 4 + 1] = 255;
    (*pixels)[n * 4 + 2] = 255;
    (*pixels)[n * 4 + 3] = f.bitmap[offset + n];
  }
  fresh.rgba = pixels;
  // Bound CPU-side converted resources; active frame references remain alive.
  if (entries_.size() >= 1024) {
    auto unused = std::find_if(
        entries_.begin(), entries_.end(),
        [](const GpuImage &entry) { return entry.rgba.use_count() == 1; });
    if (unused != entries_.end())
      entries_.erase(unused);
  }
  entries_.push_back(fresh);
  *out = std::move(fresh);
  return true;
}

bool decodeGpuDrawList(PodRuntime *runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, std::string *error) {
  GpuDecodeCache cache;
  return decodeGpuDrawList(runtime, list, scale, out, &cache, error);
}
bool decodeGpuDrawList(PodRuntime *runtime, const PodDrawList &list,
                       uint32_t scale, GpuFrame *out, GpuDecodeCache *cache,
                       std::string *error) {
  if (!runtime || !out || !list.words || scale == 0 || scale > 4)
    return bad(out, error, "invalid decoder arguments");
  out->primitives.clear();
  out->images.clear();
  out->roundedClips.clear();
  if (cache) cache->pruneStale(runtime);
  GpuClip screen{0, 0, 0x7fffffff, 0x7fffffff};
  struct ClipState {
    GpuClip screen;
    size_t roundedCount;
  };
  std::vector<ClipState> stack;
  std::vector<GpuRoundedClip> rounded;
  auto appendPrimitive = [&](GpuPrimitive &&primitive) {
    if (rounded.size() > kMaxFlattenedRoundedClips - out->roundedClips.size())
      return false;
    primitive.roundedClipOffset =
        static_cast<uint32_t>(out->roundedClips.size());
    primitive.roundedClipCount = static_cast<uint32_t>(rounded.size());
    out->roundedClips.insert(out->roundedClips.end(), rounded.begin(),
                             rounded.end());
    out->primitives.push_back(std::move(primitive));
    return true;
  };
  auto appendImage=[&](GpuImage image) {
    // A page is finalized after every cell in this frame is known. Retaining
    // intermediate snapshots here would copy the whole page per glyph and
    // keep up to sixteen obsolete RGBA buffers alive until the frame ends.
    if(image.glyphSize) image.rgba.reset();
    out->images.push_back(std::move(image));
  };
  const uint32_t *w = list.words;
  size_t i = 0;
  while (i < list.word_count) {
    uint32_t op = w[i++];
    GpuPrimitive p{};
    p.clip = screen;
    if (op == RECT) {
      if (i + 3 > list.word_count)
        return bad(out, error, "truncated RECT");
      p.type = GpuPrimitiveType::Rect;
      p.clip = intersect(screen, bounds(w[i], w[i + 1], scale));
      p.vertex[0] = vertex(w[i], scale, w[i + 2]);
      p.width = (w[i + 1] & 65535) * scale;
      p.height = (w[i + 1] >> 16) * scale;
      i += 3;
      if (!appendPrimitive(std::move(p)))
        return bad(out, error, "rounded clip table capacity exceeded");
    } else if (op == GRAD_RECT) {
      if (i + 5 > list.word_count)
        return bad(out, error, "truncated GRAD_RECT");
      p.type = GpuPrimitiveType::GradientRect;
      p.clip = intersect(screen, bounds(w[i], w[i + 1], scale));
      p.vertex[0] = vertex(w[i], scale, w[i + 2]);
      p.width = (w[i + 1] & 65535) * scale;
      p.height = (w[i + 1] >> 16) * scale;
      p.from = w[i + 2];
      p.to = w[i + 3];
      p.gradientDirection = w[i + 4];
      i += 5;
      if (!appendPrimitive(std::move(p)))
        return bad(out, error, "rounded clip table capacity exceeded");
    } else if (op == TRI) {
      if (i + 6 > list.word_count)
        return bad(out, error, "truncated TRI");
      p.type = GpuPrimitiveType::Tri;
      for (int n = 0; n < 3; n++)
        p.vertex[n] = vertex(w[i + n], scale, w[i + 3 + n]);
      p.clip = intersect(
          screen, {std::min({p.vertex[0].x, p.vertex[1].x, p.vertex[2].x}),
                   std::min({p.vertex[0].y, p.vertex[1].y, p.vertex[2].y}),
                   std::max({p.vertex[0].x, p.vertex[1].x, p.vertex[2].x}),
                   std::max({p.vertex[0].y, p.vertex[1].y, p.vertex[2].y})});
      i += 6;
      if (!appendPrimitive(std::move(p)))
        return bad(out, error, "rounded clip table capacity exceeded");
    } else if (op == TEX_QUAD_F32) {
      if (i + 10 > list.word_count) return bad(out, error, "truncated TEX_QUAD_F32");
      p.textureSlot = w[i];
      float x0=std::bit_cast<float>(w[i+1])*scale, y0=std::bit_cast<float>(w[i+2])*scale;
      float x1=std::bit_cast<float>(w[i+3])*scale, y1=std::bit_cast<float>(w[i+4])*scale;
      float u0=std::bit_cast<float>(w[i+5]), v0=std::bit_cast<float>(w[i+6]);
      float u1=std::bit_cast<float>(w[i+7]), v1=std::bit_cast<float>(w[i+8]);
      for(float value:{x0,y0,x1,y1,u0,v0,u1,v1})
        if(!std::isfinite(value)||std::abs(value)>16777216.0f) return bad(out,error,"invalid TEX_QUAD_F32 coordinate");
      if(x1<=x0||y1<=y0) return bad(out,error,"empty TEX_QUAD_F32");
      const uint32_t color=w[i+9];i+=10;
      // Shader mode 6 evaluates a fractional parallelogram, with ordinary RGBA
      // sampling here (glyphs opt separately into monochrome coverage filtering).
      p.type=GpuPrimitiveType::TexTri;p.subpixelCoverage=true;
      p.vertex[0]={int32_t(x0),int32_t(y0),color,u0,v0};
      p.vertex[1]={int32_t(x1),int32_t(y0),color,u1,v0};
      p.vertex[2]={int32_t(x0),int32_t(y1),color,u0,v1};
      const float points[]={x0,y0,x1,y0,x0,y1};std::copy(points,points+6,p.floatCoordinates);
      p.clip=intersect(screen,{int32_t(std::floor(x0)),int32_t(std::floor(y0)),int32_t(std::ceil(x1)),int32_t(std::ceil(y1))});
      GpuImage image{};if(!cache->get(runtime,p.textureSlot,&image,error))return false;
      p.textureKey=image.stableKey;out->images.push_back(std::move(image));
      if(!appendPrimitive(std::move(p)))return bad(out,error,"rounded clip table capacity exceeded");
    } else if (op == TEX_QUAD) {
      if (i + 8 > list.word_count)
        return bad(out, error, "truncated TEX_QUAD");
      p.type = GpuPrimitiveType::TexQuad;
      p.textureSlot = w[i];
      p.clip = intersect(screen, bounds(w[i + 1], w[i + 2], scale));
      p.vertex[0] = vertex(w[i + 1], scale, w[i + 7]);
      p.vertex[1] = vertex(w[i + 1], scale, w[i + 7]);
      p.width = (w[i + 2] & 65535) * scale;
      p.height = (w[i + 2] >> 16) * scale;
      p.vertex[0].u = __builtin_bit_cast(float, w[i + 3]);
      p.vertex[0].v = __builtin_bit_cast(float, w[i + 4]);
      p.vertex[1].u = __builtin_bit_cast(float, w[i + 5]);
      p.vertex[1].v = __builtin_bit_cast(float, w[i + 6]);
      i += 8;
      GpuImage image{};
      if (!cache->get(runtime, p.textureSlot, &image, error))
        return false;
      p.textureKey = image.stableKey;
      out->images.push_back(std::move(image));
      if (!appendPrimitive(std::move(p)))
        return bad(out, error, "rounded clip table capacity exceeded");
    } else if (op == TEX_TRI) {
      if (i + 11 > list.word_count)
        return bad(out, error, "truncated TEX_TRI");
      p.type = GpuPrimitiveType::TexTri;
      p.textureSlot = w[i];
      for (int n = 0; n < 3; n++) {
        p.vertex[n] = vertex(w[i + 1 + n * 3], scale, w[i + 10]);
        p.vertex[n].u = __builtin_bit_cast(float, w[i + 2 + n * 3]);
        p.vertex[n].v = __builtin_bit_cast(float, w[i + 3 + n * 3]);
      }
      p.clip = intersect(
          screen, {std::min({p.vertex[0].x, p.vertex[1].x, p.vertex[2].x}),
                   std::min({p.vertex[0].y, p.vertex[1].y, p.vertex[2].y}),
                   std::max({p.vertex[0].x, p.vertex[1].x, p.vertex[2].x}),
                   std::max({p.vertex[0].y, p.vertex[1].y, p.vertex[2].y})});
      GpuImage image{};
      if (!cache->get(runtime, p.textureSlot, &image, error))
        return false;
      p.textureKey = image.stableKey;
      out->images.push_back(std::move(image));
      i += 11;
      if (!appendPrimitive(std::move(p)))
        return bad(out, error, "rounded clip table capacity exceeded");
    } else if (op == GLYPH_RUN_XFORM || op == GLYPH_RUN_F32) {
      const bool precise = op == GLYPH_RUN_F32;
      if (i + (precise ? 6 : 4) > list.word_count)
        return bad(out, error, "truncated GLYPH_RUN_XFORM");
      uint32_t packed = w[i++], n = packed >> 16;
      uint32_t slot = packed & 255, color = w[i++];
      struct FloatVertex { float x, y; uint32_t color; float u, v; };
      auto readVertex = [&](size_t offset, uint32_t tint = 0) {
        if (!precise) { auto v = vertex(w[offset], scale, tint);
          return FloatVertex{float(v.x), float(v.y), tint, 0, 0}; }
        return FloatVertex{std::bit_cast<float>(w[offset]) * scale,
                         std::bit_cast<float>(w[offset+1]) * scale, tint, 0, 0};
      };
      const FloatVertex axisX = readVertex(i); i += precise ? 2 : 1;
      const FloatVertex axisY = readVertex(i); i += precise ? 2 : 1;
      const size_t stride = precise ? 3 : 2;
      if (!std::isfinite(axisX.x) || !std::isfinite(axisX.y) ||
          !std::isfinite(axisY.x) || !std::isfinite(axisY.y))
        return bad(out, error, "nonfinite glyph axes");
      if (i + stride * n > list.word_count)
        return bad(out, error, "truncated GLYPH_RUN_XFORM glyphs");
      for (uint32_t g = 0; g < n; g++) {
        const uint32_t gid = w[i + g * stride + stride - 1] & 65535;
        GpuImage image{};
        if (!cache->getGlyph(runtime, slot, gid, &image, error))
          return false;
        const FloatVertex anchor = readVertex(i + g * stride, color);
        if (!std::isfinite(anchor.x) || !std::isfinite(anchor.y))
          return bad(out, error, "nonfinite glyph anchor");
        FloatVertex corner[4] = {
            anchor,
            {anchor.x + axisX.x, anchor.y + axisX.y, color, 1.0f, 0.0f},
            {anchor.x + axisX.x + axisY.x, anchor.y + axisX.y + axisY.y, color,
             1.0f, 1.0f},
            {anchor.x + axisY.x, anchor.y + axisY.y, color, 0.0f, 1.0f},
        };
        corner[0].u = 0.0f;
        corner[0].v = 0.0f;
        auto appendGlyphTriangle = [&](int a, int b, int c) {
          GpuPrimitive tri{};
          tri.type = GpuPrimitiveType::TexTri;
          tri.textureSlot = slot;
          tri.glyphSlot = slot;
          tri.glyphOrigin=image.glyphOrigin;tri.glyphSize=image.glyphSize;
          tri.linearCoverage = true;
          tri.textureKey = image.stableKey;
          const FloatVertex chosen[3] = {corner[a], corner[b], corner[c]};
          tri.subpixelCoverage = precise;
          for (int j = 0; j < 3; ++j) {
            if (!std::isfinite(chosen[j].x) || !std::isfinite(chosen[j].y) ||
                std::abs(chosen[j].x) > 16777216 || std::abs(chosen[j].y) > 16777216) return false;
            tri.vertex[j] = {int32_t(chosen[j].x), int32_t(chosen[j].y),
                             chosen[j].color, chosen[j].u, chosen[j].v};
            tri.floatCoordinates[j*2] = chosen[j].x;
            tri.floatCoordinates[j*2+1] = chosen[j].y;
          }
          tri.clip = intersect(
              screen,
              {int32_t(std::floor(std::min({chosen[0].x, chosen[1].x, chosen[2].x}))),
               int32_t(std::floor(std::min({chosen[0].y, chosen[1].y, chosen[2].y}))),
               int32_t(std::ceil(std::max({chosen[0].x, chosen[1].x, chosen[2].x}))),
               int32_t(std::ceil(std::max({chosen[0].y, chosen[1].y, chosen[2].y})))});
          if (precise) {
            tri.clip = intersect(screen, {
              int32_t(std::floor(std::min({corner[0].x, corner[1].x, corner[2].x, corner[3].x}))),
              int32_t(std::floor(std::min({corner[0].y, corner[1].y, corner[2].y, corner[3].y}))),
              int32_t(std::ceil(std::max({corner[0].x, corner[1].x, corner[2].x, corner[3].x}))),
              int32_t(std::ceil(std::max({corner[0].y, corner[1].y, corner[2].y, corner[3].y})))});
          }
          return appendPrimitive(std::move(tri));
        };
        appendImage(std::move(image));
        if (precise ? !appendGlyphTriangle(0, 1, 3)
                    : (!appendGlyphTriangle(0, 1, 2) || !appendGlyphTriangle(0, 2, 3)))
          return bad(out, error, "rounded clip table capacity exceeded");
      }
      i += stride * n;
    } else if (op == GLYPH_RUN) {
      if (i + 2 > list.word_count)
        return bad(out, error, "truncated GLYPH_RUN");
      uint32_t packed = w[i++], n = packed >> 16;
      uint32_t slot = packed & 255, color = w[i++];
      PodFontView font{};
      if (pod_runtime_font(runtime, slot, &font) != 0)
        return bad(out, error, "font unavailable");
      if (i + 2 * n > list.word_count)
        return bad(out, error, "truncated GLYPH_RUN glyphs");
      for (uint32_t g = 0; g < n; g++) {
        uint32_t gid = w[i + g * 2 + 1] & 65535;
        GpuImage image{};
        if (!cache->getGlyph(runtime, slot, gid, &image, error))
          return false;
        GpuPrimitive q{};
        q.type = GpuPrimitiveType::GlyphRun;
        q.glyphSlot = slot;
        q.glyphOrigin=image.glyphOrigin;q.glyphSize=image.glyphSize;
        q.textureSlot = slot;
        q.textureKey = image.stableKey;
        q.vertex[0] = vertex(w[i + g * 2], scale, color);
        q.width = font.cell_width * scale;
        q.height = font.cell_height * scale;
        q.clip = intersect(screen, {q.vertex[0].x, q.vertex[0].y,
                                    q.vertex[0].x + int32_t(q.width),
                                    q.vertex[0].y + int32_t(q.height)});
        q.vertex[0].u = 0;
        q.vertex[0].v = 0;
        q.vertex[1].u = 1;
        q.vertex[1].v = 1;
        appendImage(std::move(image));
        if (!appendPrimitive(std::move(q)))
          return bad(out, error, "rounded clip table capacity exceeded");
      }
      i += 2 * n;
    } else if (op == SCISSOR) {
      if (i + 2 > list.word_count)
        return bad(out, error, "truncated SCISSOR");
      stack.push_back({screen, rounded.size()});
      screen = intersect(screen, bounds(w[i], w[i + 1], scale));
      i += 2;
    } else if (op == ROUNDED_CLIP) {
      if (i + 4 > list.word_count)
        return bad(out, error, "truncated ROUNDED_CLIP");
      const float radiusX = __builtin_bit_cast(float, w[i + 2]);
      const float radiusY = __builtin_bit_cast(float, w[i + 3]);
      if (!std::isfinite(radiusX) || !std::isfinite(radiusY) ||
          radiusX < 0.0f || radiusY < 0.0f)
        return bad(out, error, "invalid ROUNDED_CLIP radius");
      const GpuClip original = bounds(w[i], w[i + 1], scale);
      stack.push_back({screen, rounded.size()});
      rounded.push_back(
          {static_cast<float>(original.x0), static_cast<float>(original.y0),
           static_cast<float>(original.x1), static_cast<float>(original.y1),
           radiusX * scale, radiusY * scale});
      screen = intersect(screen, original);
      i += 4;
    } else if (op == SCISSOR_POP) {
      if (stack.empty())
        return bad(out, error, "unbalanced SCISSOR_POP");
      screen = stack.back().screen;
      rounded.resize(stack.back().roundedCount);
      stack.pop_back();
    } else
      return bad(out, error, "unsupported or unknown DrawList opcode");
  }
  if (!stack.empty())
    return bad(out, error, "unbalanced SCISSOR");
  cache->finishFrame(out);
  return true;
}
