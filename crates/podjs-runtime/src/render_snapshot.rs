//! Immutable handoff from the guest lane to an independent renderer.
use super::*;
use pocketjs_core::{RasterResources, RenderResources};

pub struct PodRenderSnapshot {
    resources: RenderResources,
    words: Vec<u32>,
    hash: u64,
    frame: u64,
    last_glyph: Option<std::sync::Arc<[u8]>>,
}

/// Capture after pod_runtime_snapshot, on the guest lane. No guest/runtime
/// pointer escapes. Draw words are copied; pixels/fonts are revision-pinned.
/// Caller-owned mapped PAK storage must remain valid until every snapshot dies.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_pin_snapshot(runtime: *mut PodRuntime) -> *mut PodRenderSnapshot {
    let Ok(runtime) = runtime_mut(runtime) else {
        return std::ptr::null_mut();
    };
    if !runtime.mounted {
        return std::ptr::null_mut();
    }
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<RenderResources>();
    let resources = runtime.surface.with_ui(|ui| ui.render_resources());
    Box::into_raw(Box::new(PodRenderSnapshot {
        resources,
        words: runtime.draw_words.clone(),
        hash: runtime.draw_hash,
        frame: runtime.frame_number,
        last_glyph: None,
    }))
}

/// Destroy on any lane, only after the snapshot's last reader finishes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_destroy(snapshot: *mut PodRenderSnapshot) {
    if !snapshot.is_null() {
        drop(unsafe { Box::from_raw(snapshot) });
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_draw_list(
    snapshot: *const PodRenderSnapshot,
    out: *mut PodDrawList,
) -> i32 {
    let Some(s) = (unsafe { snapshot.as_ref() }) else {
        return ERR_ARGUMENT;
    };
    let Some(out) = (unsafe { out.as_mut() }) else {
        return ERR_ARGUMENT;
    };
    *out = PodDrawList {
        words: s.words.as_ptr(),
        word_count: s.words.len(),
        content_hash: s.hash,
        frame_number: s.frame,
        changed: 1,
    };
    OK
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_texture(
    snapshot: *const PodRenderSnapshot,
    handle: i32,
    out: *mut PodTextureView,
) -> i32 {
    let Some(s) = (unsafe { snapshot.as_ref() }) else {
        return ERR_ARGUMENT;
    };
    let Some(out) = (unsafe { out.as_mut() }) else {
        return ERR_ARGUMENT;
    };
    let Some(v) = s.resources.texture(handle) else {
        return IDLE;
    };
    let palette = v.palette.unwrap_or(&[]);
    *out = PodTextureView {
        handle,
        revision: s.resources.texture_revision(handle).unwrap_or(0),
        pixels: v.pixels.as_ptr(),
        byte_length: v.pixels.len(),
        width: v.w,
        height: v.h,
        pixel_format: v.psm,
        palette: palette.as_ptr(),
        palette_length: palette.len(),
        linear: i32::from(v.linear),
    };
    OK
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_font(
    snapshot: *const PodRenderSnapshot,
    slot: u32,
    out: *mut PodFontView,
) -> i32 {
    let Some(s) = (unsafe { snapshot.as_ref() }) else {
        return ERR_ARGUMENT;
    };
    let Some(out) = (unsafe { out.as_mut() }) else {
        return ERR_ARGUMENT;
    };
    if slot > 255 {
        return ERR_ARGUMENT;
    }
    let Some(f) = s.resources.font_atlas(slot as u8) else {
        return IDLE;
    };
    *out = PodFontView {
        slot,
        cell_width: f.cell_w,
        cell_height: f.cell_h,
        baseline: f.baseline,
        line_height: f.line_height,
        raster_density: f.raster_density as u32,
        glyph_count: f.glyph_count as u32,
        bitmap: f.bitmap.as_ptr(),
        bitmap_length: f.bitmap.len(),
    };
    OK
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_font_epoch(
    snapshot: *const PodRenderSnapshot,
    slot: u32,
) -> u64 {
    let Some(s) = (unsafe { snapshot.as_ref() }) else {
        return 0;
    };
    if slot > 255 {
        return 0;
    }
    s.resources.font_epoch(slot as u8)
}
/// Renderer-local scratch pins the returned coverage until its next glyph call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_glyph(
    snapshot: *mut PodRenderSnapshot,
    slot: u32,
    gid: u32,
    out: *mut PodGlyphView,
) -> i32 {
    let Some(s) = (unsafe { snapshot.as_mut() }) else {
        return ERR_ARGUMENT;
    };
    let Some(out) = (unsafe { out.as_mut() }) else {
        return ERR_ARGUMENT;
    };
    if slot > 255 || gid > 65535 {
        return ERR_ARGUMENT;
    }
    let Some(a) = s.resources.font_atlas(slot as u8) else {
        return IDLE;
    };
    if gid >= u32::from(a.glyph_count) {
        return IDLE;
    }
    let cell = match a.glyph_coverage(gid as u16) {
        pocketjs_core::text::GlyphRows::Shared(v) => v,
        pocketjs_core::text::GlyphRows::Borrowed(v) => std::sync::Arc::from(v),
    };
    *out = PodGlyphView {
        coverage: cell.as_ptr(),
        byte_length: cell.len(),
    };
    s.last_glyph = Some(cell);
    OK
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_rgba(
    snapshot: *const PodRenderSnapshot,
    scale: u32,
    pixels: *mut u8,
    length: usize,
) -> i32 {
    let Some(s) = (unsafe { snapshot.as_ref() }) else {
        return ERR_ARGUMENT;
    };
    if pixels.is_null() || !(1..=4).contains(&scale) {
        return ERR_ARGUMENT;
    }
    let (w, h) = s.resources.viewport();
    let expected = (w as usize)
        .checked_mul(h as usize)
        .and_then(|n| n.checked_mul((scale * scale * 4) as usize));
    if expected != Some(length) {
        return ERR_ARGUMENT;
    }
    pocketjs_core::raster::render_snapshot_transparent(
        &s.resources,
        &s.words,
        unsafe { slice::from_raw_parts_mut(pixels, length) },
        scale,
    );
    OK
}
