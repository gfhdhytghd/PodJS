//! PodJS portable watch runtime.
//!
//! This crate deliberately owns no window or graphics API. It hosts the
//! PocketJS guest and UI surface, emits a stable DrawList/resource snapshot,
//! and exposes frame-boundary platform facts through a small append-only C ABI.

use std::cell::RefCell;
pub mod sync_files;
pub mod sync_file_ffi;
pub mod sync_file_source;
pub mod sync_cancellation;
pub mod guest_io;
pub mod guest_publish;
pub mod sync_auth;
pub mod sync_session_ffi;
pub mod sync_state_ffi;
pub mod sync_message_ffi;
pub mod sync_file_wire;
pub mod background;
pub mod background_ffi;
pub mod kv;
pub mod accessibility_ffi;
mod pak_reader;
mod pak_integrity;
pub use pak_reader::PodPakReadAt;
use kv::KvStore;
use std::collections::{BTreeMap, VecDeque};
use std::ffi::{CStr, CString, c_char};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::slice;
use std::time::Instant;

use anyhow::Context as _;
use base64::Engine as _;
use pocket_fs::{FsModule, Storage};
use pocket_mod::Guest;
use pocket_mod::qjs::{Function, Ctx, TypedArray, CatchResultExt};
use pocket_net::{HttpRequest, HttpTransport, NetFailure, NetSurface, TransportCompletion};
use pocket_ui_surface::UiSurface;
use pocketjs_core::damage::{DEFAULT_DAMAGE_REGIONS, DamagePolicy, DamageTracker};
use pocketjs_core::spec;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

pub const ABI_VERSION: u32 = 3;
pub const MIN_ABI_VERSION: u32 = 1;
pub const DEFAULT_LOGICAL_WIDTH: u32 = 240;
pub const DEFAULT_LOGICAL_HEIGHT: u32 = 240;
pub const QUICKJS_BYTECODE_FORMAT: &str = "quickjs-rquickjs-0.12.2-bc26-le-v1";
pub const QUICKJS_BYTECODE_VERSION: u32 = 26;
const OK: i32 = 0;
const IDLE: i32 = 1;
const ERR_ARGUMENT: i32 = -1;
const ERR_STATE: i32 = -2;
const ERR_GUEST: i32 = -3;
const PAK_INTEGRITY_NONE: u32 = 0;
const PAK_INTEGRITY_LEGACY_FULL_FNV: u32 = 1;
const PAK_INTEGRITY_ENTRY_SHA256: u32 = 2;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

fn set_error(message: impl AsRef<str>) {
    let value = CString::new(message.as_ref().replace('\0', " ")).unwrap_or_default();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = value);
}

fn cstr(ptr: *const c_char) -> Option<&'static str> {
    if ptr.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().ok()
}

#[repr(C)]
pub struct PodRuntimeConfig {
    pub struct_size: u32,
    pub target_id: *const c_char,
    pub host_abi: u32,
    pub raster_density: u32,
    pub physical_width: u32,
    pub physical_height: u32,
    pub display_density: f32,
    pub display_shape: u32,
    pub safe_top: f32,
    pub safe_right: f32,
    pub safe_bottom: f32,
    pub safe_left: f32,
    pub data_dir: *const c_char,
    pub capabilities_json: *const c_char,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct PodTouch {
    pub id: u32,
    pub x: f32,
    pub y: f32,
}

#[repr(C)]
pub struct PodInputFrame {
    pub struct_size: u32,
    pub buttons: u32,
    pub analog: u32,
    pub touches: *const PodTouch,
    pub touch_count: u32,
    pub rotary_primary_millidegrees: i32,
    pub rotary_secondary_millidegrees: i32,
}

#[repr(C)]
pub struct PodDrawList {
    pub words: *const u32,
    pub word_count: usize,
    pub content_hash: u64,
    pub frame_number: u64,
    pub changed: i32,
}

#[repr(C)]
pub struct PodTextureView {
    pub handle: i32,
    pub revision: u64,
    pub pixels: *const u8,
    pub byte_length: usize,
    pub width: u32,
    pub height: u32,
    pub pixel_format: u32,
    pub palette: *const u8,
    pub palette_length: usize,
    pub linear: i32,
}

#[repr(C)]
pub struct PodFontView {
    pub slot: u32,
    pub cell_width: u32,
    pub cell_height: u32,
    pub baseline: u32,
    pub line_height: u32,
    pub raster_density: u32,
    pub glyph_count: u32,
    pub bitmap: *const u8,
    pub bitmap_length: usize,
}

#[repr(C)]
pub struct PodGlyphView { pub coverage:*const u8,pub byte_length:usize }

#[repr(C)]
pub struct PodGlyphCacheStats {pub hits:u64,pub misses:u64,pub raster_micros:u64,pub resident_bytes:u64,pub source_bytes:u64,pub seed_bytes:u64,pub cells:u64}

#[repr(C)]
pub struct PodPakStats {
    pub struct_size: u32,
    pub directory_entries: u32,
    pub directory_parse_micros: u64,
    pub pak_hash_micros: u64,
    pub first_font_load_micros: u64,
    pub loaded_font_slots: u32,
    pub pak_integrity_mode: u32,
    pub verified_entries: u32,
    pub integrity_failures: u32,
    pub verified_bytes: u64,
    pub directory_hash_micros: u64,
    pub eager_hash_micros: u64,
    pub integrity_hash_micros: u64,
}

/// Last bytecode evaluation, in microseconds. JS_EvalFunction combines the
/// global-function closure creation with execution; there is no module linker
/// phase for the CLI's global IIFE bundle.
#[repr(C)]
#[derive(Default, Clone, Copy)]
pub struct PodEvalStats {
    pub struct_size: u32,
    pub bytecode_bytes: u64,
    pub hash_micros: u64,
    pub mount_micros: u64,
    pub read_object_micros: u64,
    pub execute_micros: u64,
    pub drain_jobs_micros: u64,
    pub finish_micros: u64,
}

fn elapsed_micros(began: Instant) -> u64 {
    began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64
}

#[repr(C)]
pub struct PodRuntimePerfStats {
    pub struct_size: u32,
    pub loaded_font_slots: u32,
    pub pending_font_slots: u32,
    pub last_font_slot: i32,
    pub last_font_async: i32,
    pub loaded_images: u32,
    pub frame_number: u64,
    pub guest_micros: u64,
    pub surface_tick_micros: u64,
    pub snapshot_micros: u64,
    pub resource_generation: u64,
    pub total_font_load_micros: u64,
    pub last_font_load_micros: u64,
    pub total_image_load_micros: u64,
    pub last_image_load_micros: u64,
}

#[derive(Default)]
struct PodBridge {
    events: VecDeque<String>,
    service_results_only: bool,
    effects: VecDeque<String>,
    metrics: String,
    capabilities: String,
}
#[derive(Default)]
struct HostTransportInner {
    commands: VecDeque<String>,
    completions: VecDeque<TransportCompletion>,
}

#[derive(Clone, Default)]
struct HostTransport {
    inner: Rc<RefCell<HostTransportInner>>,
}

impl HttpTransport for HostTransport {
    fn start(&mut self, request: HttpRequest) -> Result<(), NetFailure> {
        let command = json!({
            "t": "start",
            "handle": request.handle,
            "url": request.url,
            "method": request.method,
            "headers": request.headers,
            "bodyBase64": base64::engine::general_purpose::STANDARD.encode(request.body),
            "timeoutMs": request.timeout_ms,
            "maxBytes": request.max_bytes,
            "maxRedirects": request.max_redirects,
        });
        self.inner
            .borrow_mut()
            .commands
            .push_back(command.to_string());
        Ok(())
    }

    fn cancel(&mut self, handle: i32) {
        self.inner
            .borrow_mut()
            .commands
            .push_back(json!({ "t": "cancel", "handle": handle }).to_string());
    }

    fn drain(&mut self, output: &mut Vec<TransportCompletion>) {
        output.extend(self.inner.borrow_mut().completions.drain(..));
    }
}

pub struct PodRuntime {
    last_glyph:Option<std::sync::Arc<[u8]>>,
    guest: Guest,
    surface: UiSurface,
    fs: Rc<RefCell<FsModule>>,
    net: NetSurface<HostTransport>,
    transport: HostTransport,
    bridge: Rc<RefCell<PodBridge>>,
    kv: Rc<RefCell<KvStore>>,
    guest_files_root: Option<PathBuf>,
    target_id: String,
    host_abi: u32,
    package_abi: u32,
    capabilities: Vec<String>,
    logical_width: u32,
    logical_height: u32,
    lifecycle: u32,
    mounted: bool,
    package_validated: bool,
    expected_bundle_hash: Option<String>,
    expected_bytecode_sha256: Option<String>,
    expected_bytecode_bytes: Option<usize>,
    eval_stats: PodEvalStats,
    mapped_pak: Option<&'static [u8]>,
    pak_loaded: bool,
    pak_hash: u64,
    pak_hash_micros: u64,
    pak_directory_hash_micros: u64,
    pak_eager_hash_micros: u64,
    pak_integrity_mode: u32,
    bundle_hash: u64,
    draw_words: Vec<u32>,
    draw_hash: u64,
    previous_draw_hash: u64,
    accessibility_json: Vec<u8>,
    accessibility_hash: Option<u64>,
    damage_tracker: DamageTracker<DEFAULT_DAMAGE_REGIONS>,
    frame_number: u64,
    guest_micros: u64,
    surface_tick_micros: u64,
    snapshot_micros: u64,
    memory_stats_json: CString,
    poll_effect: CString,
    poll_net: CString,
    receipt: CString,
}

// Fast path for large file-service payloads. Invalid/noncanonical input falls
// back to the SDK codec, retaining its existing permissive semantics.
fn decode_base64<'js>(ctx: Ctx<'js>, input: String) -> rquickjs::Result<Option<TypedArray<'js, u8>>> {
    let decoded = base64::engine::general_purpose::STANDARD.decode(&input)
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(&input));
    match decoded {
        Ok(bytes) => TypedArray::new(ctx, bytes).map(Some),
        Err(_) => Ok(None),
    }
}

// Read the typed-array view, including its offset, synchronously. The result
// owns its storage and does not retain or mutate the guest buffer.
fn encode_base64(input: TypedArray<'_, u8>) -> Option<String> {
    input.as_bytes().map(|bytes| base64::engine::general_purpose::STANDARD.encode(bytes))
}

// Only the image decoder's bounded RGBA outputs may bypass the JSON/Base64
// service lane. The guest still receives an owned typed array, never a path to
// arbitrary application files.
fn read_decoded_rgba(root: &Path, relative: &str) -> Option<Vec<u8>> {
    let digits = relative.strip_prefix("images/decoded-")?.strip_suffix(".rgba")?;
    if digits.is_empty() || digits.len() > 20 || !digits.bytes().all(|digit| digit.is_ascii_digit()) { return None; }
    let file = root.join(format!("decoded-{digits}.rgba"));
    let metadata = std::fs::symlink_metadata(&file).ok()?;
    if !metadata.file_type().is_file() || metadata.len() == 0 || metadata.len() > 512 * 512 * 4 || metadata.len() % 4 != 0 { return None; }
    if file.canonicalize().ok()?.parent()? != root.canonicalize().ok()? { return None; }
    let bytes = std::fs::read(&file).ok()?;
    (bytes.len() as u64 == metadata.len()).then_some(bytes)
}

fn texture_file_path(root: &Path, relative: &str) -> Option<PathBuf> {
    let (directory, name) = relative.split_once('/')?;
    if name.contains('/') || name.contains('\\') || name.contains('\0') { return None; }
    let token = match directory {
        "text" => name.strip_prefix("line-")?.strip_suffix(".rgba")?,
        "images" => name.strip_prefix("decoded-")?.strip_suffix(".rgba")?,
        _ => return None,
    };
    if token.is_empty() || token.len() > 64
        || !token.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return None;
    }
    Some(root.join(directory).join(name))
}

struct RemoveTextureFile(PathBuf);
impl Drop for RemoveTextureFile {
    fn drop(&mut self) { let _ = std::fs::remove_file(&self.0); }
}

// Android image/text services publish bounded RGBA files under two private
// guest directories. This synchronous bridge consumes exactly those outputs,
// deletes them on every accepted-path outcome, and never exposes their bytes to
// QuickJS or the JSON service lane.
fn upload_texture_file(surface: &UiSurface, root: &Path, relative: &str, width: i32, height: i32, psm: i32) -> i32 {
    let Some(path) = texture_file_path(root, relative) else { return -1; };
    let _cleanup = RemoveTextureFile(path.clone());
    let valid_dimension = |value: i32| value > 0 && value <= 512 && value & (value - 1) == 0;
    if !valid_dimension(width) || !valid_dimension(height) || psm != spec::psm::PSM_8888 as i32 { return -1; }
    let Some(expected) = (width as usize).checked_mul(height as usize).and_then(|pixels| pixels.checked_mul(4)) else { return -1; };

    let Some(parent) = path.parent() else { return -1; };
    let Ok(root_canonical) = root.canonicalize() else { return -1; };
    let Ok(parent_metadata) = std::fs::symlink_metadata(parent) else { return -1; };
    if !parent_metadata.file_type().is_dir() { return -1; }
    let Ok(parent_canonical) = parent.canonicalize() else { return -1; };
    if parent_canonical.parent() != Some(root_canonical.as_path()) { return -1; }
    let Ok(canonical) = path.canonicalize() else { return -1; };
    if canonical.parent() != Some(parent_canonical.as_path()) { return -1; }

    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)] {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let Ok(mut file) = options.open(&path) else { return -1; };
    let Ok(metadata) = file.metadata() else { return -1; };
    if !metadata.is_file() || metadata.len() != expected as u64 { return -1; }
    let mut bytes = vec![0; expected];
    if file.read_exact(&mut bytes).is_err() { return -1; }
    surface.with_ui(|ui| ui.upload_texture(&bytes, width as u32, height as u32, psm as u32))
}

fn mount_pod(
    guest: &Guest,
    bridge: Rc<RefCell<PodBridge>>,
    kv: Rc<RefCell<KvStore>>,
    surface: UiSurface,
    guest_files_root: Option<PathBuf>,
) -> anyhow::Result<()> {
    guest.mount("pod", |ctx, ns| {
        ns.set("decodeBase64", Function::new(ctx.clone(), decode_base64)?)?;
        ns.set("encodeBase64", Function::new(ctx.clone(), encode_base64)?)?;
        if let Some(root) = guest_files_root {
            let image_root = root.join("images");
            ns.set("readDecodedRgba", Function::new(ctx.clone(), move |ctx, relative: String| {
                match read_decoded_rgba(&image_root, &relative) {
                    Some(bytes) => TypedArray::new(ctx, bytes).map(Some),
                    None => Ok(None),
                }
            })?)?;
            let upload_surface = surface.clone();
            ns.set("uploadTextureFile", Function::new(ctx.clone(), move |relative: String, width: i32, height: i32, psm: i32| {
                upload_texture_file(&upload_surface, &root, &relative, width, height, psm)
            })?)?;
        }
        ns.set("fontMetrics", Function::new(ctx.clone(), move |slot: f64| {
            if !slot.is_finite() || slot.fract() != 0.0 || !(0.0..=255.0).contains(&slot) { return None; }
            surface.ensure_font(slot as u8);
            surface.with_ui(|ui| ui.font_atlas(slot as u8).map(|font| json!({
                "cellWidth": font.cell_w, "cellHeight": font.cell_h,
                "baseline": font.baseline, "lineHeight": font.line_height,
                "rasterDensity": font.raster_density,
            }).to_string()))
        })?)?;
        let b = bridge.clone();
        ns.set(
            "takeEvents",
            Function::new(ctx.clone(), move || {
                let mut b = b.borrow_mut();
                if b.events.is_empty() {
                    return None;
                };
                let events = if b.service_results_only {
                    let mut results = Vec::new();
                    let mut retained = VecDeque::new();
                    for event in b.events.drain(..) {
                        if serde_json::from_str::<Value>(&event)
                            .is_ok_and(|value| matches!(value.get("t").and_then(Value::as_str),
                                Some("service.result" | "browser.auth.status")))
                        {
                            results.push(event);
                        } else {
                            retained.push_back(event);
                        }
                    }
                    b.events = retained;
                    results
                } else {
                    b.events.drain(..).collect::<Vec<_>>()
                };
                if events.is_empty() {
                    return None;
                }
                let raw = format!("[{}]", events.join(","));
                Some(raw)
            })?,
        )?;

        let b = bridge.clone();
        ns.set(
            "emit",
            Function::new(ctx.clone(), move |line: String| {
                if let Ok(value) = serde_json::from_str::<Value>(&line) {
                    let acoustic = value.get("t").and_then(Value::as_str)==Some("service.request")
                        && value.get("method").and_then(Value::as_str).is_some_and(|m|m.starts_with("acoustic."));
                    let mut bridge=b.borrow_mut();
                    if acoustic && !serde_json::from_str::<Vec<String>>(&bridge.capabilities).unwrap_or_default().iter().any(|c|c=="debug.acoustic") {
                        bridge.events.push_back(json!({"t":"service.result","id":value.get("id"),"ok":false,"code":"permission_denied","message":"Acoustic capability not granted"}).to_string());
                    } else {bridge.effects.push_back(line);}
                }
            })?,
        )?;

        let b = bridge.clone();
        ns.set(
            "displayMetrics",
            Function::new(ctx.clone(), move || b.borrow().metrics.clone())?,
        )?;

        let b = bridge.clone();
        ns.set(
            "capabilities",
            Function::new(ctx.clone(), move || b.borrow().capabilities.clone())?,
        )?;

        let store = kv.clone();
        ns.set(
            "kvGet",
            Function::new(ctx.clone(), move |key: String| {
                store
                    .borrow_mut()
                    .get(&key)
                    .ok().flatten()
                    .and_then(|v| serde_json::to_string(&v).ok())
            })?,
        )?;

        let store = kv.clone();
        ns.set(
            "kvSet",
            Function::new(ctx.clone(), move |key: String, value: String| -> i32 {
                if key.is_empty() || key.as_bytes().len() > 128 {
                    return 1;
                }
                let Ok(value) = serde_json::from_str::<Value>(&value) else {
                    return 1;
                };
                if store.borrow_mut().set(key,value).is_ok() {0} else {1}
            })?,
        )?;

        let store = kv.clone();
        ns.set(
            "kvDelete",
            Function::new(ctx.clone(), move |key: String| -> i32 {
                if store.borrow_mut().delete(&key).unwrap_or(false) {0} else {1}
            })?,
        )?;

        let store = kv;
        ns.set(
            "kvKeys",
            Function::new(ctx.clone(), move || {
                serde_json::to_string(&store.borrow_mut().keys().unwrap_or_default())
                    .unwrap_or_else(|_| "[]".into())
            })?,
        )?;
        Ok(())
    })
}

const FNV_OFFSET: u64 = 0xcbf29ce484222325;

fn hash_extend(mut hash: u64, bytes: &[u8]) -> u64 {
    for byte in bytes {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn hash_bytes(bytes: &[u8]) -> u64 {
    hash_extend(FNV_OFFSET, bytes)
}

fn hash_words(words: &[u32]) -> u64 {
    let bytes = unsafe { slice::from_raw_parts(words.as_ptr() as *const u8, words.len() * 4) };
    hash_bytes(bytes)
}

fn packed_touch(touch: &PodTouch) -> u32 {
    let x = touch.x.round().clamp(0.0, 1023.0) as u32;
    let y = touch.y.round().clamp(0.0, 1023.0) as u32;
    0x8000_0000 | ((touch.id & 0xff) << 20) | (y << 10) | x
}

fn runtime_mut<'a>(ptr: *mut PodRuntime) -> Result<&'a mut PodRuntime, i32> {
    if ptr.is_null() {
        set_error("null PodRuntime");
        Err(ERR_ARGUMENT)
    } else {
        Ok(unsafe { &mut *ptr })
    }
}

fn revoke_package_validation(runtime: &mut PodRuntime) {
    runtime.package_validated = false;
    runtime.expected_bundle_hash = None;
    runtime.expected_bytecode_sha256 = None;
    runtime.expected_bytecode_bytes = None;
}

fn reset_pak_authority(runtime: &mut PodRuntime) {
    revoke_package_validation(runtime);
    runtime.mapped_pak = None;
    runtime.pak_loaded = false;
    runtime.pak_hash = 0;
    runtime.pak_hash_micros = 0;
    runtime.pak_directory_hash_micros = 0;
    runtime.pak_eager_hash_micros = 0;
    runtime.pak_integrity_mode = PAK_INTEGRITY_NONE;
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_create(config: *const PodRuntimeConfig) -> *mut PodRuntime {
    if config.is_null() {
        set_error("null PodRuntimeConfig");
        return std::ptr::null_mut();
    }
    let config = unsafe { &*config };
    if config.struct_size as usize != std::mem::size_of::<PodRuntimeConfig>()
        || !(MIN_ABI_VERSION..=ABI_VERSION).contains(&config.host_abi)
        || config.raster_density == 0
        || config.physical_width == 0
        || config.physical_height == 0
    {
        set_error("invalid runtime config or host ABI");
        return std::ptr::null_mut();
    }
    let Some(target_id) = cstr(config.target_id) else {
        set_error("target_id is required");
        return std::ptr::null_mut();
    };
    if !matches!(
        target_id,
        "android-watch" | "wearos-watch" | "watchos-watch" | "harmonyos-watch"
    ) {
        set_error("unknown PodJS watch target");
        return std::ptr::null_mut();
    }
    let Some(capabilities_raw) = cstr(config.capabilities_json) else {
        set_error("capabilities_json is required");
        return std::ptr::null_mut();
    };
    let Ok(capabilities) = serde_json::from_str::<Vec<String>>(capabilities_raw) else {
        set_error("capabilities_json must be a JSON string array");
        return std::ptr::null_mut();
    };
    let guest = match Guest::new() {
        Ok(value) => value,
        Err(error) => {
            set_error(format!("QuickJS create failed: {error}"));
            return std::ptr::null_mut();
        }
    };
    let logical_width = config.physical_width.div_ceil(config.raster_density);
    let logical_height = config.physical_height.div_ceil(config.raster_density);
    let surface = UiSurface::new_with_density(
        (logical_width as f32, logical_height as f32),
        config.raster_density,
    );
    surface.set_identity(target_id, config.host_abi);
    // Android/Wear renderers negotiate the additive rounded-overflow
    // DrawList command; other watch hosts retain legacy AABB scissors until
    // their fixed-function decoders implement it.
    if matches!(target_id, "android-watch" | "wearos-watch") {
        surface.with_ui(|ui| {
            ui.set_rounded_clip_supported(true);
            ui.set_subpixel_glyphs_supported(true);
            ui.set_subpixel_images_supported(true);
            ui.set_fractional_layout(true);
        });
    }
    if !surface.set_tick_rate(60) {
        set_error("failed to pin the PocketJS clock to 60 Hz");
        return std::ptr::null_mut();
    }

    let data_dir = cstr(config.data_dir);
    let fs = Rc::new(RefCell::new(match data_dir {
        Some(root) => FsModule::with_quota(
            Storage::Dir {
                root: PathBuf::from(root).join("files"),
                tmp: PathBuf::from(root).join("tmp"),
            },
            16 * 1024 * 1024,
        ),
        None => FsModule::with_quota(Storage::Memory, 16 * 1024 * 1024),
    }));
    let bridge = Rc::new(RefCell::new(PodBridge {
        events: VecDeque::new(),
        service_results_only: false,
        effects: VecDeque::new(),
        metrics: json!({
            "logicalWidth": logical_width,
            "logicalHeight": logical_height,
            "physicalWidth": config.physical_width,
            "physicalHeight": config.physical_height,
            "density": config.display_density,
            "shape": if config.display_shape == 0 { "round" } else { "rect" },
            "safeInsets": {
                "top": config.safe_top, "right": config.safe_right,
                "bottom": config.safe_bottom, "left": config.safe_left,
            }
        })
        .to_string(),
        capabilities: serde_json::to_string(&capabilities).unwrap_or_else(|_| "[]".into()),
    }));
    let transport = HostTransport::default();
    let net = NetSurface::new(transport.clone());
    Box::into_raw(Box::new(PodRuntime {
        guest,
        surface,
        last_glyph:None,
        fs,
        net,
        transport,
        bridge,
        kv: Rc::new(RefCell::new(KvStore::open(data_dir))),
        guest_files_root: data_dir.map(|root| PathBuf::from(root).join("files")),
        target_id: target_id.to_owned(),
        host_abi: config.host_abi,
        package_abi: config.host_abi,
        capabilities,
        logical_width,
        logical_height,
        lifecycle: 0,
        mounted: false,
        package_validated: false,
        expected_bundle_hash: None,
        expected_bytecode_sha256: None,
        expected_bytecode_bytes: None,
        eval_stats: PodEvalStats::default(),
        mapped_pak: None,
        pak_loaded: false,
        pak_hash: 0,
        pak_hash_micros: 0,
        pak_directory_hash_micros: 0,
        pak_eager_hash_micros: 0,
        pak_integrity_mode: 0,
        bundle_hash: 0,
        draw_words: Vec::new(),
        draw_hash: 0,
        previous_draw_hash: u64::MAX,
        accessibility_json: Vec::new(),
        accessibility_hash: None,
        damage_tracker: DamageTracker::new(),
        frame_number: 0,
        guest_micros: 0,
        surface_tick_micros: 0,
        snapshot_micros: 0,
        memory_stats_json: CString::default(),
        poll_effect: CString::default(),
        poll_net: CString::default(),
        receipt: CString::default(),
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_logical_width(runtime: *const PodRuntime) -> u32 {
    if runtime.is_null() {
        0
    } else {
        unsafe { (*runtime).logical_width }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_logical_height(runtime: *const PodRuntime) -> u32 {
    if runtime.is_null() {
        0
    } else {
        unsafe { (*runtime).logical_height }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_load_pak(
    runtime: *mut PodRuntime,
    bytes: *const u8,
    length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if runtime.mounted || bytes.is_null() || length == 0 {
        return ERR_STATE;
    }
    reset_pak_authority(runtime);
    let bytes = unsafe { slice::from_raw_parts(bytes, length) };
    let hash_began = Instant::now();
    runtime.pak_hash = hash_bytes(bytes);
    runtime.pak_hash_micros =
        hash_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    runtime.pak_loaded = true;
    runtime.pak_integrity_mode = PAK_INTEGRITY_LEGACY_FULL_FNV;
    runtime.surface.feed_pak(bytes);
    OK
}

/// Load a stable read-only mapping and retain only the package bytes. Directory
/// authentication and resource indexing are deferred until the manifest selects
/// entry SHA-256 or the legacy full-FNV path. The host must keep `bytes` mapped
/// and immutable until runtime destruction.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_runtime_load_pak_mapped(
    runtime: *mut PodRuntime,
    bytes: *const u8,
    length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if runtime.mounted || bytes.is_null() || length == 0 || length > isize::MAX as usize {
        set_error("mapped pak requires an unmounted runtime and a valid nonempty mapping");
        return ERR_STATE;
    }
    reset_pak_authority(runtime);
    let bytes: &'static [u8] = unsafe { slice::from_raw_parts(bytes, length) };
    runtime.mapped_pak = Some(bytes);
    runtime.pak_loaded = true;
    OK
}

/// Load an immutable host source without requiring a contiguous whole-pak buffer.
/// The callback is synchronous and must not re-enter this runtime.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_load_pak_reader(
    runtime: *mut PodRuntime,
    length: usize,
    read_at: Option<PodPakReadAt>,
    context: *mut std::ffi::c_void,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if runtime.mounted || length == 0 {
        set_error("pak reader requires an unmounted runtime and a nonempty source");
        return ERR_STATE;
    }
    let Some(read_at) = read_at else {
        set_error("pak read callback is required");
        return ERR_ARGUMENT;
    };
    if length > isize::MAX as usize {
        set_error("pak source is too large for this host");
        return ERR_ARGUMENT;
    }
    // Failed/partial loads cannot retain a previous package's authority.
    reset_pak_authority(runtime);
    let mut reader = pak_reader::PakReader { length, read_at, context };
    let hash_began = Instant::now();
    let checksum = reader.checksum();
    runtime.pak_hash_micros =
        hash_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    let result = checksum.and_then(|hash| {
        runtime.surface.feed_pak_reader(length, |offset, bytes| reader.read_exact_at(offset, bytes))?;
        Ok(hash)
    });
    match result {
        Ok(hash) => {
            runtime.pak_hash = hash;
            runtime.pak_loaded = true;
            runtime.pak_integrity_mode = PAK_INTEGRITY_LEGACY_FULL_FNV;
            OK
        }
        Err(error) => { set_error(format!("pak reader: {error}")); ERR_STATE }
    }
}

fn validate_loaded_pak(runtime: &mut PodRuntime, manifest: &Value) -> anyhow::Result<()> {
    let declared = manifest
        .get("pakHash")
        .and_then(Value::as_str)
        .filter(|hash| hash.len() == 16 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .context("package manifest has no valid pakHash")?;
    let declared_hash = u64::from_str_radix(declared, 16).context("invalid pakHash")?;
    let Some(pak) = runtime.mapped_pak else {
        if runtime.pak_hash != declared_hash {
            anyhow::bail!("legacy full pak hash mismatch");
        }
        return Ok(());
    };

    runtime.pak_hash = 0;
    runtime.pak_hash_micros = 0;
    runtime.pak_directory_hash_micros = 0;
    runtime.pak_eager_hash_micros = 0;
    runtime.pak_integrity_mode = PAK_INTEGRITY_NONE;
    if let Some(integrity) = manifest.get("pakIntegrity") {
        let validated = pak_integrity::validate(pak, integrity)?;
        unsafe {
            runtime.surface.feed_pak_mapped_verified(
                pak,
                validated.entries,
                validated.directory_parse_micros,
            )?;
        }
        let stats = runtime.surface.lazy_pak_stats();
        runtime.pak_hash = declared_hash;
        runtime.pak_directory_hash_micros = validated.directory_hash_micros;
        runtime.pak_eager_hash_micros = stats.integrity_hash_micros;
        runtime.pak_hash_micros = runtime
            .pak_directory_hash_micros
            .saturating_add(runtime.pak_eager_hash_micros);
        runtime.pak_integrity_mode = PAK_INTEGRITY_ENTRY_SHA256;
        return Ok(());
    }

    let began = Instant::now();
    let actual = hash_bytes(pak);
    runtime.pak_hash_micros = began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    if actual != declared_hash {
        anyhow::bail!("legacy full pak hash mismatch");
    }
    unsafe { runtime.surface.feed_pak_mapped(pak) };
    runtime.pak_hash = actual;
    runtime.pak_integrity_mode = PAK_INTEGRITY_LEGACY_FULL_FNV;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_validate_package(
    runtime: *mut PodRuntime,
    manifest_json: *const c_char,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if runtime.mounted || !runtime.pak_loaded {
        set_error("pak must load before package validation");
        return ERR_STATE;
    }
    // A new validation attempt replaces the prior decision, including failures.
    revoke_package_validation(runtime);
    let Some(raw) = cstr(manifest_json) else {
        set_error("package manifest is required");
        return ERR_ARGUMENT;
    };
    let Ok(value) = serde_json::from_str::<Value>(raw) else {
        set_error("package manifest is invalid JSON");
        return ERR_ARGUMENT;
    };
    let revision = option_env!("PODJS_POCKETJS_REVISION")
        .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086");
    let manifest_bundle = value.get("bundleHash").and_then(Value::as_str);
    let manifest_bytecode = value.get("bytecode");
    let bytecode_absent = manifest_bytecode.is_none() || manifest_bytecode == Some(&Value::Null);
    let valid_bytecode = bytecode_absent || manifest_bytecode.is_some_and(|bytecode| {
        bytecode.get("file").and_then(Value::as_str) == Some("main.qbc")
            && bytecode.get("format").and_then(Value::as_str) == Some(QUICKJS_BYTECODE_FORMAT)
            && bytecode.get("rquickjsVersion").and_then(Value::as_str) == Some("0.12.2")
            && bytecode.get("quickjsBytecodeVersion").and_then(Value::as_u64) == Some(QUICKJS_BYTECODE_VERSION as u64)
            && bytecode.get("endianness").and_then(Value::as_str) == Some("little")
            && bytecode.get("sourceHash").and_then(Value::as_str) == manifest_bundle
            && bytecode.get("sha256").and_then(Value::as_str)
                .is_some_and(|hash| hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()))
            && bytecode.get("bytes").and_then(Value::as_u64)
                .is_some_and(|bytes| bytes > 0 && bytes <= 16 * 1024 * 1024)
    });
    let manifest_capabilities = value
        .get("capabilities")
        .and_then(Value::as_array)
        .and_then(|items| items.iter().map(Value::as_str).collect::<Option<Vec<_>>>());
    let capabilities_match = manifest_capabilities.is_some_and(|items| {
        items.len() == runtime.capabilities.len()
            && items
                .iter()
                .zip(&runtime.capabilities)
                .all(|(a, b)| *a == b)
    });
    let package_abi = value.get("hostAbi").and_then(Value::as_u64);
    let valid = value.get("target").and_then(Value::as_str) == Some(runtime.target_id.as_str())
        && package_abi.is_some_and(|abi| (MIN_ABI_VERSION as u64..=runtime.host_abi as u64).contains(&abi))
        && value.get("pocketjsRevision").and_then(Value::as_str) == Some(revision)
        && value.get("pakHash").and_then(Value::as_str)
            .is_some_and(|hash| hash.len() == 16 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()))
        && manifest_bundle
            .is_some_and(|hash| hash.len() == 16 && hash.bytes().all(|b| b.is_ascii_hexdigit()))
        && valid_bytecode
        && capabilities_match;
    if !valid {
        set_error("package target, ABI, revision, pak hash, or capabilities mismatch");
        return ERR_STATE;
    }
    if let Err(error) = validate_loaded_pak(runtime, &value) {
        set_error(format!("package pak integrity validation failed: {error:#}"));
        return ERR_STATE;
    }
    let preload_slots = match value.get("preloadFontSlots") {
        None => Vec::new(),
        Some(Value::Array(items)) if items.len() <= 8 => {
            let mut slots = Vec::new();
            for item in items {
                let Some(slot) = item.as_u64().filter(|slot| *slot <= 63) else {
                    set_error("invalid preloadFontSlots"); return ERR_STATE;
                };
                let slot = slot as u8;
                if slots.contains(&slot) || !runtime.surface.has_font_resource(slot) {
                    set_error("duplicate or missing preloadFontSlots resource"); return ERR_STATE;
                }
                slots.push(slot);
            }
            slots
        }
        Some(_) => { set_error("invalid preloadFontSlots"); return ERR_STATE; }
    };
    // Start before bytecode eval. Entry SHA-256 is still verified by the worker,
    // and ensure_font consumes completed work before its synchronous fallback.
    for slot in preload_slots { runtime.surface.request_font(slot); }
    runtime.package_validated = true;
    runtime.package_abi = package_abi.expect("validated package ABI") as u32;
    // Legacy bundles may assert ui.__hostAbi exactly. Negotiate before mount,
    // preserving the old guest contract without overstating host capabilities.
    runtime.surface.set_identity(&runtime.target_id, runtime.package_abi);
    runtime.expected_bundle_hash = manifest_bundle.map(str::to_owned);
    if let Some(bytecode) = manifest_bytecode.filter(|_| !bytecode_absent) {
        runtime.expected_bytecode_sha256 = bytecode.get("sha256").and_then(Value::as_str).map(str::to_owned);
        runtime.expected_bytecode_bytes = bytecode.get("bytes").and_then(Value::as_u64).map(|bytes| bytes as usize);
    }
    OK
}

fn mount_runtime(runtime: &mut PodRuntime) -> anyhow::Result<()> {
    runtime
        .surface
        .mount(&runtime.guest)
        .and_then(|_| pocket_fs::mount(&runtime.guest, runtime.fs.clone()))
        .and_then(|_| runtime.net.mount(&runtime.guest))
        .and_then(|_| mount_pod(&runtime.guest, runtime.bridge.clone(), runtime.kv.clone(), runtime.surface.clone(), runtime.guest_files_root.clone()))
}

fn finish_bundle_eval(runtime: &mut PodRuntime, bundle_hash: u64, bytecode_sha256: Option<&str>) -> i32 {
    if !runtime.guest.has_frame() {
        set_error("bundle installed no global frame()");
        return ERR_GUEST;
    }
    runtime.bundle_hash = bundle_hash;
    let revision = option_env!("PODJS_POCKETJS_REVISION")
        .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086");
    let mut receipt = json!({
        "target": runtime.target_id,
        "hostAbi": runtime.host_abi,
        "packageAbi": runtime.package_abi,
        "runtimeAbi": ABI_VERSION,
        "pocketjsRevision": revision,
        "pakHash": format!("{:016x}", runtime.pak_hash),
        "bundleHash": format!("{:016x}", runtime.bundle_hash),
    });
    if let Some(hash) = bytecode_sha256 { receipt["bytecodeSha256"] = json!(hash); }
    runtime.receipt = CString::new(receipt.to_string()).unwrap_or_default();
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_eval_bundle(
    runtime: *mut PodRuntime,
    source: *const u8,
    length: usize,
    label: *const c_char,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if runtime.mounted {
        set_error("bundle evaluation is one-shot");
        return ERR_STATE;
    }
    if !runtime.package_validated {
        set_error("package must validate before bundle evaluation");
        return ERR_STATE;
    }
    if source.is_null() || length == 0 {
        return ERR_ARGUMENT;
    }
    let bytes = unsafe { slice::from_raw_parts(source, length) };
    let actual_bundle_hash = format!("{:016x}", hash_bytes(bytes));
    if runtime.expected_bundle_hash.as_deref() != Some(actual_bundle_hash.as_str()) {
        set_error("bundle hash does not match package manifest");
        return ERR_STATE;
    }
    let Ok(bundle) = std::str::from_utf8(bytes) else {
        set_error("bundle is not UTF-8");
        return ERR_ARGUMENT;
    };
    if let Err(error) = mount_runtime(runtime) {
        set_error(format!("surface mount failed: {error}"));
        return ERR_GUEST;
    }
    runtime.mounted = true;
    let label = cstr(label).unwrap_or("app");
    if let Err(error) = runtime.guest.eval(label, bundle) {
        set_error(format!("bundle eval failed: {error}"));
        return ERR_GUEST;
    }
    finish_bundle_eval(runtime, hash_bytes(bytes), None)
}

/// Evaluate CLI-produced QuickJS bytecode. The manifest pins the rquickjs release,
/// QuickJS bytecode version, little-endian encoding, source hash, byte length and
/// SHA-256 before the untrusted binary reader is entered. Source evaluation remains
/// available for packages without this optional field and for older hosts.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_eval_bundle_bytecode(
    runtime: *mut PodRuntime,
    bytecode: *const u8,
    length: usize,
) -> i32 {
    use pocket_mod::qjs::qjs as raw;
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if runtime.mounted {
        set_error("bundle evaluation is one-shot");
        return ERR_STATE;
    }
    if !runtime.package_validated {
        set_error("package must validate before bundle evaluation");
        return ERR_STATE;
    }
    if bytecode.is_null() || length == 0 { return ERR_ARGUMENT; }
    let bytes = unsafe { slice::from_raw_parts(bytecode, length) };
    if runtime.expected_bytecode_bytes != Some(length) {
        set_error("bytecode length does not match package manifest");
        return ERR_STATE;
    }
    runtime.eval_stats = PodEvalStats { bytecode_bytes: length as u64, ..Default::default() };
    let began = Instant::now();
    let actual_sha256 = format!("{:x}", Sha256::digest(bytes));
    runtime.eval_stats.hash_micros = elapsed_micros(began);
    if runtime.expected_bytecode_sha256.as_deref() != Some(actual_sha256.as_str()) {
        set_error("bytecode hash does not match package manifest");
        return ERR_STATE;
    }
    let began = Instant::now();
    if let Err(error) = mount_runtime(runtime) {
        set_error(format!("surface mount failed: {error}"));
        return ERR_GUEST;
    }
    runtime.eval_stats.mount_micros = elapsed_micros(began);
    runtime.mounted = true;
    let evaluation = runtime.guest.with(|ctx| {
        let began = Instant::now();
        let compiled = unsafe { raw::JS_ReadObject(
            ctx.as_raw().as_ptr(), bytes.as_ptr(), bytes.len() as _, raw::JS_READ_OBJ_BYTECODE as i32,
        ) };
        runtime.eval_stats.read_object_micros = elapsed_micros(began);
        if unsafe { raw::JS_IsException(compiled) } {
            return Err(format!("{:?}", ctx.catch()));
        }
        let began = Instant::now();
        let result = unsafe { raw::JS_EvalFunction(ctx.as_raw().as_ptr(), compiled) };
        runtime.eval_stats.execute_micros = elapsed_micros(began);
        if unsafe { raw::JS_IsException(result) } {
            return Err(format!("{:?}", ctx.catch()));
        }
        unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), result); }
        Ok(())
    });
    if let Err(error) = evaluation {
        set_error(format!("bundle bytecode eval failed: {error}"));
        return ERR_GUEST;
    }
    let began = Instant::now();
    runtime.guest.drain_jobs();
    runtime.eval_stats.drain_jobs_micros = elapsed_micros(began);
    let began = Instant::now();
    let Some(source_hash) = runtime.expected_bundle_hash.as_deref()
        .and_then(|hash| u64::from_str_radix(hash, 16).ok()) else {
        set_error("validated package lost source hash");
        return ERR_STATE;
    };
    let result = finish_bundle_eval(runtime, source_hash, Some(&actual_sha256));
    runtime.eval_stats.finish_micros = elapsed_micros(began);
    result
}

/// Set before evaluating the guest. Values retain physical units in displayMetrics;
/// consumers convert using physical/logical extent, not font/raster density.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_set_scroll_configuration(
    runtime: *mut PodRuntime, touch_slop: f32, min_fling: f32, max_fling: f32,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if !touch_slop.is_finite() || !min_fling.is_finite() || !max_fling.is_finite()
        || touch_slop <= 0.0 || min_fling <= 0.0 || max_fling < min_fling {
        return ERR_ARGUMENT;
    }
    let mut bridge = runtime.bridge.borrow_mut();
    let Ok(mut metrics) = serde_json::from_str::<Value>(&bridge.metrics) else { return ERR_STATE; };
    metrics["scrollConfiguration"] = json!({
        "touchSlopPx": touch_slop, "minimumFlingVelocityPx": min_fling,
        "maximumFlingVelocityPx": max_fling,
    });
    bridge.metrics = metrics.to_string();
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_set_lifecycle(runtime: *mut PodRuntime, state: u32) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if state > 2 {
        return ERR_ARGUMENT;
    }
    if runtime.lifecycle == state {
        return OK;
    }
    runtime.lifecycle = state;
    let state_name = ["active", "inactive", "background"][state as usize];
    runtime
        .bridge
        .borrow_mut()
        .events
        .push_back(json!({ "t": "lifecycle", "state": state_name }).to_string());
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_set_theme(runtime: *mut PodRuntime, theme: *const c_char) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    let Some(theme) = cstr(theme) else {
        return ERR_ARGUMENT;
    };
    if !matches!(theme, "light" | "dark") {
        return ERR_ARGUMENT;
    }
    runtime
        .bridge
        .borrow_mut()
        .events
        .push_back(json!({ "t": "theme", "theme": theme }).to_string());
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_post_event(runtime: *mut PodRuntime, object: *const c_char) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    let Some(raw) = cstr(object) else {
        return ERR_ARGUMENT;
    };
    if raw.len() > 1024 * 1024 || !serde_json::from_str::<Value>(raw).is_ok_and(|v| v.is_object()) {
        return ERR_ARGUMENT;
    }
    let mut bridge = runtime.bridge.borrow_mut();
    if bridge.events.len() >= 256
        || bridge.events.iter().map(String::len).sum::<usize>() + raw.len() > 4 * 1024 * 1024
    {
        set_error("host event queue full; retain and retry after guest drain");
        return ERR_STATE;
    }
    bridge.events.push_back(raw.to_owned());
    OK
}

/// Host-side authority query. Configured capabilities alone do not authorize IO
/// before package validation and successful guest mounting.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_has_capability(runtime: *mut PodRuntime, name: *const c_char) -> bool {
    let Ok(runtime) = runtime_mut(runtime) else { return false };
    let Some(name) = cstr(name) else { return false };
    runtime.mounted && runtime.package_validated && name.len() <= 128
        && runtime.capabilities.iter().any(|value| value == name)
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_pump_services(runtime: *mut PodRuntime) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted {
        return ERR_STATE;
    }
    // Unlike frame(), this does not tick the UI surface or advance its committed
    // draw snapshot. Hosts call it for lifecycle/IO completion, never as a timer.
    runtime.net.begin_tick();
    if let Err(error) = runtime.guest.eval(
        "pod-service-completion",
        "if (typeof globalThis.__pocketPumpServices === 'function') globalThis.__pocketPumpServices();",
    ) {
        set_error(format!("guest service pump failed: {error}"));
        return ERR_GUEST;
    }
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_pump_service_results(runtime: *mut PodRuntime) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted {
        return ERR_STATE;
    }
    // begin_tick only promotes completed transport requests; it does not reset
    // a per-frame request budget. Legacy NET completions can also settle here.
    runtime.net.begin_tick();
    let previous_mode = {
        let mut bridge = runtime.bridge.borrow_mut();
        std::mem::replace(&mut bridge.service_results_only, true)
    };
    // Keep the mode active through eval's Promise-job drain, without retaining
    // a RefCell borrow while guest code can call pod.takeEvents().
    let result = runtime.guest.eval(
        "pod-service-results",
        "if (typeof globalThis.__pocketPumpServices === 'function') globalThis.__pocketPumpServices();",
    );
    runtime.bridge.borrow_mut().service_results_only = previous_mode;
    if let Err(error) = result {
        set_error(format!("guest service result pump failed: {error}"));
        return ERR_GUEST;
    }
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_frame(runtime: *mut PodRuntime, input: *const PodInputFrame) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted {
        return ERR_STATE;
    }
    if runtime.lifecycle == 2 {
        return IDLE;
    }
    let default = PodInputFrame {
        struct_size: std::mem::size_of::<PodInputFrame>() as u32,
        buttons: 0,
        analog: spec::ANALOG_CENTER,
        touches: std::ptr::null(),
        touch_count: 0,
        rotary_primary_millidegrees: 0,
        rotary_secondary_millidegrees: 0,
    };
    let input = if input.is_null() {
        &default
    } else {
        unsafe { &*input }
    };
    if input.struct_size as usize != std::mem::size_of::<PodInputFrame>() {
        return ERR_ARGUMENT;
    }
    for (axis, delta) in [
        (0, input.rotary_primary_millidegrees),
        (1, input.rotary_secondary_millidegrees),
    ] {
        if delta != 0 {
            runtime
                .bridge
                .borrow_mut()
                .events
                .push_back(json!({ "t": "axis", "axis": axis, "delta": delta }).to_string());
        }
    }
    runtime.net.begin_tick();
    let touches = if input.touches.is_null() || input.touch_count == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(input.touches, input.touch_count.min(8) as usize) }
    };
    let words = touches.iter().map(packed_touch).collect::<Vec<_>>();
    let mut hits = [0i32; 8];
    let hit_count = runtime
        .surface
        .with_ui(|ui| ui.touch_hits(&words, &mut hits));
    let analog = if input.analog == 0 {
        spec::ANALOG_CENTER
    } else {
        input.analog
    };
    let guest_began = Instant::now();
    if let Err(error) =
        runtime
            .guest
            .frame_with_touch_hits(input.buttons, analog, &words, &hits[..hit_count])
    {
        runtime.guest_micros = guest_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
        runtime.surface_tick_micros = 0;
        set_error(format!("guest frame failed: {error}"));
        return ERR_GUEST;
    }
    runtime.guest_micros = guest_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    let surface_began = Instant::now();
    runtime.surface.tick();
    runtime.surface_tick_micros = surface_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    runtime.frame_number += 1;
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_snapshot(runtime: *mut PodRuntime, out: *mut PodDrawList) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted || out.is_null() {
        return ERR_STATE;
    }
    let began = Instant::now();
    let mut hash = runtime.surface.with_ui(|ui| hash_words(&ui.draw().words));
    runtime.surface.with_ui(|ui| {
        for slot in 0..ui.texture_slot_count() as u32 {
            if let Some((handle, revision, _)) = ui.texture_at_versioned(slot) {
                // Mix ordered identities and versions: XOR alone cancels simultaneous
                // updates when two media textures acquire the same revision.
                hash = (hash ^ handle as u32 as u64).wrapping_mul(0x100000001b3);
                hash = (hash ^ revision).wrapping_mul(0x100000001b3);
            }
        }
    });
    if hash != runtime.draw_hash {
        runtime
            .surface
            .with_ui(|ui| runtime.draw_words.clone_from(&ui.draw().words));
    }
    runtime.previous_draw_hash = runtime.draw_hash;
    runtime.draw_hash = hash;
    unsafe {
        *out = PodDrawList {
            words: runtime.draw_words.as_ptr(),
            word_count: runtime.draw_words.len(),
            content_hash: hash,
            frame_number: runtime.frame_number,
            changed: i32::from(hash != runtime.previous_draw_hash),
        };
    }
    runtime.snapshot_micros = began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    OK
}

/// Render the most recently snapshotted DrawList into an RGBA8 framebuffer.
///
/// SpriteKit hosts use this to submit one immutable texture instead of
/// materializing thousands of nodes for PocketJS rounded geometry. Rendering
/// is still performed from the canonical DrawList and the same font/texture
/// resources used by every other backend.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_render_rgba(
    runtime: *mut PodRuntime,
    scale: u32,
    pixels: *mut u8,
    length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted || pixels.is_null() || !(1..=4).contains(&scale) {
        return ERR_ARGUMENT;
    }
    let Some(width) = runtime.logical_width.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(height) = runtime.logical_height.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(expected) = (width as usize)
        .checked_mul(height as usize)
        .and_then(|value| value.checked_mul(4))
    else {
        return ERR_ARGUMENT;
    };
    if length != expected {
        return ERR_ARGUMENT;
    }
    let framebuffer = unsafe { slice::from_raw_parts_mut(pixels, length) };
    runtime.surface.with_ui(|ui| {
        pocketjs_core::raster::render_scaled(ui, &runtime.draw_words, framebuffer, scale)
    });
    runtime.damage_tracker.invalidate();
    OK
}

/// Incrementally render into a host-retained RGBA8 framebuffer.
///
/// Damage tracking is runtime-owned so Android can keep a stable native buffer
/// without mirroring PocketJS paint state across the C ABI. If the damage plan
/// cannot be built, correctness wins: render the complete frame and invalidate
/// the tracker so the next call safely starts a new transaction.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_render_rgba_incremental(
    runtime: *mut PodRuntime,
    scale: u32,
    pixels: *mut u8,
    length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted || pixels.is_null() || !(1..=4).contains(&scale) {
        return ERR_ARGUMENT;
    }
    let Some(width) = runtime.logical_width.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(height) = runtime.logical_height.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(expected) = (width as usize)
        .checked_mul(height as usize)
        .and_then(|value| value.checked_mul(4))
    else {
        return ERR_ARGUMENT;
    };
    if length != expected {
        return ERR_ARGUMENT;
    }
    let framebuffer = unsafe { slice::from_raw_parts_mut(pixels, length) };
    let result = runtime.surface.with_ui(|ui| {
        pocketjs_core::raster::render_scaled_incremental(
            ui,
            &runtime.draw_words,
            framebuffer,
            scale,
            &mut runtime.damage_tracker,
            DamagePolicy::default(),
        )
    });
    if result.is_err() {
        runtime.surface.with_ui(|ui| {
            pocketjs_core::raster::render_scaled(ui, &runtime.draw_words, framebuffer, scale)
        });
        runtime.damage_tracker.invalidate();
    }
    OK
}

/// Incrementally render premultiplied RGBA8 for an alpha-composited native
/// surface. This is opt-in; existing hosts retain opaque-black raster output.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_render_rgba_transparent_incremental(
    runtime: *mut PodRuntime,
    scale: u32,
    pixels: *mut u8,
    length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if !runtime.mounted || pixels.is_null() || !(1..=4).contains(&scale) {
        return ERR_ARGUMENT;
    }
    let Some(width) = runtime.logical_width.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(height) = runtime.logical_height.checked_mul(scale) else {
        return ERR_ARGUMENT;
    };
    let Some(expected) = (width as usize)
        .checked_mul(height as usize)
        .and_then(|value| value.checked_mul(4))
    else {
        return ERR_ARGUMENT;
    };
    if length != expected {
        return ERR_ARGUMENT;
    }
    let framebuffer = unsafe { slice::from_raw_parts_mut(pixels, length) };
    let result = runtime.surface.with_ui(|ui| {
        pocketjs_core::raster::render_scaled_transparent_incremental(
            ui,
            &runtime.draw_words,
            framebuffer,
            scale,
            &mut runtime.damage_tracker,
            DamagePolicy::default(),
        )
    });
    if result.is_err() {
        runtime.surface.with_ui(|ui| {
            pocketjs_core::raster::render_scaled_transparent(
                ui,
                &runtime.draw_words,
                framebuffer,
                scale,
            )
        });
        runtime.damage_tracker.invalidate();
    }
    OK
}

/// Native-owned RGBA media textures. The host serializes these calls with frames.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_upload_rgba(runtime: *mut PodRuntime, pixels: *const u8, length: usize, width: u32, height: u32) -> i32 {
    pod_runtime_upload_rgba_sampled(runtime, pixels, length, width, height, 0)
}
/// Additive upload API: sampling is 0 for nearest, 1 for linear, and immutable.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_upload_rgba_sampled(runtime: *mut PodRuntime, pixels: *const u8, length: usize, width: u32, height: u32, sampling: i32) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    let flags = match sampling {
        0 => 0,
        1 => pocketjs_core::spec::img::FLAG_LINEAR,
        _ => return ERR_ARGUMENT,
    };
    if pixels.is_null() || width == 0 || height == 0 || width > 1024 || height > 1024 || length != width as usize * height as usize * 4 { return ERR_ARGUMENT; }
    let bytes = unsafe { std::slice::from_raw_parts(pixels, length) };
    runtime.surface.with_ui(|ui| ui.upload_texture_flags(bytes, width, height, 3, flags))
}
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_update_rgba(runtime: *mut PodRuntime, handle: i32, pixels: *const u8, length: usize) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if pixels.is_null() || length == 0 || length > 1024 * 1024 * 4 { return ERR_ARGUMENT; }
    let bytes = unsafe { std::slice::from_raw_parts(pixels, length) };
    if runtime.surface.with_ui(|ui| ui.update_texture_rgba(handle, bytes)) { OK } else { ERR_ARGUMENT }
}
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_free_texture(runtime: *mut PodRuntime, handle: i32) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    runtime.surface.with_ui(|ui| ui.free_texture(handle));
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_texture(
    runtime: *mut PodRuntime,
    slot: u32,
    out: *mut PodTextureView,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if out.is_null() {
        return ERR_ARGUMENT;
    }
    runtime.surface.with_ui(|ui| {
        let Some((handle, revision, view)) = ui.texture_at_versioned(slot) else {
            return IDLE;
        };
        let palette = view.palette.unwrap_or(&[]);
        unsafe {
            *out = PodTextureView {
                handle,
                revision,
                pixels: view.pixels.as_ptr(),
                byte_length: view.pixels.len(),
                width: view.w,
                height: view.h,
                pixel_format: view.psm,
                palette: palette.as_ptr(),
                palette_length: palette.len(),
                linear: i32::from(view.linear),
            }
        };
        OK
    })
}

/// Resolve a generation-tagged texture handle directly. Unlike the legacy
/// slot sweep, this rejects freed handles and slot reuse with a stale
/// generation instead of enumerating a slot.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_texture_for_handle(
    runtime: *mut PodRuntime,
    handle: i32,
    out: *mut PodTextureView,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if out.is_null() {
        return ERR_ARGUMENT;
    }
    runtime.surface.with_ui(|ui| {
        let Some(view) = ui.texture(handle) else {
            return IDLE;
        };
        let Some(revision) = ui.texture_revision(handle) else {
            return IDLE;
        };
        let palette = view.palette.unwrap_or(&[]);
        unsafe {
            *out = PodTextureView {
                handle,
                revision,
                pixels: view.pixels.as_ptr(),
                byte_length: view.pixels.len(),
                width: view.w,
                height: view.h,
                pixel_format: view.psm,
                palette: palette.as_ptr(),
                palette_length: palette.len(),
                linear: i32::from(view.linear),
            };
        }
        OK
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_font(
    runtime: *mut PodRuntime,
    slot: u32,
    out: *mut PodFontView,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if out.is_null() || slot > u8::MAX as u32 {
        return ERR_ARGUMENT;
    }
    runtime.surface.ensure_font(slot as u8);
    runtime.surface.with_ui(|ui| {
        let Some(font) = ui.font_atlas(slot as u8) else {
            return IDLE;
        };
        unsafe {
            *out = PodFontView {
                slot,
                cell_width: font.cell_w,
                cell_height: font.cell_h,
                baseline: font.baseline,
                line_height: font.line_height,
                raster_density: font.raster_density as u32,
                glyph_count: font.glyph_count as u32,
                bitmap: font.bitmap.as_ptr(),
                bitmap_length: font.bitmap.len(),
            }
        };
        OK
    })
}

// Fixed names and numeric leaves only: a malformed/custom guest diagnostic
// must not put text, cache keys or URLs into the automatically logged sample.
fn numeric_sdk_memory(value: Value) -> Value {
    let sources: &[(&str, &[&str])] = &[
        ("systemText", &["active","queued","cached","queueSlots","refs","listeners","textures","textureBytes","utf16Units","paragraphUtf16Units","keyUtf16Units","caretUnits"]),
        ("services", &["pending"]),
        ("watch", &["hostEvents","lifecycle","theme","back","axis"]),
        ("tree", &["mirrors","roots","retained","sweep","textures","sprites"]),
        ("frame", &["callbacks"]),
        ("clock", &["timers"]),
        ("solid", &["roots","owners","computations","signals","signalObservers"]),
        ("gesture", &["contacts","recognizers","velocitySamples","contactOwners"]),
        ("devtools", &["enabled","tapeFrames","touchRecords","touchWords","replayFrames"]),
        ("bench", &["errors","disposed","offset","hit","owners","computations","timers","signalObservers","signals"]),
    ];
    let mut result = serde_json::Map::new();
    for (name, fields) in sources {
        let Some(source) = value.get(*name) else { continue; };
        if source.is_null() { result.insert((*name).into(),Value::Null); continue; }
        let mut counts = serde_json::Map::new();
        for key in *fields {
            if let Some(Value::Number(number)) = source.get(*key) {
                counts.insert((*key).into(),Value::Number(number.clone()));
            }
        }
        if !counts.is_empty() { result.insert((*name).into(),Value::Object(counts)); }
    }
    if result.is_empty() { Value::Null } else { Value::Object(result) }
}

/// Demand-only ownership snapshot. The host must serialize this with guest
/// turns, exactly as it serializes texture/frame APIs. No GC is performed.
impl PodRuntime {
    #[cfg(feature = "memory-diagnostics")]
    pub fn debug_run_gc(&self) { self.guest.debug_run_gc(); }

    #[cfg(feature = "memory-diagnostics")]
    pub fn debug_trace_gc(&self) { self.guest.debug_trace_gc(); }

    pub fn memory_stats(&self) -> Value {
        // Collect optional SDK registries outside the runtime mutex used by
        // JS_ComputeMemoryUsage. No text, keys, paths or URLs are recorded.
        let sdk = self.guest.with(|ctx| {
            ctx.globals().get::<_, Option<Function>>("__podMemoryStats").catch(&ctx).ok().flatten()
                .and_then(|f| f.call::<_, String>(()).catch(&ctx).ok())
                .and_then(|s| serde_json::from_str::<Value>(&s).ok())
                .map(numeric_sdk_memory).unwrap_or(Value::Null)
        });
        let q = self.guest.memory_usage();
        let ui = self.surface.with_ui(|ui| ui.memory_stats());
        let (glyph, source, seeds) = self.surface.glyph_cache_stats();
        let bridge = self.bridge.borrow();
        json!({
            "schema": 1, "frame": self.frame_number,
            "quickjs": {"gcThresholdBytes":self.guest.gc_threshold(), "mallocBytes":q.malloc_size, "usedBytes":q.memory_used_size,
                "mallocCount":q.malloc_count, "usedCount":q.memory_used_count,
                "objects":q.obj_count, "objectBytes":q.obj_size,
                "strings":q.str_count, "stringBytes":q.str_size,
                "atoms":q.atom_count, "atomBytes":q.atom_size,
                "shapes":q.shape_count, "shapeBytes":q.shape_size,
                "functions":q.js_func_count, "functionBytes":q.js_func_size,
                "functionCodeBytes":q.js_func_code_size, "arrays":q.array_count},
            "surface": {"nodes":ui.nodes, "nodeSlots":ui.node_slots,
                "nodeCapacity":ui.node_capacity, "nodeArenaBytes":ui.node_arena_bytes,
                "nodePayloadCapacityBytes":ui.node_payload_capacity_bytes,
                "textures":ui.textures, "textureSlots":ui.texture_slots,
                "textureCapacity":ui.texture_capacity,
                "textureBytes":ui.texture_bytes, "textureCapacityBytes":ui.texture_capacity_bytes,
                "fontBitmapBytes":ui.font_bitmap_bytes},
            "glyph": {"cells":glyph.cells, "cellBytes":glyph.bytes, "sourceBytes":source,
                "seedBytes":seeds, "lastPinnedCellBytes":self.last_glyph.as_ref().map_or(0,|v|v.len()),
                "hits":glyph.hits, "misses":glyph.misses},
            "runtime": {"pakMappedBytes":self.mapped_pak.map_or(0,|v|v.len()),
                "drawCapacityBytes":self.draw_words.capacity()*4,
                "accessibilityCapacityBytes":self.accessibility_json.capacity(),
                "events":bridge.events.len(), "effects":bridge.effects.len(),
                "eventBytes":bridge.events.iter().map(|v|v.len()).sum::<usize>(),
                "effectBytes":bridge.effects.iter().map(|v|v.len()).sum::<usize>(),
                "netCommands":self.transport.inner.borrow().commands.len(),
                "netCompletions":self.transport.inner.borrow().completions.len()},
            "sdk": sdk
        })
    }
}

/// JSON remains valid until the next memory sample or runtime destruction.
/// Android callers enforce FLAG_DEBUGGABLE/PERF_TRACE before invoking this.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_memory_stats(runtime: *mut PodRuntime) -> *const c_char {
    let Ok(runtime) = runtime_mut(runtime) else { return std::ptr::null(); };
    runtime.memory_stats_json = CString::new(runtime.memory_stats().to_string()).unwrap_or_default();
    runtime.memory_stats_json.as_ptr()
}

/// Zero for legacy atlases; runtime epoch is immutable per installation.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_glyph_cache_stats(runtime:*mut PodRuntime,out:*mut PodGlyphCacheStats)->i32 {
    let Ok(runtime)=runtime_mut(runtime) else {return ERR_ARGUMENT};if out.is_null() {return ERR_ARGUMENT;}
    let (s,source,seeds)=runtime.surface.glyph_cache_stats();
    unsafe {*out=PodGlyphCacheStats{hits:s.hits,misses:s.misses,raster_micros:s.raster_micros,resident_bytes:s.bytes as u64,source_bytes:source as u64,seed_bytes:seeds as u64,cells:s.cells as u64};}OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_font_epoch(runtime:*mut PodRuntime,slot:u32)->u64 {
    let Ok(runtime)=runtime_mut(runtime) else {return 0};
    if slot>255 {return 0;}
    runtime.surface.with_ui(|ui|ui.font_atlas(slot as u8).and_then(|a|a.runtime.as_ref()).map_or(0,|i|i.cache_epoch))
}
/// Coverage pointer is valid until the next glyph call or runtime destruction.
/// Decoder copies it immediately; an Arc pins it across shared-cache eviction.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_glyph(runtime:*mut PodRuntime,slot:u32,gid:u32,out:*mut PodGlyphView)->i32 {
    let Ok(runtime)=runtime_mut(runtime) else {return ERR_ARGUMENT};
    if slot>255||gid>65535||out.is_null() {return ERR_ARGUMENT;}
    let cell=runtime.surface.with_ui(|ui| {
        let a=ui.font_atlas(slot as u8)?;if gid>=u32::from(a.glyph_count) {return None;}
        match a.glyph_coverage(gid as u16) {
            pocketjs_core::text::GlyphRows::Shared(v)=>Some(v),
            pocketjs_core::text::GlyphRows::Borrowed(v)=>if a.runtime.is_some() {None}else{Some(std::sync::Arc::<[u8]>::from(v))},
        }
    });
    let Some(cell)=cell else {return IDLE};
    unsafe {*out=PodGlyphView{coverage:cell.as_ptr(),byte_length:cell.len()};}
    runtime.last_glyph=Some(cell);OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_pak_stats(
    runtime: *mut PodRuntime,
    out: *mut PodPakStats,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if out.is_null() {
        return ERR_ARGUMENT;
    }
    let available = unsafe { (*out).struct_size as usize };
    if available < std::mem::size_of::<u32>() {
        return ERR_ARGUMENT;
    }
    let stats = runtime.surface.lazy_pak_stats();
    let value = PodPakStats {
        struct_size: std::mem::size_of::<PodPakStats>() as u32,
        directory_entries: stats.directory_entries.min(u32::MAX as usize) as u32,
        directory_parse_micros: stats.directory_parse_micros,
        pak_hash_micros: runtime.pak_hash_micros,
        first_font_load_micros: stats.first_font_load_micros.unwrap_or(0),
        loaded_font_slots: stats.loaded_fonts.min(u32::MAX as usize) as u32,
        pak_integrity_mode: runtime.pak_integrity_mode,
        verified_entries: stats.verified_entries.min(u32::MAX as usize) as u32,
        integrity_failures: stats.integrity_failures.min(u32::MAX as usize) as u32,
        verified_bytes: stats.verified_bytes,
        directory_hash_micros: runtime.pak_directory_hash_micros,
        eager_hash_micros: runtime.pak_eager_hash_micros,
        integrity_hash_micros: stats.integrity_hash_micros,
    };
    unsafe {
        std::ptr::copy_nonoverlapping(
            (&value as *const PodPakStats).cast::<u8>(),
            out.cast::<u8>(),
            available.min(std::mem::size_of::<PodPakStats>()),
        );
    }
    OK
}

/// Copy only the caller-declared prefix, as with the other stats APIs.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_eval_stats(runtime: *mut PodRuntime, out: *mut PodEvalStats) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if out.is_null() { return ERR_ARGUMENT; }
    let available = unsafe { (*out).struct_size as usize };
    if available < std::mem::size_of::<u32>() { return ERR_ARGUMENT; }
    let value = PodEvalStats { struct_size: std::mem::size_of::<PodEvalStats>() as u32, ..runtime.eval_stats };
    unsafe {
        std::ptr::copy_nonoverlapping((&value as *const PodEvalStats).cast::<u8>(), out.cast::<u8>(),
            available.min(std::mem::size_of::<PodEvalStats>()));
    }
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_perf_stats(
    runtime: *mut PodRuntime,
    out: *mut PodRuntimePerfStats,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if out.is_null() { return ERR_ARGUMENT; }
    let stats = runtime.surface.lazy_pak_stats();
    unsafe {
        *out = PodRuntimePerfStats {
            struct_size: std::mem::size_of::<PodRuntimePerfStats>() as u32,
            loaded_font_slots: stats.loaded_fonts.min(u32::MAX as usize) as u32,
            pending_font_slots: stats.pending_fonts.min(u32::MAX as usize) as u32,
            last_font_slot: stats.last_font_slot.map(i32::from).unwrap_or(-1),
            last_font_async: i32::from(stats.last_font_async),
            loaded_images: stats.loaded_images.min(u32::MAX as usize) as u32,
            frame_number: runtime.frame_number,
            guest_micros: runtime.guest_micros,
            surface_tick_micros: runtime.surface_tick_micros,
            snapshot_micros: runtime.snapshot_micros,
            resource_generation: stats.resource_generation,
            total_font_load_micros: stats.total_font_load_micros,
            last_font_load_micros: stats.last_font_load_micros,
            total_image_load_micros: stats.total_image_load_micros,
            last_image_load_micros: stats.last_image_load_micros,
        };
    }
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_poll_effect(runtime: *mut PodRuntime) -> *const c_char {
    let Ok(runtime) = runtime_mut(runtime) else {
        return std::ptr::null();
    };
    let Some(line) = runtime.bridge.borrow_mut().effects.pop_front() else {
        return std::ptr::null();
    };
    runtime.poll_effect = CString::new(line).unwrap_or_default();
    runtime.poll_effect.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_poll_net_command(runtime: *mut PodRuntime) -> *const c_char {
    let Ok(runtime) = runtime_mut(runtime) else {
        return std::ptr::null();
    };
    let Some(line) = runtime.transport.inner.borrow_mut().commands.pop_front() else {
        return std::ptr::null();
    };
    runtime.poll_net = CString::new(line).unwrap_or_default();
    runtime.poll_net.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_complete_http(
    runtime: *mut PodRuntime,
    handle: i32,
    status: u32,
    url: *const c_char,
    headers_json: *const c_char,
    body: *const u8,
    body_length: usize,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    let (Some(url), Some(headers_raw)) = (cstr(url), cstr(headers_json)) else {
        return ERR_ARGUMENT;
    };
    let Ok(headers) = serde_json::from_str::<BTreeMap<String, String>>(headers_raw) else {
        return ERR_ARGUMENT;
    };
    let body = if body.is_null() || body_length == 0 {
        Vec::new()
    } else {
        unsafe { slice::from_raw_parts(body, body_length) }.to_vec()
    };
    runtime
        .transport
        .inner
        .borrow_mut()
        .completions
        .push_back(TransportCompletion::Done {
            handle,
            status: status as u16,
            url: url.to_owned(),
            headers,
            body,
        });
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_fail_http(
    runtime: *mut PodRuntime,
    handle: i32,
    code: *const c_char,
    message: *const c_char,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    let (Some(code), Some(message)) = (cstr(code), cstr(message)) else {
        return ERR_ARGUMENT;
    };
    runtime
        .transport
        .inner
        .borrow_mut()
        .completions
        .push_back(TransportCompletion::Error {
            handle,
            failure: NetFailure::new(code, message),
        });
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_receipt(runtime: *mut PodRuntime) -> *const c_char {
    let Ok(runtime) = runtime_mut(runtime) else {
        return std::ptr::null();
    };
    runtime.receipt.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_destroy(runtime: *mut PodRuntime) {
    if !runtime.is_null() {
        unsafe { drop(Box::from_raw(runtime)) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(target: &CString) -> PodRuntimeConfig {
        PodRuntimeConfig {
            struct_size: std::mem::size_of::<PodRuntimeConfig>() as u32,
            target_id: target.as_ptr(),
            host_abi: 1,
            raster_density: 1,
            physical_width: 466,
            physical_height: 466,
            display_density: 1.941_666_7,
            display_shape: 0,
            safe_top: 8.0,
            safe_right: 8.0,
            safe_bottom: 8.0,
            safe_left: 8.0,
            data_dir: std::ptr::null(),
            capabilities_json: std::ptr::null(),
        }
    }

    #[test]
    fn memory_snapshot_uses_default_guest_and_distinguishes_live_from_capacity() {
        assert!(pod_runtime_memory_stats(std::ptr::null_mut()).is_null());
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let r = unsafe { &*runtime };
        let initial = r.memory_stats();
        assert!(initial["quickjs"]["mallocBytes"].as_i64().unwrap() > 0);
        assert_eq!(initial["surface"]["nodes"], 1);
        assert_eq!(initial["sdk"], Value::Null);
        r.guest.eval("allocate", "globalThis.held = Array.from({length:1000}, (_, i)=>({text:'owned'+i}));").unwrap();
        let (node,texture) = r.surface.with_ui(|ui| {
            (ui.create_node(0),ui.upload_texture(&[1;64],4,4,3))
        });
        let allocated = r.memory_stats();
        assert!(allocated["quickjs"]["usedBytes"].as_i64().unwrap() > initial["quickjs"]["usedBytes"].as_i64().unwrap());
        assert_eq!(allocated["surface"]["nodes"], 2);
        assert_eq!(allocated["surface"]["textures"], 1);
        assert_eq!(allocated["surface"]["textureBytes"], 64);
        r.guest.eval("release", "globalThis.held = null; globalThis.__podMemoryStats=()=>JSON.stringify({services:{pending:0}});").unwrap();
        r.surface.with_ui(|ui|{ui.destroy_node(node);ui.free_texture(texture);});
        let released: Value = serde_json::from_str(unsafe {CStr::from_ptr(pod_runtime_memory_stats(runtime))}.to_str().unwrap()).unwrap();
        assert_eq!(released["surface"]["nodes"], 1);
        assert_eq!(released["surface"]["textureBytes"], 0);
        assert_eq!(released["surface"]["textureSlots"], 1);
        assert_eq!(released["surface"]["nodeCapacity"], allocated["surface"]["nodeCapacity"]);
        assert_eq!(released["sdk"]["services"]["pending"], 0);
        r.guest.eval("private diagnostic", "globalThis.__podMemoryStats=()=>JSON.stringify({privateText:'secret',services:{pending:2,url:'secret'},tree:{textures:'secret'}});").unwrap();
        assert_eq!(r.memory_stats()["sdk"],json!({"services":{"pending":2}}));
        r.guest.eval("broken diagnostic", "globalThis.__podMemoryStats=()=>{throw new Error('unavailable');};").unwrap();
        assert_eq!(r.memory_stats()["sdk"],Value::Null);
        r.guest.eval("diagnostic getter", "Object.defineProperty(globalThis,'__podMemoryStats',{get(){throw new Error('unavailable');},configurable:true});").unwrap();
        assert_eq!(r.memory_stats()["sdk"],Value::Null);
        r.guest.eval("after sampling", "globalThis.afterSampling = 42;").unwrap();
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn acoustic_effect_requires_capability_and_denial_reaches_guest() {
        for granted in [false,true] {
            let target=CString::new("android-watch").unwrap();
            let caps=CString::new(if granted {"[\"debug.acoustic\"]"} else {"[]"}).unwrap();
            let runtime=pod_runtime_create(&valid_config(&target,&caps));
            assert!(!runtime.is_null());
            let source=b"globalThis.frame=function(){};pod.emit(JSON.stringify({t:'service.request',id:7,method:'acoustic.pcm.open',args:{mode:'record'}}));";
            let grants: &[&str]=if granted { &["debug.acoustic"] } else { &[] };
            validate(runtime,"android-watch",b"pak",source,grants);
            assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
            let bridge=unsafe {&*runtime}.bridge.borrow();
            if granted {assert_eq!(bridge.effects.len(),1);}else{
                assert!(bridge.effects.is_empty());
                let reply:Value=serde_json::from_str(bridge.events.back().unwrap()).unwrap();
                assert_eq!(reply["id"],7);assert_eq!(reply["code"],"permission_denied");assert_eq!(reply["ok"],false);
            }
            drop(bridge);pod_runtime_destroy(runtime);
        }
    }

    #[test]
    fn scroll_configuration_publishes_physical_units_and_rejects_bad_values() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        assert_eq!(pod_runtime_set_scroll_configuration(runtime, 16.0, 100.0, 16000.0), OK);
        let metrics: Value = serde_json::from_str(&unsafe { &*runtime }.bridge.borrow().metrics).unwrap();
        assert_eq!(metrics["scrollConfiguration"]["touchSlopPx"], 16.0);
        assert_eq!(metrics["scrollConfiguration"]["minimumFlingVelocityPx"], 100.0);
        assert_eq!(metrics["scrollConfiguration"]["maximumFlingVelocityPx"], 16000.0);
        for (slop, min, max) in [(f32::NAN, 1.0, 2.0), (8.0, 100.0, 50.0), (0.0, 1.0, 2.0), (8.0, 1.0, f32::INFINITY)] {
            assert_eq!(pod_runtime_set_scroll_configuration(runtime, slop, min, max), ERR_ARGUMENT);
        }
        let unchanged: Value = serde_json::from_str(&unsafe { &*runtime }.bridge.borrow().metrics).unwrap();
        assert_eq!(metrics, unchanged);
        pod_runtime_destroy(runtime);
    }

    fn valid_config(target: &CString, capabilities: &CString) -> PodRuntimeConfig {
        let mut value = config(target);
        value.capabilities_json = capabilities.as_ptr();
        value
    }

    fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
        bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
    }

    fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn align_pak(value: usize) -> usize {
        (value + pocketjs_core::spec::pak::ALIGN - 1) & !(pocketjs_core::spec::pak::ALIGN - 1)
    }

    fn integrity_pak(entries: &[(&str, &[u8])]) -> (Vec<u8>, Value, BTreeMap<String, std::ops::Range<usize>>) {
        let header = pocketjs_core::spec::pak::HEADER_SIZE;
        let record_bytes = entries.len() * pocketjs_core::spec::pak::ENTRY_SIZE;
        let names_offset = header + record_bytes;
        let names_bytes = entries.iter().map(|(key, _)| key.len()).sum::<usize>();
        let directory_bytes = align_pak(names_offset + names_bytes);
        let mut total = directory_bytes;
        for (_, blob) in entries { total = align_pak(total + blob.len()); }
        let mut pak = vec![0u8; total];
        put_u32(&mut pak, 0, pocketjs_core::spec::pak::MAGIC);
        put_u16(&mut pak, 4, pocketjs_core::spec::pak::VERSION);
        put_u32(&mut pak, 8, entries.len() as u32);
        put_u32(&mut pak, 12, header as u32);
        put_u32(&mut pak, 16, names_offset as u32);
        put_u32(&mut pak, 20, directory_bytes as u32);
        put_u32(&mut pak, 24, total as u32);
        let mut name_cursor = 0usize;
        let mut blob_cursor = directory_bytes;
        let mut manifest_entries = Vec::new();
        let mut ranges = BTreeMap::new();
        for (index, (key, blob)) in entries.iter().enumerate() {
            let record = header + index * pocketjs_core::spec::pak::ENTRY_SIZE;
            let key_hash = key.encode_utf16().fold(0x811c_9dc5u32, |hash, unit| {
                (hash ^ u32::from(unit)).wrapping_mul(0x0100_0193)
            });
            put_u32(&mut pak, record, key_hash);
            put_u32(&mut pak, record + 4, blob_cursor as u32);
            put_u32(&mut pak, record + 8, blob.len() as u32);
            put_u32(&mut pak, record + 12, name_cursor as u32);
            put_u16(&mut pak, record + 16, key.len() as u16);
            pak[names_offset + name_cursor..names_offset + name_cursor + key.len()]
                .copy_from_slice(key.as_bytes());
            let end = blob_cursor + blob.len();
            pak[blob_cursor..end].copy_from_slice(blob);
            ranges.insert((*key).to_owned(), blob_cursor..end);
            manifest_entries.push(json!({
                "key": key,
                "offset": blob_cursor,
                "bytes": blob.len(),
                "sha256": format!("{:x}", Sha256::digest(blob)),
            }));
            name_cursor += key.len();
            blob_cursor = align_pak(end);
        }
        let integrity = json!({
            "format": pak_integrity::FORMAT,
            "directoryBytes": directory_bytes,
            "directorySha256": format!("{:x}", Sha256::digest(&pak[..directory_bytes])),
            "entries": manifest_entries,
        });
        (pak, integrity, ranges)
    }

    fn package_manifest(pak: &[u8], integrity: Option<Value>, source: &[u8]) -> CString {
        let mut value = json!({
            "target": "android-watch",
            "hostAbi": 1,
            "pocketjsRevision": option_env!("PODJS_POCKETJS_REVISION")
                .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
            "pakHash": format!("{:016x}", hash_bytes(pak)),
            "bundleHash": format!("{:016x}", hash_bytes(source)),
            "capabilities": [],
        });
        if let Some(integrity) = integrity { value["pakIntegrity"] = integrity; }
        CString::new(value.to_string()).unwrap()
    }

    fn pak_stats(runtime: *mut PodRuntime) -> PodPakStats {
        let mut stats: PodPakStats = unsafe { std::mem::zeroed() };
        stats.struct_size = std::mem::size_of::<PodPakStats>() as u32;
        assert_eq!(pod_runtime_pak_stats(runtime, &mut stats), OK);
        stats
    }

    #[test]
    fn pak_stats_respects_legacy_caller_struct_size() {
        #[repr(C)]
        struct LegacyPodPakStats {
            struct_size: u32,
            directory_entries: u32,
            directory_parse_micros: u64,
            pak_hash_micros: u64,
            first_font_load_micros: u64,
            loaded_font_slots: u32,
        }

        #[repr(C)]
        struct GuardedLegacyStats {
            stats: LegacyPodPakStats,
            canary: [u8; 16],
        }

        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        let mut guarded = GuardedLegacyStats {
            stats: LegacyPodPakStats {
                struct_size: std::mem::size_of::<LegacyPodPakStats>() as u32,
                directory_entries: u32::MAX,
                directory_parse_micros: u64::MAX,
                pak_hash_micros: u64::MAX,
                first_font_load_micros: u64::MAX,
                loaded_font_slots: u32::MAX,
            },
            canary: [0xa5; 16],
        };
        assert_eq!(
            pod_runtime_pak_stats(
                runtime,
                (&mut guarded.stats as *mut LegacyPodPakStats).cast::<PodPakStats>(),
            ),
            OK,
        );
        assert_eq!(
            guarded.stats.struct_size,
            std::mem::size_of::<PodPakStats>() as u32,
        );
        assert_eq!(guarded.stats.directory_entries, 0);
        assert_eq!(guarded.stats.loaded_font_slots, 0);
        assert_eq!(guarded.canary, [0xa5; 16]);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn mapped_entry_integrity_defers_payload_hashes_and_rejects_font_image_tile_and_js_tamper() {
        let font = b"font atlas payload";
        let image = [1, 0, 1, 0, 3, 0, 0, 0, 255, 0, 0, 255];
        let tile = b"tileset payload";
        let (original, integrity, ranges) = integrity_pak(&[
            ("ui:font.7", font),
            ("ui:img.hero", &image),
            ("ui:tile.map", tile),
        ]);
        let source = br#"
            if (ui.loadPakImage('hero') !== -1) throw new Error('tampered image accepted');
            if (ui.loadTileTexture('ui:tile.map', 0) !== -1) throw new Error('tampered tile accepted');
            ui.measureText('font', 7);
            globalThis.frame = function() {};
        "#;
        let manifest = package_manifest(&original, Some(integrity), source);
        let mut damaged = original;
        for key in ["ui:font.7", "ui:img.hero", "ui:tile.map"] {
            damaged[ranges[key].start] ^= 1;
        }
        let mapped: &'static [u8] = Box::leak(damaged.into_boxed_slice());
        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(unsafe { pod_runtime_load_pak_mapped(runtime, mapped.as_ptr(), mapped.len()) }, OK);
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK,
            "{}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
        let before = pak_stats(runtime);
        assert_eq!(before.pak_integrity_mode, PAK_INTEGRITY_ENTRY_SHA256);
        assert_eq!(before.directory_entries, 3);
        assert_eq!(before.verified_entries, 0);
        assert_eq!(before.integrity_failures, 0);

        let mut source_tamper = source.to_vec();
        source_tamper[0] ^= 1;
        assert_eq!(pod_runtime_eval_bundle(runtime, source_tamper.as_ptr(), source_tamper.len(), std::ptr::null()), ERR_STATE);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK,
            "{}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
        for _ in 0..200 {
            unsafe { &*runtime }.surface.tick();
            if pak_stats(runtime).integrity_failures == 3 { break; }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        let after = pak_stats(runtime);
        assert_eq!(after.integrity_failures, 3);
        assert_eq!(after.verified_entries, 0);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn mapped_entry_integrity_reports_successful_lazy_verification_once() {
        let font = b"font atlas payload";
        let image = [1, 0, 1, 0, 3, 0, 0, 0, 255, 0, 0, 255];
        let tile = b"tileset payload";
        let (pak, integrity, _) = integrity_pak(&[
            ("ui:font.7", font),
            ("ui:img.hero", &image),
            ("ui:tile.map", tile),
        ]);
        let source = br#"
            if (ui.loadPakImage('hero') < 0) throw new Error('valid image rejected');
            ui.loadPakImage('hero');
            ui.loadTileTexture('ui:tile.map', 0);
            ui.loadTileTexture('ui:tile.map', 0);
            ui.measureText('font', 7);
            globalThis.frame = function() {};
        "#;
        let manifest = package_manifest(&pak, Some(integrity), source);
        let mapped: &'static [u8] = Box::leak(pak.into_boxed_slice());
        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(
            unsafe { pod_runtime_load_pak_mapped(runtime, mapped.as_ptr(), mapped.len()) },
            OK,
        );
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
        assert_eq!(
            pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()),
            OK,
        );
        for _ in 0..200 {
            unsafe { &*runtime }.surface.tick();
            if pak_stats(runtime).verified_entries == 3 { break; }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        let stats = pak_stats(runtime);
        assert_eq!(stats.verified_entries, 3);
        assert_eq!(
            stats.verified_bytes,
            (font.len() + image.len() + tile.len()) as u64,
        );
        assert_eq!(stats.integrity_failures, 0);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn startup_font_preload_is_bounded_async_and_preserves_entry_integrity() {
        use pocketjs_core::spec::font_atlas as fa;
        let mut font = Vec::new();
        font.extend_from_slice(&fa::MAGIC.to_le_bytes());
        font.extend_from_slice(&fa::VERSION.to_le_bytes());
        font.extend_from_slice(&1u16.to_le_bytes());
        font.extend_from_slice(&[8, 10, 7, 12, 7, 0, 1, 0]);
        font.extend_from_slice(&('A' as u32).to_le_bytes());
        font.extend_from_slice(&[0, 0, 8, 0]);
        font.extend_from_slice(&[255; 80]);
        let (pak, integrity, ranges) = integrity_pak(&[("ui:font.7", &font)]);
        let source = b"globalThis.frame=function(){};";
        let original: Value = serde_json::from_str(package_manifest(&pak, Some(integrity), source).to_str().unwrap()).unwrap();
        for damaged in [false, true] {
            let mut bytes = pak.clone();
            if damaged { bytes[ranges["ui:font.7"].start] ^= 1; }
            let mapped = Box::leak(bytes.into_boxed_slice());
            let target = CString::new("android-watch").unwrap();
            let capabilities = CString::new("[]").unwrap();
            let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
            assert_eq!(unsafe { pod_runtime_load_pak_mapped(runtime, mapped.as_ptr(), mapped.len()) }, OK);
            for invalid in [json!(null), json!([7,7]), json!([64]), json!([1.5]), json!([8]), json!([0,1,2,3,4,5,6,7,8])] {
                let mut manifest = original.clone(); manifest["preloadFontSlots"] = invalid;
                let manifest = CString::new(manifest.to_string()).unwrap();
                assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), ERR_STATE);
                assert!(!unsafe { &*runtime }.package_validated);
            }
            let mut manifest = original.clone(); manifest["preloadFontSlots"] = json!([7]);
            let manifest = CString::new(manifest.to_string()).unwrap();
            assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
            assert_eq!(pak_stats(runtime).loaded_font_slots, 0, "queueing must not parse on the validation thread");
            for _ in 0..200 {
                unsafe { &*runtime }.surface.tick();
                if pak_stats(runtime).verified_entries + pak_stats(runtime).integrity_failures > 0 { break; }
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            let stats = unsafe { &*runtime }.surface.lazy_pak_stats();
            if damaged {
                assert_eq!(stats.integrity_failures, 1); assert_eq!(stats.loaded_fonts, 0);
                assert!(!unsafe { &*runtime }.surface.ensure_font(7));
            } else {
                assert_eq!(stats.verified_entries, 1); assert_eq!(stats.loaded_fonts, 1);
                assert!(stats.last_font_async);
                assert!(unsafe { &*runtime }.surface.ensure_font(7));
                assert_eq!(pak_stats(runtime).verified_entries, 1);
            }
            pod_runtime_destroy(runtime);
        }
    }

    #[test]
    fn mapped_legacy_manifest_retains_full_pak_hash_fallback() {
        let pak: &'static [u8] = Box::leak(b"legacy pak".to_vec().into_boxed_slice());
        let source = b"globalThis.frame=function(){};";
        let manifest = package_manifest(pak, None, source);
        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(unsafe { pod_runtime_load_pak_mapped(runtime, pak.as_ptr(), pak.len()) }, OK);
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
        assert_eq!(pak_stats(runtime).pak_integrity_mode, PAK_INTEGRITY_LEGACY_FULL_FNV);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        pod_runtime_destroy(runtime);
    }

    #[test]
    #[ignore = "set PODJS_BENCH_DIST to a CLI-built package directory"]
    #[cfg(unix)]
    fn benchmark_mapped_pak_legacy_full_hash_against_entry_startup_validation() {
        use std::os::fd::AsRawFd;

        struct Mapping { ptr: *mut libc::c_void, len: usize }
        impl Drop for Mapping {
            fn drop(&mut self) { unsafe { libc::munmap(self.ptr, self.len); } }
        }

        let dist = PathBuf::from(std::env::var("PODJS_BENCH_DIST").expect("PODJS_BENCH_DIST"));
        let file = std::fs::File::open(dist.join("main.pak")).expect("open main.pak");
        let len = file.metadata().expect("pak metadata").len() as usize;
        let ptr = unsafe { libc::mmap(
            std::ptr::null_mut(), len, libc::PROT_READ, libc::MAP_PRIVATE, file.as_raw_fd(), 0,
        ) };
        assert_ne!(ptr, libc::MAP_FAILED);
        let mapping = Mapping { ptr, len };
        let pak: &'static [u8] = unsafe { slice::from_raw_parts(mapping.ptr.cast(), mapping.len) };
        let manifest_text = std::fs::read_to_string(dist.join("pod.manifest.json"))
            .expect("read pod.manifest.json");
        let entry_value: Value = serde_json::from_str(&manifest_text).expect("manifest JSON");
        assert!(entry_value.get("pakIntegrity").is_some(), "benchmark requires a new manifest");
        let mut legacy_value = entry_value.clone();
        legacy_value.as_object_mut().unwrap().remove("pakIntegrity");
        let manifests = [
            ("entry-sha256", CString::new(entry_value.to_string()).unwrap()),
            ("legacy-fnv", CString::new(legacy_value.to_string()).unwrap()),
        ];
        let target = CString::new(entry_value["target"].as_str().unwrap()).unwrap();
        let capabilities = CString::new(entry_value["capabilities"].to_string()).unwrap();

        for (mode, manifest) in manifests {
            let mut wall_micros = Vec::new();
            let mut reported_micros = Vec::new();
            for _ in 0..11 {
                let mut config = valid_config(&target, &capabilities);
                config.host_abi = ABI_VERSION;
                let runtime = pod_runtime_create(&config);
                let began = Instant::now();
                assert_eq!(unsafe { pod_runtime_load_pak_mapped(runtime, pak.as_ptr(), pak.len()) }, OK);
                assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK,
                    "{mode}: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
                wall_micros.push(began.elapsed().as_micros() as u64);
                reported_micros.push(pak_stats(runtime).pak_hash_micros);
                pod_runtime_destroy(runtime);
            }
            wall_micros.sort_unstable();
            reported_micros.sort_unstable();
            println!(
                "K6_BENCH mode={mode} bytes={} wall_ms={:.3} pak_hash_ms={:.3}",
                pak.len(), wall_micros[wall_micros.len() / 2] as f64 / 1000.0,
                reported_micros[reported_micros.len() / 2] as f64 / 1000.0,
            );
        }
        drop(mapping);
    }

    fn compile_test_bytecode(source: &[u8]) -> Vec<u8> {
        compile_test_bytecode_with_flags(source, pocket_mod::qjs::qjs::JS_WRITE_OBJ_BYTECODE as i32)
    }

    fn compile_test_bytecode_with_flags(source: &[u8], write_flags: i32) -> Vec<u8> {
        use pocket_mod::qjs::{Context, Runtime, qjs as raw};
        let runtime = Runtime::new().unwrap();
        let context = Context::full(&runtime).unwrap();
        let source_c = CString::new(source).unwrap();
        context.with(|ctx| {
            let compiled = unsafe { raw::JS_Eval(
                ctx.as_raw().as_ptr(), source_c.as_ptr(), source.len() as _, c"test.js".as_ptr(),
                (raw::JS_EVAL_TYPE_GLOBAL | raw::JS_EVAL_FLAG_STRICT | raw::JS_EVAL_FLAG_COMPILE_ONLY) as i32,
            ) };
            assert!(!unsafe { raw::JS_IsException(compiled) }, "{:?}", ctx.catch());
            let mut length = 0;
            let bytes = unsafe { raw::JS_WriteObject(
                ctx.as_raw().as_ptr(), &mut length, compiled, write_flags,
            ) };
            unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), compiled); }
            assert!(!bytes.is_null());
            let result = unsafe { slice::from_raw_parts(bytes, length as usize) }.to_vec();
            unsafe { raw::js_free(ctx.as_raw().as_ptr(), bytes.cast()); }
            result
        })
    }

    #[test]
    fn quickjs_bytecode_is_version_hash_and_source_bound_with_safe_source_fallback() {
        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let source = b"globalThis.value='bytecode-ok';globalThis.frame=()=>{};";
        let bytecode = compile_test_bytecode(source);
        assert_eq!(bytecode[0], QUICKJS_BYTECODE_VERSION as u8);
        let pak = b"pak";
        let bytecode_sha256 = format!("{:x}", Sha256::digest(&bytecode));
        let revision = option_env!("PODJS_POCKETJS_REVISION")
            .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086");
        let manifest_value = json!({
            "target":"android-watch", "hostAbi":1, "pocketjsRevision":revision,
            "pakHash":format!("{:016x}",hash_bytes(pak)),
            "bundleHash":format!("{:016x}",hash_bytes(source)), "capabilities":[],
            "bytecode":{
                "file":"main.qbc", "format":QUICKJS_BYTECODE_FORMAT,
                "rquickjsVersion":"0.12.2", "quickjsBytecodeVersion":QUICKJS_BYTECODE_VERSION,
                "endianness":"little", "sourceHash":format!("{:016x}",hash_bytes(source)),
                "sha256":bytecode_sha256, "bytes":bytecode.len(),
            }
        });
        let manifest = CString::new(manifest_value.to_string()).unwrap();

        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), OK);
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
        assert_eq!(pod_runtime_eval_bundle_bytecode(runtime, bytecode.as_ptr(), bytecode.len()), OK,
            "{}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
        let receipt: Value = serde_json::from_str(cstr(pod_runtime_receipt(runtime)).unwrap()).unwrap();
        assert_eq!(receipt["bundleHash"], format!("{:016x}", hash_bytes(source)));
        assert_eq!(receipt["bytecodeSha256"], bytecode_sha256);
        pod_runtime_destroy(runtime);

        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), OK);
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
        let mut damaged = bytecode.clone(); let last = damaged.len() - 1; damaged[last] ^= 1;
        assert_eq!(pod_runtime_eval_bundle_bytecode(runtime, damaged.as_ptr(), damaged.len()), ERR_STATE);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK,
            "source fallback: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
        pod_runtime_destroy(runtime);

        let mut wrong = manifest_value;
        wrong["bytecode"]["quickjsBytecodeVersion"] = json!(QUICKJS_BYTECODE_VERSION + 1);
        let wrong = CString::new(wrong.to_string()).unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), OK);
        assert_eq!(pod_runtime_validate_package(runtime, wrong.as_ptr()), ERR_STATE);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn stripped_bytecode_is_authenticated_and_eval_stats_respect_caller_size() {
        use pocket_mod::qjs::qjs as raw;
        let target = CString::new("android-watch").unwrap();
        let capabilities = CString::new("[]").unwrap();
        let source = b"globalThis.frame=()=>{};globalThis.answer=(()=>42)();";
        let bytecode = compile_test_bytecode_with_flags(source,
            (raw::JS_WRITE_OBJ_BYTECODE | raw::JS_WRITE_OBJ_STRIP_SOURCE) as i32);
        let sha = format!("{:x}", Sha256::digest(&bytecode));
        let manifest = CString::new(json!({
            "target":"android-watch", "hostAbi":1,
            "pocketjsRevision":option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
            "pakHash":format!("{:016x}",hash_bytes(b"pak")),
            "bundleHash":format!("{:016x}",hash_bytes(source)), "capabilities":[],
            "bytecode":{"file":"main.qbc", "format":QUICKJS_BYTECODE_FORMAT,
                "rquickjsVersion":"0.12.2", "quickjsBytecodeVersion":QUICKJS_BYTECODE_VERSION,
                "endianness":"little", "sourceHash":format!("{:016x}",hash_bytes(source)),
                "sha256":sha, "bytes":bytecode.len(), "sourceIncluded":false, "debugIncluded":true}
        }).to_string()).unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &capabilities));
        assert_eq!(pod_runtime_load_pak(runtime, b"pak".as_ptr(), 3), OK);
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
        let mut damaged = bytecode.clone();
        *damaged.last_mut().unwrap() ^= 1;
        assert_eq!(pod_runtime_eval_bundle_bytecode(runtime, damaged.as_ptr(), damaged.len()), ERR_STATE);
        assert!(!unsafe { &*runtime }.mounted, "authenticate before mount or reader side effects");
        assert_eq!(pod_runtime_eval_bundle_bytecode(runtime, bytecode.as_ptr(), bytecode.len()), OK);
        assert_eq!(unsafe { &*runtime }.guest.with(|ctx| ctx.globals().get::<_, i32>("answer").unwrap()), 42);
        let receipt: Value = serde_json::from_str(cstr(pod_runtime_receipt(runtime)).unwrap()).unwrap();
        assert_eq!(receipt["bytecodeSha256"], sha);
        let mut stats = PodEvalStats { struct_size: std::mem::size_of::<PodEvalStats>() as u32, ..Default::default() };
        assert_eq!(pod_runtime_eval_stats(runtime, &mut stats), OK);
        assert_eq!(stats.bytecode_bytes, bytecode.len() as u64);
        #[repr(C, align(8))]
        struct Prefix { size: u32, canary: u32 }
        let mut prefix = Prefix { size: 4, canary: 0xfeed_beef };
        assert_eq!(pod_runtime_eval_stats(runtime, (&mut prefix as *mut Prefix).cast()), OK);
        assert_eq!(prefix.size as usize, std::mem::size_of::<PodEvalStats>());
        assert_eq!(prefix.canary, 0xfeed_beef);
        prefix.size = 0;
        assert_eq!(pod_runtime_eval_stats(runtime, (&mut prefix as *mut Prefix).cast()), ERR_ARGUMENT);
        assert_eq!(pod_runtime_eval_stats(runtime, std::ptr::null_mut()), ERR_ARGUMENT);
        pod_runtime_destroy(runtime);
    }

    #[test]
    #[ignore = "set PODJS_COMPARE_BEFORE and PODJS_COMPARE_AFTER to bytecode distributions"]
    fn bytecode_variants_preserve_initial_frames() {
        let roots = ["PODJS_COMPARE_BEFORE", "PODJS_COMPARE_AFTER"].map(|name|
            PathBuf::from(std::env::var(name).expect("comparison dist path")));
        assert_eq!(std::fs::read(roots[0].join("main.js")).unwrap(),
            std::fs::read(roots[1].join("main.js")).unwrap(), "same source required");
        let mut recordings = Vec::new();
        for root in &roots {
            let pak = std::fs::read(root.join("main.pak")).unwrap();
            let bytecode = std::fs::read(root.join("main.qbc")).unwrap();
            let manifest_text = std::fs::read_to_string(root.join("pod.manifest.json")).unwrap();
            let manifest: Value = serde_json::from_str(&manifest_text).unwrap();
            let target = CString::new(manifest["target"].as_str().unwrap()).unwrap();
            let capabilities = CString::new(manifest["capabilities"].to_string()).unwrap();
            let manifest_c = CString::new(manifest_text).unwrap();
            let mut config = valid_config(&target, &capabilities);
            config.host_abi = manifest["hostAbi"].as_u64().unwrap() as u32;
            config.raster_density = 2;
            config.physical_width = 480;
            config.physical_height = 480;
            let runtime = pod_runtime_create(&config);
            assert!(!runtime.is_null());
            assert_eq!(pod_runtime_load_pak(runtime,pak.as_ptr(),pak.len()),OK);
            assert_eq!(pod_runtime_validate_package(runtime,manifest_c.as_ptr()),OK);
            assert_eq!(pod_runtime_eval_bundle_bytecode(runtime,bytecode.as_ptr(),bytecode.len()),OK,
                "{}",cstr(pod_runtime_last_error()).unwrap_or("unknown"));
            let mut frames = Vec::new();
            for _ in 0..5 {
                assert_eq!(pod_runtime_frame(runtime,std::ptr::null()),OK);
                let mut draw = PodDrawList { words:std::ptr::null(),word_count:0,content_hash:0,frame_number:0,changed:0 };
                assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);
                let words = if draw.word_count == 0 { Vec::new() } else {
                    unsafe { slice::from_raw_parts(draw.words,draw.word_count) }.to_vec()
                };
                assert!(!words.is_empty(), "initial frame must contain draw commands");
                frames.push((draw.content_hash,words));
            }
            println!("K7_FRAMES variant={} hashes={:?} word_counts={:?}",root.display(),frames.iter().map(|f|f.0).collect::<Vec<_>>(),frames.iter().map(|f|f.1.len()).collect::<Vec<_>>());
            recordings.push(frames);
            pod_runtime_destroy(runtime);
        }
        assert_eq!(recordings[0],recordings[1],"initial draw commands differ");
    }

    #[derive(Default)]
    struct ModuleProfileState {
        stack: Vec<(String, Instant, std::time::Duration)>,
        samples: BTreeMap<String, Vec<(u64, u64)>>,
        errors: Vec<String>,
    }

    /// Diagnostic-only K5 harness. It is ignored in the normal suite and loads the exact
    /// CLI-produced main.js/main.pak selected by PODJS_PROFILE_DIST. Keeping it here gives
    /// the profiler access to the already-mounted production Guest without adding a release
    /// ABI or changing the normal single-eval boot path.
    #[test]
    #[ignore = "set PODJS_PROFILE_DIST to a built android-watch distribution"]
    fn profile_bundle_compile_execute_and_modules() {
        use pocket_mod::qjs::{Function, qjs as raw};

        let dist = PathBuf::from(std::env::var("PODJS_PROFILE_DIST")
            .expect("PODJS_PROFILE_DIST must name the android-watch dist directory"));
        let source = std::fs::read(dist.join("main.js")).expect("read main.js");
        let pak = std::fs::read(dist.join("main.pak")).expect("read main.pak");
        let manifest_text = std::fs::read_to_string(dist.join("pod.manifest.json"))
            .expect("read pod.manifest.json");
        let manifest: Value = serde_json::from_str(&manifest_text).expect("parse manifest");
        let bytecode = (std::env::var("PODJS_PROFILE_BYTECODE").as_deref() == Ok("1"))
            .then(|| std::fs::read(dist.join("main.qbc")).expect("read main.qbc"));
        let capabilities = CString::new(manifest["capabilities"].to_string()).unwrap();
        let target = CString::new(manifest["target"].as_str().unwrap()).unwrap();
        let manifest_c = CString::new(manifest_text).unwrap();
        let iterations = std::env::var("PODJS_PROFILE_ITERATIONS").ok()
            .and_then(|value| value.parse::<usize>().ok()).unwrap_or(5).clamp(1, 25);
        let expect_modules = std::env::var("PODJS_PROFILE_EXPECT_MODULES").as_deref() == Ok("1");
        let mut compile_samples = Vec::with_capacity(iterations);
        let mut execute_samples = Vec::with_capacity(iterations);
        let mut module_samples: BTreeMap<String, Vec<(u64, u64)>> = BTreeMap::new();

        for iteration in 0..iterations {
            let mut runtime_config = valid_config(&target, &capabilities);
            runtime_config.host_abi = manifest["hostAbi"].as_u64().unwrap() as u32;
            runtime_config.raster_density = 2;
            runtime_config.physical_width = 480;
            runtime_config.physical_height = 480;
            let runtime = pod_runtime_create(&runtime_config);
            assert!(!runtime.is_null(), "runtime create: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
            assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), OK,
                "pak load: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
            assert_eq!(pod_runtime_validate_package(runtime, manifest_c.as_ptr()), OK,
                "manifest: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));

            if let Some(bytecode) = &bytecode {
                let began = Instant::now();
                assert_eq!(pod_runtime_eval_bundle_bytecode(runtime, bytecode.as_ptr(), bytecode.len()), OK,
                    "bytecode eval: {}", cstr(pod_runtime_last_error()).unwrap_or("unknown"));
                let elapsed = began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
                compile_samples.push(0);
                execute_samples.push(elapsed);
                let stats = unsafe { &*runtime }.eval_stats;
                println!("K7_ITER {} bytes={} total_ms={:.3} hash_ms={:.3} mount_ms={:.3} read_object_ms={:.3} execute_ms={:.3} drain_ms={:.3} finish_ms={:.3}",
                    iteration + 1, stats.bytecode_bytes, elapsed as f64 / 1000.0,
                    stats.hash_micros as f64 / 1000.0, stats.mount_micros as f64 / 1000.0,
                    stats.read_object_micros as f64 / 1000.0, stats.execute_micros as f64 / 1000.0,
                    stats.drain_jobs_micros as f64 / 1000.0, stats.finish_micros as f64 / 1000.0);
                pod_runtime_destroy(runtime);
                continue;
            }

            let runtime_ref = unsafe { &mut *runtime };
            runtime_ref.surface.mount(&runtime_ref.guest).expect("mount ui surface");
            pocket_fs::mount(&runtime_ref.guest, runtime_ref.fs.clone()).expect("mount fs");
            runtime_ref.net.mount(&runtime_ref.guest).expect("mount net");
            mount_pod(&runtime_ref.guest, runtime_ref.bridge.clone(), runtime_ref.kv.clone(),
                runtime_ref.surface.clone(), runtime_ref.guest_files_root.clone()).expect("mount pod");
            runtime_ref.mounted = true;

            let profile = Rc::new(RefCell::new(ModuleProfileState::default()));
            let start_profile = profile.clone();
            let end_profile = profile.clone();
            let (compile_micros, execute_started) = runtime_ref.guest.with(|ctx| {
                ctx.globals().set("__podProfileStart", Function::new(ctx.clone(), move |label: String| {
                    start_profile.borrow_mut().stack.push((label, Instant::now(), std::time::Duration::ZERO));
                }).unwrap()).unwrap();
                ctx.globals().set("__podProfileEnd", Function::new(ctx.clone(), move |label: String| {
                    let ended = Instant::now();
                    let mut state = end_profile.borrow_mut();
                    let Some((opened, began, child)) = state.stack.pop() else {
                        state.errors.push(format!("end without start: {label}"));
                        return;
                    };
                    if opened != label {
                        state.errors.push(format!("module stack mismatch: opened {opened}, ended {label}"));
                    }
                    let inclusive = ended.duration_since(began);
                    let exclusive = inclusive.saturating_sub(child);
                    state.samples.entry(label).or_default().push((
                        exclusive.as_micros().min(u128::from(u64::MAX)) as u64,
                        inclusive.as_micros().min(u128::from(u64::MAX)) as u64,
                    ));
                    if let Some(parent) = state.stack.last_mut() { parent.2 += inclusive; }
                }).unwrap()).unwrap();

                let source_c = CString::new(source.clone()).expect("bundle contains NUL");
                let label = c"main.js";
                let compile_started = Instant::now();
                let compiled = unsafe { raw::JS_Eval(
                    ctx.as_raw().as_ptr(), source_c.as_ptr(), source.len() as _, label.as_ptr(),
                    (raw::JS_EVAL_TYPE_GLOBAL | raw::JS_EVAL_FLAG_STRICT | raw::JS_EVAL_FLAG_COMPILE_ONLY) as i32,
                ) };
                let compile_micros = compile_started.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
                if unsafe { raw::JS_IsException(compiled) } {
                    panic!("QuickJS compile failed: {:?}", ctx.catch());
                }
                let execute_started = Instant::now();
                let result = unsafe { raw::JS_EvalFunction(ctx.as_raw().as_ptr(), compiled) };
                if unsafe { raw::JS_IsException(result) } {
                    panic!("QuickJS execute failed: {:?}", ctx.catch());
                }
                unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), result); }
                (compile_micros, execute_started)
            });
            runtime_ref.guest.drain_jobs();
            let execute_micros = execute_started.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
            assert!(runtime_ref.guest.has_frame(), "bundle installed no global frame()");
            let profile = profile.borrow();
            assert!(profile.stack.is_empty(), "unterminated module spans: {:?}", profile.stack.iter().map(|span| &span.0).collect::<Vec<_>>());
            assert!(profile.errors.is_empty(), "module profile errors: {:?}", profile.errors);
            for (label, samples) in &profile.samples {
                let exclusive = samples.iter().map(|sample| sample.0).sum();
                let inclusive = samples.iter().map(|sample| sample.1).sum();
                module_samples.entry(label.clone()).or_default().push((exclusive, inclusive));
            }
            compile_samples.push(compile_micros);
            execute_samples.push(execute_micros);
            println!("K5_ITER {} compile_ms={:.3} execute_ms={:.3} modules={}",
                iteration + 1, compile_micros as f64 / 1000.0, execute_micros as f64 / 1000.0, profile.samples.len());
            drop(profile);
            pod_runtime_destroy(runtime);
        }

        fn median(values: &mut [u64]) -> u64 {
            values.sort_unstable();
            values[values.len() / 2]
        }
        let compile_median = median(&mut compile_samples);
        let execute_median = median(&mut execute_samples);
        println!("K5_SUMMARY bundle_bytes={} iterations={} compile_median_ms={:.3} execute_median_ms={:.3} total_median_ms={:.3}",
            source.len(), iterations, compile_median as f64 / 1000.0, execute_median as f64 / 1000.0,
            (compile_median + execute_median) as f64 / 1000.0);

        let mut rows: Vec<_> = module_samples.into_iter().map(|(label, samples)| {
            let mut exclusive: Vec<_> = samples.iter().map(|sample| sample.0).collect();
            let mut inclusive: Vec<_> = samples.iter().map(|sample| sample.1).collect();
            (label, median(&mut exclusive), median(&mut inclusive), samples.len())
        }).collect();
        rows.sort_by_key(|row| std::cmp::Reverse(row.1));
        if expect_modules { assert!(rows.len() >= 30, "expected at least 30 instrumented modules, got {}", rows.len()); }
        println!("K5_MODULE_TOP count={} (median exclusive/inclusive milliseconds)", rows.len());
        for (rank, (label, exclusive, inclusive, count)) in rows.iter().take(30).enumerate() {
            println!("K5_MODULE {:02} exclusive_ms={:.3} inclusive_ms={:.3} runs={} {}",
                rank + 1, *exclusive as f64 / 1000.0, *inclusive as f64 / 1000.0, count, label);
        }
    }

    #[test]
    fn decoded_rgba_fast_path_reads_only_bounded_decoder_outputs() {
        let root = std::env::temp_dir().join(format!("podjs-image-read-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir_all(&root).unwrap();
        let valid = root.join("decoded-123.rgba");
        std::fs::write(&valid, [1, 2, 3, 4]).unwrap();
        assert_eq!(read_decoded_rgba(&root, "images/decoded-123.rgba"), Some(vec![1, 2, 3, 4]));
        assert_eq!(read_decoded_rgba(&root, "../decoded-123.rgba"), None);
        assert_eq!(read_decoded_rgba(&root, "images/decoded-123.rgba/../../secret"), None);
        assert_eq!(read_decoded_rgba(&root, "images/decoded-abc.rgba"), None);
        #[cfg(unix)] {
            let link = root.join("decoded-124.rgba");
            std::os::unix::fs::symlink(&valid, &link).unwrap();
            assert_eq!(read_decoded_rgba(&root, "images/decoded-124.rgba"), None);
        }
        std::fs::write(&valid, vec![0; 512 * 512 * 4 + 4]).unwrap();
        assert_eq!(read_decoded_rgba(&root, "images/decoded-123.rgba"), None);
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn texture_file_upload_is_scoped_validated_consuming_and_releasable() {
        let root = std::env::temp_dir().join(format!("podjs-texture-file-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        let text = root.join("text");
        let images = root.join("images");
        std::fs::create_dir_all(&text).unwrap();
        std::fs::create_dir_all(&images).unwrap();
        let surface = UiSurface::new((32.0, 32.0));

        let valid = text.join("line-valid_1.rgba");
        std::fs::write(&valid, [1u8; 16]).unwrap();
        let handle = upload_texture_file(&surface, &root, "text/line-valid_1.rgba", 2, 2, spec::psm::PSM_8888 as i32);
        assert!(handle >= 0);
        assert!(!valid.exists());
        assert_eq!(surface.with_ui(|ui| ui.texture(handle).map(|texture| texture.pixels.to_vec())), Some(vec![1; 16]));
        surface.with_ui(|ui| ui.free_texture(handle));
        assert!(surface.with_ui(|ui| ui.texture(handle).is_none()));

        let bad_size = images.join("decoded-size.rgba");
        std::fs::write(&bad_size, [2u8; 16]).unwrap();
        assert_eq!(upload_texture_file(&surface, &root, "images/decoded-size.rgba", 3, 2, spec::psm::PSM_8888 as i32), -1);
        assert!(!bad_size.exists());

        let bad_length = text.join("line-length.rgba");
        std::fs::write(&bad_length, [3u8; 12]).unwrap();
        assert_eq!(upload_texture_file(&surface, &root, "text/line-length.rgba", 2, 2, spec::psm::PSM_8888 as i32), -1);
        assert!(!bad_length.exists());

        let bad_format = images.join("decoded-format.rgba");
        std::fs::write(&bad_format, [4u8; 16]).unwrap();
        assert_eq!(upload_texture_file(&surface, &root, "images/decoded-format.rgba", 2, 2, spec::psm::PSM_5650 as i32), -1);
        assert!(!bad_format.exists());

        let rejected = root.join("secret.rgba");
        std::fs::write(&rejected, [5u8; 16]).unwrap();
        assert_eq!(upload_texture_file(&surface, &root, "../secret.rgba", 2, 2, spec::psm::PSM_8888 as i32), -1);
        assert!(rejected.exists());
        assert_eq!(upload_texture_file(&surface, &root, "text/not-a-line.rgba", 2, 2, spec::psm::PSM_8888 as i32), -1);

        #[cfg(unix)] {
            let target = root.join("target.rgba");
            let link = text.join("line-link.rgba");
            std::fs::write(&target, [6u8; 16]).unwrap();
            std::os::unix::fs::symlink(&target, &link).unwrap();
            assert_eq!(upload_texture_file(&surface, &root, "text/line-link.rgba", 2, 2, spec::psm::PSM_8888 as i32), -1);
            assert!(!link.exists());
            assert!(target.exists());
        }
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn mounted_guest_can_upload_texture_file_without_js_bytes() {
        let root = std::env::temp_dir().join(format!(
            "podjs-texture-file-eval-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let text = root.join("files/text");
        std::fs::create_dir_all(&text).unwrap();
        std::fs::create_dir_all(root.join("tmp")).unwrap();
        let texture = text.join("line-mounted_1.rgba");
        let pixels = [
            1u8, 2, 3, 255, 4, 5, 6, 255, 7, 8, 9, 255, 10, 11, 12, 255,
        ];
        std::fs::write(&texture, pixels).unwrap();

        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let data_dir = CString::new(root.to_string_lossy().as_bytes()).unwrap();
        let mut runtime_config = valid_config(&target, &caps);
        runtime_config.data_dir = data_dir.as_ptr();
        let runtime = pod_runtime_create(&runtime_config);
        assert!(!runtime.is_null());
        let source = br#"
            globalThis.frame = () => {};
            const handle = pod.uploadTextureFile('text/line-mounted_1.rgba', 2, 2, 3);
            if (!Number.isInteger(handle) || handle < 0) throw new Error('native file upload failed');
            pod.emit(JSON.stringify({handle}));
        "#;
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(
            pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()),
            OK,
            "{}",
            unsafe { CStr::from_ptr(pod_runtime_last_error()) }.to_string_lossy()
        );
        assert!(!texture.exists());
        let effect: Value =
            serde_json::from_str(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
        let handle = effect["handle"].as_i64().unwrap() as i32;
        assert_eq!(
            unsafe { &*runtime }
                .surface
                .with_ui(|ui| ui.texture(handle).map(|value| value.pixels.to_vec())),
            Some(pixels.to_vec())
        );
        assert_eq!(pod_runtime_free_texture(runtime, handle), OK);
        assert!(unsafe { &*runtime }
            .surface
            .with_ui(|ui| ui.texture(handle).is_none()));
        pod_runtime_destroy(runtime);
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn external_event_admission_is_bounded_and_retry_preserves_fifo() {
        let target = CString::new("watchos-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let event = CString::new("{\"t\":\"test\"}").unwrap();
        for _ in 0..256 { assert_eq!(pod_runtime_post_event(runtime, event.as_ptr()), OK); }
        let next = CString::new("{\"t\":\"next\"}").unwrap();
        assert_eq!(pod_runtime_post_event(runtime, next.as_ptr()), ERR_STATE);
        let bridge = unsafe { &*runtime }.bridge.clone();
        assert_eq!(bridge.borrow().events.len(), 256);
        assert_eq!(bridge.borrow_mut().events.pop_front().unwrap(), event.to_str().unwrap());
        assert_eq!(pod_runtime_post_event(runtime, next.as_ptr()), OK);
        assert_eq!(bridge.borrow().events.back().unwrap(), next.to_str().unwrap());
        bridge.borrow_mut().events.clear();
        let large = CString::new(format!("{{\"x\":\"{}\"}}", "x".repeat(1024 * 1024 - 8))).unwrap();
        assert_eq!(large.as_bytes().len(), 1024 * 1024);
        for _ in 0..4 { assert_eq!(pod_runtime_post_event(runtime, large.as_ptr()), OK); }
        assert_eq!(pod_runtime_post_event(runtime, event.as_ptr()), ERR_STATE);
        let oversize = CString::new(format!("{{\"x\":\"{}\"}}", "x".repeat(1024 * 1024))).unwrap();
        assert_eq!(pod_runtime_post_event(runtime, oversize.as_ptr()), ERR_ARGUMENT);
        assert_eq!(bridge.borrow().events.len(), 4);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn capability_authority_requires_validated_mounted_guest() {
        let target = CString::new("watchos-watch").unwrap();
        let caps = CString::new("[\"companion.sync.file\"]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let name = CString::new("companion.sync.file").unwrap();
        assert!(!pod_runtime_has_capability(std::ptr::null_mut(), name.as_ptr()));
        assert!(!pod_runtime_has_capability(runtime, name.as_ptr()));
        let source = b"globalThis.frame = function() {}";
        validate(runtime, "watchos-watch", b"pak", source, &["companion.sync.file"]);
        assert!(!pod_runtime_has_capability(runtime, name.as_ptr()));
        assert_eq!(pod_runtime_validate_package(runtime, std::ptr::null()), ERR_ARGUMENT);
        assert!(!pod_runtime_has_capability(runtime, name.as_ptr()));
        validate(runtime, "watchos-watch", b"pak", source, &["companion.sync.file"]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        assert!(pod_runtime_has_capability(runtime, name.as_ptr()));
        assert!(!pod_runtime_has_capability(runtime, std::ptr::null()));
        let other = CString::new("companion.sync.message").unwrap();
        assert!(!pod_runtime_has_capability(runtime, other.as_ptr()));
        assert_eq!(pod_runtime_validate_package(runtime, std::ptr::null()), ERR_STATE);
        assert!(pod_runtime_has_capability(runtime, name.as_ptr()));
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn rgba_sampling_is_opt_in_validated_and_preserved_by_pixel_updates() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let first = [1, 2, 3, 255, 4, 5, 6, 128];
        let next = [9, 8, 7, 64, 6, 5, 4, 255];
        let legacy = pod_runtime_upload_rgba(runtime, first.as_ptr(), first.len(), 2, 1);
        let nearest = pod_runtime_upload_rgba_sampled(runtime, first.as_ptr(), first.len(), 2, 1, 0);
        let linear = pod_runtime_upload_rgba_sampled(runtime, first.as_ptr(), first.len(), 2, 1, 1);
        assert!(legacy >= 0 && nearest >= 0 && linear >= 0);
        assert_ne!(legacy, nearest);
        assert_ne!(nearest, linear);
        let read = |handle| {
            let mut view = std::mem::MaybeUninit::<PodTextureView>::uninit();
            assert_eq!(pod_runtime_texture_for_handle(runtime, handle, view.as_mut_ptr()), OK);
            unsafe { view.assume_init() }
        };
        for (handle, expected_linear) in [(legacy, 0), (nearest, 0), (linear, 1)] {
            let before = read(handle);
            assert_eq!(before.handle, handle);
            assert_eq!((before.width, before.height, before.pixel_format), (2, 1, 3));
            assert_eq!(before.linear, expected_linear);
            assert_eq!(unsafe { slice::from_raw_parts(before.pixels, before.byte_length) }, first);
            assert_eq!(pod_runtime_update_rgba(runtime, handle, next.as_ptr(), next.len()), OK);
            let after = read(handle);
            assert_eq!(after.handle, handle);
            assert_eq!(after.linear, expected_linear);
            assert!(after.revision > before.revision);
            assert_eq!(unsafe { slice::from_raw_parts(after.pixels, after.byte_length) }, next);
        }
        // Public sampling modes are not the engine's texture flag bitmask.
        for invalid in [-1, 2, i32::MAX] {
            assert_eq!(pod_runtime_upload_rgba_sampled(runtime, first.as_ptr(), first.len(), 2, 1, invalid), ERR_ARGUMENT);
        }
        assert_eq!(pod_runtime_upload_rgba_sampled(runtime, std::ptr::null(), first.len(), 2, 1, 1), ERR_ARGUMENT);
        assert_eq!(pod_runtime_upload_rgba_sampled(runtime, first.as_ptr(), first.len() - 1, 2, 1, 1), ERR_ARGUMENT);
        assert_eq!(read(legacy).linear, 0);
        assert_eq!(read(linear).linear, 1);
        for handle in [legacy, nearest, linear] {
            assert_eq!(pod_runtime_free_texture(runtime, handle), OK);
        }
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn concurrent_rgba_frame_updates_invalidate_the_draw_snapshot() {
        let target=CString::new("android-watch").unwrap();let caps=CString::new("[]").unwrap();
        let runtime=pod_runtime_create(&valid_config(&target,&caps));assert!(!runtime.is_null());
        let source=b"globalThis.frame = function() {}";
        validate(runtime,"android-watch",b"pak",source,&[]);
        assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
        let first=[1,2,3,255];let next=[9,8,7,255];
        let a=pod_runtime_upload_rgba(runtime,first.as_ptr(),4,1,1);let b=pod_runtime_upload_rgba(runtime,first.as_ptr(),4,1,1);assert!(a>=0&&b>=0);
        let mut draw=PodDrawList{words:std::ptr::null(),word_count:0,content_hash:0,frame_number:0,changed:0};
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);let before=draw.content_hash;
        assert_eq!(pod_runtime_update_rgba(runtime,a,next.as_ptr(),4),OK);assert_eq!(pod_runtime_update_rgba(runtime,b,next.as_ptr(),4),OK);
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);assert_eq!(draw.changed,1);assert_ne!(draw.content_hash,before);
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);assert_eq!(draw.changed,0);
        assert_eq!(pod_runtime_update_rgba(runtime,a,next.as_ptr(),3),ERR_ARGUMENT);
        assert_eq!(pod_runtime_free_texture(runtime,a),OK);assert_eq!(pod_runtime_update_rgba(runtime,a,next.as_ptr(),4),ERR_ARGUMENT);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn texture_handle_api_resolves_live_and_rejects_stale_handles() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let handle = unsafe {
            (&mut *runtime)
                .surface
                .with_ui(|ui| ui.upload_texture(&[1, 2, 3, 4], 1, 1, 0))
        };
        let mut view = PodTextureView {
            handle: 0,
            revision: 0,
            pixels: std::ptr::null(),
            byte_length: 0,
            width: 0,
            height: 0,
            pixel_format: 0,
            palette: std::ptr::null(),
            palette_length: 0,
            linear: 0,
        };
        assert_eq!(
            pod_runtime_texture_for_handle(runtime, handle, &mut view),
            OK
        );
        assert_eq!(view.handle, handle);
        assert!(view.byte_length > 0);
        unsafe {
            (&mut *runtime)
                .surface
                .with_ui(|ui| ui.free_texture(handle));
        }
        assert_eq!(
            pod_runtime_texture_for_handle(runtime, handle, &mut view),
            IDLE
        );
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn accessibility_actions_queue_only_valid_live_snapshot_targets() {
        use crate::accessibility_ffi::*;
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert_eq!(pod_runtime_accessibility_action(runtime, 1, 0, 1), ERR_STATE);
        let source = format!("globalThis.n=ui.createNode(0);ui.setProp(n,{},80);ui.setProp(n,{},20);ui.setAccessibility(n,'Go',1,null,null,512,0);ui.insertBefore(1,n,0);globalThis.frame=()=>{{}};", spec::prop::WIDTH, spec::prop::HEIGHT);
        validate(runtime, "android-watch", b"pak", source.as_bytes(), &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime, 1), OK);
        let mut draw = PodDrawList { words: std::ptr::null(), word_count: 0, content_hash: 0, frame_number: 0, changed: 0 };
        assert_eq!(pod_runtime_snapshot(runtime, &mut draw), OK);
        let (id, hash) = unsafe { &mut *runtime }.surface.with_ui(|ui| {
            let snapshot = ui.current_accessibility();
            (snapshot.nodes[0].id, snapshot.content_hash)
        });
        let mut tree = PodAccessibilityTree { node_count: 0, content_hash: 0, frame_number: 0 };
        assert_eq!(pod_runtime_accessibility_tree(runtime, &mut tree), OK);
        assert_eq!(tree.node_count, 1); assert_eq!(tree.content_hash, hash);
        assert_eq!(pod_runtime_accessibility_tree(runtime, std::ptr::null_mut()), ERR_ARGUMENT);
        let mut node = std::mem::MaybeUninit::<PodAccessibilityNode>::uninit();
        assert_eq!(pod_runtime_accessibility_node(runtime, 0, node.as_mut_ptr()), OK);
        let mut node = unsafe { node.assume_init() };
        assert_eq!((node.id, node.parent_id, node.role, node.actions), (id, 0, 1, 1));
        assert_eq!((node.left, node.top, node.right, node.bottom), (0, 0, 80, 20));
        assert_eq!(unsafe { slice::from_raw_parts(node.label.bytes, node.label.byte_length) }, b"Go");
        assert!(node.value.bytes.is_null()); assert!(node.hint.bytes.is_null());
        assert_eq!(pod_runtime_accessibility_node(runtime, 1, &mut node), ERR_ARGUMENT);
        assert_eq!(node.id, id); // Failed reads do not overwrite a caller's output.
        assert_eq!(pod_runtime_accessibility_action(runtime, id, hash, 1), OK);
        let event = unsafe { &mut *runtime }.bridge.borrow_mut().events.pop_back().unwrap();
        assert_eq!(serde_json::from_str::<Value>(&event).unwrap(), json!({"t":"accessibility.action","nodeId":id,"action":"activate"}));
        assert_eq!(pod_runtime_accessibility_action(runtime, id, hash ^ 1, 1), ERR_STATE);
        assert_eq!(pod_runtime_accessibility_action(runtime, id, hash, 2), ERR_STATE);
        assert_eq!(pod_runtime_accessibility_action(runtime, id, hash, 3), ERR_ARGUMENT);
        unsafe { &mut *runtime }.surface.with_ui(|ui| {
            let mut props = ui.accessibility_of(id).unwrap().clone();
            props.value = Some(String::new()); props.label = Some("New😀".into());
            ui.set_accessibility(id, props);
        });
        assert_eq!(pod_runtime_accessibility_node(runtime, 0, &mut node), OK);
        assert!(node.value.bytes.is_null()); // Uncommitted metadata is still hidden.
        assert_eq!(pod_runtime_snapshot(runtime, &mut draw), OK);
        assert_eq!(pod_runtime_accessibility_node(runtime, 0, &mut node), OK);
        assert!(!node.value.bytes.is_null()); assert_eq!(node.value.byte_length, 0);
        assert_eq!(unsafe { slice::from_raw_parts(node.label.bytes, node.label.byte_length) }, "New😀".as_bytes());
        let mut json_view = PodAccessibilitySnapshot { json: std::ptr::null(), byte_length: 0, content_hash: 0, frame_number: 0, changed: 0 };
        assert_eq!(pod_runtime_accessibility_snapshot(runtime, &mut json_view), OK);
        assert_eq!(json_view.changed, 1); // Typed reads did not consume JSON's cursor.
        assert_eq!(pod_runtime_accessibility_tree(runtime, &mut tree), OK);
        assert_eq!(tree.content_hash, json_view.content_hash);
        unsafe { &mut *runtime }.surface.with_ui(|ui| { ui.destroy_node(id); });
        assert_eq!(pod_runtime_accessibility_action(runtime, id, hash, 1), ERR_STATE);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn accessibility_abi_reads_only_committed_content_and_suppresses_unchanged_exports() {
        use crate::accessibility_ffi::*;
        let target=CString::new("android-watch").unwrap();let caps=CString::new("[]").unwrap();
        let runtime=pod_runtime_create(&valid_config(&target,&caps));assert!(!runtime.is_null());
        let mut semantics=PodAccessibilitySnapshot{json:std::ptr::null(),byte_length:0,content_hash:0,frame_number:0,changed:0};
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),ERR_STATE);
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime,2),ERR_ARGUMENT);
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime,1),OK);
        let source=format!("globalThis.n=ui.createNode(1);ui.setText(n,'Read😀');ui.setProp(n,{},80);ui.setProp(n,{},20);ui.insertBefore(1,n,0);globalThis.frame=()=>ui.setText(n,'Changed');",spec::prop::WIDTH,spec::prop::HEIGHT);
        validate(runtime,"android-watch",b"pak",source.as_bytes(),&[]);
        assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
        let mut draw=PodDrawList{words:std::ptr::null(),word_count:0,content_hash:0,frame_number:0,changed:0};
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,1);
        let read=|view:&PodAccessibilitySnapshot|->Value {serde_json::from_slice(unsafe{slice::from_raw_parts(view.json,view.byte_length)}).unwrap()};
        let first=read(&semantics);assert_eq!(first["schema"],1);assert_eq!(first["nodes"][0]["role"],"text");assert_eq!(first["nodes"][0]["label"],"Read😀");
        assert_eq!(first["nodes"][0]["bounds"],json!({"left":0,"top":0,"right":80,"bottom":20}));
        let hash=semantics.content_hash;let pointer=semantics.json;let committed_frame=semantics.frame_number;
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,0);assert_eq!(semantics.json,pointer);
        assert_eq!(pod_runtime_frame(runtime,std::ptr::null()),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.content_hash,hash);assert_eq!(read(&semantics),first);assert_eq!(semantics.frame_number,committed_frame);
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,1);assert_ne!(semantics.content_hash,hash);
        assert_eq!(read(&semantics)["nodes"][0]["label"],"Changed");
        assert!(semantics.frame_number>committed_frame);
        let second_frame=semantics.frame_number;
        assert_eq!(pod_runtime_frame(runtime,std::ptr::null()),OK);
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,0);assert!(semantics.frame_number>second_frame);
        // A host can clear its projection while disabled without querying the
        // empty snapshot; re-enable must still republish identical content.
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime,0),OK);
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime,1),OK);
        assert_eq!(pod_runtime_snapshot(runtime,&mut draw),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,1);
        assert_eq!(read(&semantics)["nodes"][0]["label"],"Changed");
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime,0),OK);
        assert_eq!(pod_runtime_accessibility_snapshot(runtime,&mut semantics),OK);assert_eq!(semantics.changed,1);assert_eq!(read(&semantics)["nodes"],json!([]));
        pod_runtime_destroy(runtime);
    }

    fn validate(runtime: *mut PodRuntime, target: &str, pak: &[u8], bundle: &[u8], caps: &[&str]) {
        assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), OK);
        let manifest = CString::new(
            json!({
                "target": target, "hostAbi": 1,
                "pocketjsRevision": option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
                "pakHash": format!("{:016x}", hash_bytes(pak)),
                "bundleHash": format!("{:016x}", hash_bytes(bundle)),
                "capabilities": caps,
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), OK);
    }

    #[test]
    fn native_base64_returns_owned_typed_arrays_and_preserves_fallback_boundary() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let bytes: Vec<u8> = (0..262144).map(|i| i as u8).collect();
        let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
        let source = format!(r#"
            globalThis.frame=()=>{{}};
            const bytes=pod.decodeBase64({encoded:?});
            if(!(bytes instanceof Uint8Array)||bytes.length!==262144)throw new Error('typed byte result');
            for(let i=0;i<bytes.length;i++)if(bytes[i]!==i%256)throw new Error('byte corruption');
            for(const text of ['', 'AA==', 'AA', 'AQID', '/w==']){{
                const value=pod.decodeBase64(text);if(!(value instanceof Uint8Array))throw new Error('valid input rejected');
            }}
            if(pod.decodeBase64('invalid!')!=null||pod.decodeBase64('AB==')!=null)throw new Error('fallback boundary');
            bytes[0]=99;if(pod.decodeBase64({encoded:?})[0]!==0)throw new Error('shared buffer');
        "#);
        validate(runtime, "android-watch", b"pak", source.as_bytes(), &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK, "{}", unsafe { CStr::from_ptr(pod_runtime_last_error()) }.to_string_lossy());
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn native_base64_encoder_preserves_views_padding_and_input() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let mut source = String::from("globalThis.frame=()=>{};const backing=new Uint8Array(262160);for(let i=0;i<backing.length;i++)backing[i]=i&255;\n");
        for offset in [0, 1, 7] {
            for length in [0, 1, 2, 3, 255, 256, 65536, 262144] {
                let bytes: Vec<u8> = (offset..offset+length).map(|i| i as u8).collect();
                let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
                source.push_str(&format!("if(pod.encodeBase64(backing.subarray({offset},{}))!=={encoded:?})throw new Error('encode view {offset}/{length}');\n",offset+length));
            }
        }
        source.push_str("for(let i=0;i<backing.length;i++)if(backing[i]!==(i&255))throw new Error('mutated input');");
        validate(runtime, "android-watch", b"pak", source.as_bytes(), &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK, "{}", unsafe { CStr::from_ptr(pod_runtime_last_error()) }.to_string_lossy());
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn guest_font_metrics_read_the_loaded_atlas_without_density_scaling() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        let mut atlas = Vec::new();
        atlas.extend_from_slice(&spec::font_atlas::MAGIC.to_le_bytes());
        atlas.extend_from_slice(&spec::font_atlas::VERSION.to_le_bytes());
        atlas.extend_from_slice(&1u16.to_le_bytes());
        atlas.extend_from_slice(&[8, 10, 7, 12, 0, 0, 2, 0]);
        atlas.extend_from_slice(&('A' as u32).to_le_bytes());
        atlas.extend_from_slice(&[0, 0, 8, 0]);
        atlas.extend_from_slice(&vec![255u8; 8 * 10 * 4]);
        assert!(unsafe { &*runtime }.surface.with_ui(|ui| ui.load_font_atlas(&atlas)));
        let source = br#"
            globalThis.frame=()=>{};
            const metrics=JSON.parse(pod.fontMetrics(0));
            if(metrics.cellWidth!==8||metrics.cellHeight!==10||metrics.baseline!==7||metrics.lineHeight!==12||metrics.rasterDensity!==2)throw new Error('atlas metrics changed units');
            for(const slot of [1,-1,256,0.5,NaN])if(pod.fontMetrics(slot)!=null)throw new Error('invalid or missing slot');
        "#;
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK, "{}", unsafe { CStr::from_ptr(pod_runtime_last_error()) }.to_string_lossy());
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn rejects_unknown_target_and_host_abi() {
        let unknown = CString::new("phone").unwrap();
        let caps = CString::new("[]").unwrap();
        assert!(pod_runtime_create(&valid_config(&unknown, &caps)).is_null());
        let target = CString::new("android-watch").unwrap();
        let mut bad = valid_config(&target, &caps);
        bad.host_abi = 9;
        assert!(pod_runtime_create(&bad).is_null());
    }

    #[test]
    fn abi_one_two_three_hosts_boot_supported_guests_without_bypassing_validation() {
        for target_name in ["android-watch", "wearos-watch", "watchos-watch", "harmonyos-watch"] {
            for (host_abi, package_abi, accepted) in [(3,1,true),(3,2,true),(3,3,true),(2,1,true),(2,2,true),(1,1,true),(1,2,false),(2,0,false),(2,3,false),(3,4,false)] {
                let target = CString::new(target_name).unwrap(); let caps = CString::new("[]").unwrap();
                let mut config = valid_config(&target,&caps); config.host_abi = host_abi;
                let runtime = pod_runtime_create(&config); assert!(!runtime.is_null());
                let source = format!("if (ui.__hostAbi !== {package_abi}) throw new Error('wrong negotiated ABI'); globalThis.frame = function() {{}};");
                let pak = b"pak";
                assert_eq!(pod_runtime_load_pak(runtime,pak.as_ptr(),pak.len()),OK);
                let mut manifest = json!({"target":target_name,"hostAbi":package_abi,
                    "pocketjsRevision":option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
                    "pakHash":format!("{:016x}",hash_bytes(pak)),"bundleHash":format!("{:016x}",hash_bytes(source.as_bytes())),"capabilities":[]});
                // ABI compatibility must not allow unsupported capability declarations.
                manifest["capabilities"] = json!(["companion.sync.message"]);
                let invalid = CString::new(manifest.to_string()).unwrap();
                assert_eq!(pod_runtime_validate_package(runtime,invalid.as_ptr()),ERR_STATE);
                manifest["capabilities"] = json!([]);
                let encoded = CString::new(manifest.to_string()).unwrap();
                assert_eq!(pod_runtime_validate_package(runtime,encoded.as_ptr()),if accepted {OK} else {ERR_STATE});
                if accepted {
                    assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
                    let receipt: Value = serde_json::from_str(cstr(pod_runtime_receipt(runtime)).unwrap()).unwrap();
                    assert_eq!(receipt["hostAbi"],host_abi); assert_eq!(receipt["packageAbi"],package_abi);
                    assert_eq!(receipt["runtimeAbi"],ABI_VERSION);
                } else {
                    assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),ERR_STATE);
                }
                pod_runtime_destroy(runtime);
            }
        }
    }

    #[test]
    fn rejects_package_mismatch_before_javascript() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert_eq!(pod_runtime_load_pak(runtime, b"pak".as_ptr(), 3), OK);
        let manifest = CString::new(
            json!({
                "target": "wearos-watch", "hostAbi": 1,
                "pocketjsRevision": "0a90bf904d835210e52a11ed275a86d0040b5086",
                "pakHash": format!("{:016x}", hash_bytes(b"pak")),
                "bundleHash": format!("{:016x}", hash_bytes(b"js")), "capabilities": [],
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(
            pod_runtime_validate_package(runtime, manifest.as_ptr()),
            ERR_STATE
        );
        assert_eq!(
            pod_runtime_eval_bundle(runtime, b"js".as_ptr(), 2, std::ptr::null()),
            ERR_STATE
        );
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn malformed_capabilities_and_failed_revalidation_revoke_boot_permission() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target,&caps));
        let source = b"globalThis.frame = function() {}";
        let valid = json!({"target":"android-watch","hostAbi":1,
            "pocketjsRevision":option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
            "pakHash":format!("{:016x}",hash_bytes(b"pak")),"bundleHash":format!("{:016x}",hash_bytes(source)),"capabilities":[]});
        assert_eq!(pod_runtime_load_pak(runtime,b"pak".as_ptr(),3),OK);
        let manifest = CString::new(valid.to_string()).unwrap();
        for invalid_capabilities in [json!([null]),json!([17]),json!([{}]),json!([true]),json!("input.touch")] {
            assert_eq!(pod_runtime_validate_package(runtime,manifest.as_ptr()),OK);
            let mut invalid = valid.clone(); invalid["capabilities"] = invalid_capabilities;
            let invalid = CString::new(invalid.to_string()).unwrap();
            assert_eq!(pod_runtime_validate_package(runtime,invalid.as_ptr()),ERR_STATE);
            assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),ERR_STATE);
        }
        assert_eq!(pod_runtime_validate_package(runtime,manifest.as_ptr()),OK);
        assert_eq!(pod_runtime_validate_package(runtime,std::ptr::null()),ERR_ARGUMENT);
        assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),ERR_STATE);
        assert_eq!(pod_runtime_validate_package(runtime,manifest.as_ptr()),OK);
        let malformed = CString::new("{").unwrap();
        assert_eq!(pod_runtime_validate_package(runtime,malformed.as_ptr()),ERR_ARGUMENT);
        assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),ERR_STATE);
        assert_eq!(pod_runtime_validate_package(runtime,manifest.as_ptr()),OK);
        assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn bundle_is_one_shot_and_background_suspends_ticks() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        let source = b"globalThis.frame = function() {}";
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(
            pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()),
            0
        );
        assert_eq!(
            pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()),
            ERR_STATE
        );
        assert_eq!(pod_runtime_set_lifecycle(runtime, 2), 0);
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), IDLE);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn background_service_completion_drains_promises_without_committing_a_frame() {
        assert_eq!(pod_runtime_pump_services(std::ptr::null_mut()), ERR_ARGUMENT);
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert_eq!(pod_runtime_pump_services(runtime), ERR_STATE);
        let source = br#"
            globalThis.frames = 0;
            globalThis.frame = () => { frames++; };
            globalThis.__pocketPumpServices = () => {
                const events = JSON.parse(pod.takeEvents() || '[]');
                Promise.resolve().then(() => pod.emit(JSON.stringify({frames, events})));
            };
        "#;
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        assert_eq!(pod_runtime_set_lifecycle(runtime, 2), OK);
        let event = CString::new(r#"{"t":"service.result","id":1,"ok":true,"value":7}"#).unwrap();
        assert_eq!(pod_runtime_post_event(runtime, event.as_ptr()), OK);
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), IDLE);
        assert_eq!(pod_runtime_pump_services(runtime), OK);
        let effect: Value = serde_json::from_str(unsafe { CStr::from_ptr(pod_runtime_poll_effect(runtime)) }.to_str().unwrap()).unwrap();
        assert_eq!(effect["frames"], 1);
        assert!(effect["events"].as_array().unwrap().iter().any(|e| e["t"] == "lifecycle" && e["state"] == "background"));
        assert!(effect["events"].as_array().unwrap().iter().any(|e| e["id"] == 1 && e["value"] == 7));
        assert_eq!(runtime_mut(runtime).unwrap().frame_number, 1);
        assert_eq!(pod_runtime_set_lifecycle(runtime, 0), OK);
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        assert_eq!(runtime_mut(runtime).unwrap().frame_number, 2);
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn service_results_pump_retains_other_events_through_promise_jobs_until_frame() {
        assert_eq!(pod_runtime_pump_service_results(std::ptr::null_mut()), ERR_ARGUMENT);
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert!(!runtime.is_null());
        assert_eq!(pod_runtime_pump_service_results(runtime), ERR_STATE);
        let source = br#"
            globalThis.frames = 0;
            const readEvents = () => JSON.parse(pod.takeEvents() || '[]');
            globalThis.frame = () => {
                frames++;
                pod.emit(JSON.stringify({phase: 'frame', frames, events: readEvents()}));
            };
            globalThis.__pocketPumpServices = () => {
                const events = readEvents();
                Promise.resolve().then(() => {
                    pod.emit(JSON.stringify({phase: 'results', frames, events, jobs: readEvents()}));
                });
            };
        "#;
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        let retained = vec![
            json!({"t":"back"}),
            json!({"t":"lifecycle","state":"inactive"}),
            json!({"t":"axis","axis":0,"delta":9}),
            json!({"t":"input.scroll","vertical":1}),
            json!({"t":"accessibility.action","nodeId":1,"action":"activate"}),
            json!({"t":"touchCancel"}),
            json!({"t":"theme","theme":"dark"}),
            json!({"t":"display.state","value":{}}),
            json!({"t":"notification.action","eventId":"action"}),
            json!({"t":"future.event"}),
            json!({"t":7}),
        ];
        // Authorization status must precede its result even when no UI frame runs.
        let results = vec![
            json!({"t":"browser.auth.status","id":1,"value":{"type":"qr","kind":"login","mime":"image/png","imageBase64":"cXI="}}),
            json!({"t":"browser.auth.status","id":1,"value":{"type":"cancelled"}}),
            json!({"t":"service.result","id":1,"ok":true,"value":11}),
            json!({"t":"service.result","id":2,"ok":false,"code":"test"}),
        ];
        for (index, event) in retained.iter().enumerate() {
            let raw = CString::new(event.to_string()).unwrap();
            assert_eq!(pod_runtime_post_event(runtime, raw.as_ptr()), OK);
            if index < results.len() {
                let raw = CString::new(results[index].to_string()).unwrap();
                assert_eq!(pod_runtime_post_event(runtime, raw.as_ptr()), OK);
            }
        }
        let effect = || serde_json::from_str::<Value>(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
        assert_eq!(pod_runtime_pump_service_results(runtime), OK);
        assert_eq!(effect(), json!({"phase":"results","frames":0,"events":results,"jobs":[]}));
        assert_eq!(runtime_mut(runtime).unwrap().frame_number, 0);
        assert_eq!(pod_runtime_pump_service_results(runtime), OK);
        assert_eq!(effect(), json!({"phase":"results","frames":0,"events":[],"jobs":[]}));
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        assert_eq!(effect(), json!({"phase":"frame","frames":1,"events":retained}));
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        assert_eq!(effect(), json!({"phase":"frame","frames":2,"events":[]}));
        assert!(pod_runtime_poll_effect(runtime).is_null());
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn service_results_pump_restores_event_mode_after_guest_error() {
        for consume_before_error in [false, true] {
            let target = CString::new("android-watch").unwrap();
            let caps = CString::new("[]").unwrap();
            let runtime = pod_runtime_create(&valid_config(&target, &caps));
            let source = format!(r#"
                let failOnce = true;
                globalThis.frame = () => {{}};
                globalThis.__pocketPumpServices = () => {{
                    if (failOnce) {{
                        failOnce = false;
                        if ({consume_before_error}) pod.takeEvents();
                        throw new Error('expected service pump failure');
                    }}
                    pod.emit(pod.takeEvents() || '[]');
                }};
            "#);
            validate(runtime, "android-watch", b"pak", source.as_bytes(), &[]);
            assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
            let events = vec![
                json!({"t":"back"}),
                json!({"t":"service.result","id":1,"ok":true,"value":7}),
                json!({"t":"lifecycle","state":"inactive"}),
            ];
            for event in &events {
                let raw = CString::new(event.to_string()).unwrap();
                assert_eq!(pod_runtime_post_event(runtime, raw.as_ptr()), OK);
            }
            assert_eq!(pod_runtime_pump_service_results(runtime), ERR_GUEST);
            assert!(cstr(pod_runtime_last_error()).unwrap().contains("expected service pump failure"));
            assert_eq!(pod_runtime_pump_services(runtime), OK);
            let effect: Value = serde_json::from_str(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
            assert_eq!(effect, if consume_before_error {
                json!([events[0], events[2]])
            } else {
                json!(events)
            });
            assert_eq!(runtime_mut(runtime).unwrap().frame_number, 0);
            pod_runtime_destroy(runtime);
        }
    }

    #[test]
    fn service_results_pump_advances_dependent_requests_without_committing_draws() {
        use crate::accessibility_ffi::pod_runtime_set_accessibility_enabled;
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        assert_eq!(pod_runtime_set_accessibility_enabled(runtime, 1), OK);
        let source = format!(r#"
            const node = ui.createNode(1);
            ui.setText(node, 'Before');
            ui.setProp(node, {}, 80);
            ui.setProp(node, {}, 20);
            ui.insertBefore(1, node, 0);
            globalThis.frame = () => {{}};
            const pending = new Map();
            const request = id => new Promise(resolve => {{
                pending.set(id, resolve);
                pod.emit(JSON.stringify({{t:'service.request',id}}));
            }});
            request(1).then(() => request(2)).then(() => request(3)).then(() => {{
                ui.setText(node, 'After');
                pod.emit(JSON.stringify({{t:'chain.done'}}));
            }});
            globalThis.__pocketPumpServices = () => {{
                for (const event of JSON.parse(pod.takeEvents() || '[]')) {{
                    const resolve = pending.get(event.id);
                    if (resolve) {{ pending.delete(event.id); resolve(event.value); }}
                }}
            }};
        "#, spec::prop::WIDTH, spec::prop::HEIGHT);
        validate(runtime, "android-watch", b"pak", source.as_bytes(), &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        let mut draw = PodDrawList { words: std::ptr::null(), word_count: 0, content_hash: 0, frame_number: 0, changed: 0 };
        assert_eq!(pod_runtime_snapshot(runtime, &mut draw), OK);
        let (frame_number, draw_words, draw_hash, semantics) = {
            let state = runtime_mut(runtime).unwrap();
            (state.frame_number, state.draw_words.clone(), state.draw_hash,
                state.surface.with_ui(|ui| ui.current_accessibility().clone()))
        };
        assert_eq!(semantics.nodes[0].label, "Before");
        let effect = || serde_json::from_str::<Value>(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
        for id in 1..=3 {
            assert_eq!(effect(), json!({"t":"service.request","id":id}));
            let result = CString::new(json!({"t":"service.result","id":id,"ok":true}).to_string()).unwrap();
            assert_eq!(pod_runtime_post_event(runtime, result.as_ptr()), OK);
            assert_eq!(pod_runtime_pump_service_results(runtime), OK);
            let state = runtime_mut(runtime).unwrap();
            assert_eq!(state.frame_number, frame_number);
            assert_eq!(state.draw_words, draw_words);
            assert_eq!(state.draw_hash, draw_hash);
            state.surface.with_ui(|ui| assert_eq!(ui.current_accessibility(), &semantics));
        }
        assert_eq!(effect(), json!({"t":"chain.done"}));
        assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), OK);
        assert_eq!(pod_runtime_snapshot(runtime, &mut draw), OK);
        let state = runtime_mut(runtime).unwrap();
        assert_eq!(state.frame_number, frame_number + 1);
        state.surface.with_ui(|ui| {
            assert_ne!(ui.current_accessibility().content_hash, semantics.content_hash);
            assert_eq!(ui.current_accessibility().nodes[0].label, "After");
        });
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn service_results_pump_promotes_legacy_net_completions_without_host_events() {
        let target = CString::new("android-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        let source = br#"
            globalThis.frame = () => {};
            net.start(JSON.stringify({url:'https://example.test/a',method:'GET',headers:{},timeoutMs:30000,maxBytes:16}), new ArrayBuffer(0));
            globalThis.__pocketPumpServices = () => {
                const events = JSON.parse(net.poll() || '[]');
                for (const event of events) {
                    const body = new ArrayBuffer(event.bytes);
                    const bytes = net.take(event.h, body);
                    Promise.resolve().then(() => pod.emit(JSON.stringify({event,bytes,body:Array.from(new Uint8Array(body))})));
                }
            };
        "#;
        validate(runtime, "android-watch", b"pak", source, &[]);
        assert_eq!(pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()), OK);
        let command: Value = serde_json::from_str(cstr(pod_runtime_poll_net_command(runtime)).unwrap()).unwrap();
        let handle = command["handle"].as_i64().unwrap() as i32;
        let url = CString::new("https://example.test/a").unwrap();
        let headers = CString::new("{}").unwrap();
        assert_eq!(pod_runtime_complete_http(runtime, handle, 200, url.as_ptr(), headers.as_ptr(), b"ok".as_ptr(), 2), OK);
        assert!(runtime_mut(runtime).unwrap().bridge.borrow().events.is_empty());
        assert_eq!(pod_runtime_pump_service_results(runtime), OK);
        let effect: Value = serde_json::from_str(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
        assert_eq!(effect, json!({"event":{"t":"done","h":handle,"status":200,"url":"https://example.test/a","headers":{},"bytes":2},"bytes":2,"body":[111,107]}));
        assert_eq!(runtime_mut(runtime).unwrap().frame_number, 0);
        assert_eq!(pod_runtime_pump_service_results(runtime), OK);
        assert!(pod_runtime_poll_effect(runtime).is_null());
        pod_runtime_destroy(runtime);
    }

    #[test]
    fn axis_event_enters_only_at_frame_boundary() {
        let target = CString::new("wearos-watch").unwrap();
        let caps = CString::new("[]").unwrap();
        let runtime = pod_runtime_create(&valid_config(&target, &caps));
        let source = br#"
            globalThis.seen = 0;
            globalThis.frame = function() {
              const events = JSON.parse(pod.takeEvents() || '[]');
              for (const event of events) if (event.t === 'axis') seen += event.delta;
            };
        "#;
        validate(runtime, "wearos-watch", b"pak", source, &[]);
        assert_eq!(
            pod_runtime_eval_bundle(runtime, source.as_ptr(), source.len(), std::ptr::null()),
            0
        );
        let input = PodInputFrame {
            struct_size: std::mem::size_of::<PodInputFrame>() as u32,
            buttons: 0,
            analog: 0,
            touches: std::ptr::null(),
            touch_count: 0,
            rotary_primary_millidegrees: -1250,
            rotary_secondary_millidegrees: 0,
        };
        assert_eq!(pod_runtime_frame(runtime, &input), 0);
        let mut snapshot = PodDrawList {
            words: std::ptr::null(),
            word_count: 0,
            content_hash: 0,
            frame_number: 0,
            changed: 0,
        };
        assert_eq!(pod_runtime_snapshot(runtime, &mut snapshot), 0);
        assert_eq!(snapshot.frame_number, 1);
        let mut rgba = vec![
            0u8;
            (pod_runtime_logical_width(runtime) * pod_runtime_logical_height(runtime) * 4)
                as usize
        ];
        let mut incremental = vec![0u8; rgba.len()];
        assert_eq!(
            pod_runtime_render_rgba_incremental(
                runtime,
                1,
                incremental.as_mut_ptr(),
                incremental.len(),
            ),
            OK
        );
        assert_eq!(
            pod_runtime_render_rgba(runtime, 1, rgba.as_mut_ptr(), rgba.len()),
            OK
        );
        assert_eq!(incremental, rgba);
        assert!(rgba.chunks_exact(4).all(|pixel| pixel[3] == 255));
        let mut transparent = vec![0xff; rgba.len()];
        assert_eq!(
            pod_runtime_render_rgba_transparent_incremental(
                runtime,
                1,
                transparent.as_mut_ptr(),
                transparent.len(),
            ),
            OK
        );
        assert!(
            transparent
                .chunks_exact(4)
                .all(|pixel| pixel == [0, 0, 0, 0])
        );
        assert_eq!(
            pod_runtime_render_rgba(runtime, 0, rgba.as_mut_ptr(), rgba.len()),
            ERR_ARGUMENT
        );
        assert_eq!(
            pod_runtime_render_rgba(runtime, 1, rgba.as_mut_ptr(), rgba.len() - 1),
            ERR_ARGUMENT
        );
        assert_eq!(
            pod_runtime_render_rgba_incremental(
                runtime,
                1,
                incremental.as_mut_ptr(),
                incremental.len() - 1,
            ),
            ERR_ARGUMENT
        );
        pod_runtime_destroy(runtime);
    }
}
