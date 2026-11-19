//! Immutable handoff from the guest lane to an independent renderer.
use super::*;
use pocketjs_core::{RasterResources, RenderResources, Ui};
use std::sync::Arc;

pub(super) struct CommittedDraw {
    pub(super) words: Arc<Vec<u32>>,
    pub(super) hash: u64,
    words_hash: u64,
    referenced_textures: Option<Vec<i32>>,
}

impl CommittedDraw {
    pub(super) fn new() -> Self {
        Self {
            words: Arc::default(),
            hash: 0,
            words_hash: FNV_OFFSET,
            // Empty lists use the whole texture registry.
            referenced_textures: None,
        }
    }

    pub(super) fn commit(&mut self, ui: &mut Ui) -> bool {
        // draw() walks the tree and commits accessibility and inspector state.
        // Hash and hand off that exact output, including on a paint-only change.
        let words = &ui.draw().words;
        if words != self.words.as_ref() {
            self.words_hash = hash_words(words);
            self.referenced_textures = draw_texture_refs::referenced(words);
            if let Some(storage) = Arc::get_mut(&mut self.words) {
                storage.clone_from(words);
            } else {
                // A rendering thread still owns the previous commands.
                self.words = Arc::new(words.clone());
            }
        }

        let mut hash = self.words_hash;
        let mut add = |value: u64| { hash = (hash ^ value).wrapping_mul(0x100000001b3) };
        // Font replacement and in-place video updates can change visible pixels
        // without changing any command. Read their current versions every time.
        for (slot, version) in ui.font_resource_versions().into_iter().enumerate() {
            if version != 0 {
                add(slot as u64);
                add(version);
            }
        }
        if let Some(handles) = &self.referenced_textures {
            for &handle in handles {
                add(handle as u32 as u64);
                if let Some(revision) = ui.texture_revision(handle) {
                    add(1);
                    add(revision);
                } else {
                    add(0);
                }
            }
        } else {
            // Auxiliary surfaces and unknown opcodes can have hidden dependencies.
            for slot in 0..ui.texture_slot_count() as u32 {
                if let Some((handle, revision, _)) = ui.texture_at_versioned(slot) {
                    add(handle as u32 as u64);
                    add(revision);
                }
            }
        }
        let changed = hash != self.hash;
        self.hash = hash;
        changed
    }
}

pub struct PodRenderSnapshot {
    _texture_memory:Vec<std::sync::Arc<memory_budget::Lease>>,
    resources: RenderResources,
    words: Arc<Vec<u32>>,
    hash: u64,
    frame: u64,
    last_glyph: Option<std::sync::Arc<[u8]>>,
}

/// Capture after pod_runtime_snapshot, on the guest lane. No guest/runtime
/// pointer escapes. Draw words and resource revisions remain pinned together.
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
        _texture_memory:runtime.bridge.borrow().texture_memory.values().cloned().collect(),
        resources,
        words: Arc::clone(&runtime.draw.words),
        hash: runtime.draw.hash,
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
        glyph_count: f.available_glyph_count(),
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
    if gid >= a.available_glyph_count() {
        return IDLE;
    }
    let cell = if let Some(color)=a.glyph_color(gid as u16) {color} else {match a.glyph_coverage(gid as u16) {
        pocketjs_core::text::GlyphRows::Shared(v) => v,
        pocketjs_core::text::GlyphRows::Borrowed(v) => std::sync::Arc::from(v),
    }};
    *out = PodGlyphView {
        coverage: cell.as_ptr(),
        byte_length: cell.len(),
    };
    s.last_glyph = Some(cell);
    OK
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_render_snapshot_glyph_image(
    snapshot:*mut PodRenderSnapshot,slot:u32,gid:u32,out:*mut PodGlyphImageView,
)->i32 {
    if out.is_null() {return ERR_ARGUMENT;}
    let mut data=PodGlyphView{coverage:std::ptr::null(),byte_length:0};
    let result=unsafe {pod_render_snapshot_glyph(snapshot,slot,gid,&mut data)};
    if result!=OK {return result;}
    let s=unsafe {&*snapshot};let a=s.resources.font_atlas(slot as u8).unwrap();
    let (w,h)=a.glyph_size(gid as u16);
    unsafe {*out=PodGlyphImageView{data,width:w*u32::from(a.raster_density),height:h*u32::from(a.raster_density)};}OK
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
