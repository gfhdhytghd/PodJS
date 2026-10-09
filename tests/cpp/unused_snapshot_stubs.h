#pragma once
// Legacy decoder unit fixtures never use the snapshot entry points.
#include "podjs_runtime.h"
#include <cstdlib>
extern "C" int32_t pod_render_snapshot_texture(const PodRenderSnapshot*,int32_t,PodTextureView*) { std::abort(); }
extern "C" int32_t pod_render_snapshot_font(const PodRenderSnapshot*,uint32_t,PodFontView*) { std::abort(); }
extern "C" uint64_t pod_render_snapshot_font_epoch(const PodRenderSnapshot*,uint32_t) { std::abort(); }
extern "C" int32_t pod_render_snapshot_glyph(PodRenderSnapshot*,uint32_t,uint32_t,PodGlyphView*) { std::abort(); }

extern "C" int32_t pod_render_snapshot_glyph_image(PodRenderSnapshot*,uint32_t,uint32_t,PodGlyphImageView*) { std::abort(); }
extern "C" int32_t pod_runtime_glyph_image(PodRuntime* r,uint32_t s,uint32_t g,PodGlyphImageView* out) {
  PodFontView font{};int result=pod_runtime_font(r,s,&font);if(result)return result;
  result=pod_runtime_glyph(r,s,g,&out->data);out->width=font.cell_width*font.raster_density;out->height=font.cell_height*font.raster_density;return result;
}
