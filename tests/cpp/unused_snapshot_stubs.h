#pragma once
// Legacy decoder unit fixtures never use the snapshot entry points.
#include "podjs_runtime.h"
#include <cstdlib>
extern "C" int32_t pod_render_snapshot_texture(const PodRenderSnapshot*,int32_t,PodTextureView*) { std::abort(); }
extern "C" int32_t pod_render_snapshot_font(const PodRenderSnapshot*,uint32_t,PodFontView*) { std::abort(); }
extern "C" uint64_t pod_render_snapshot_font_epoch(const PodRenderSnapshot*,uint32_t) { std::abort(); }
extern "C" int32_t pod_render_snapshot_glyph(PodRenderSnapshot*,uint32_t,uint32_t,PodGlyphView*) { std::abort(); }
