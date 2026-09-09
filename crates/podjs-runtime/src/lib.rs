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
use kv::KvStore;
use std::collections::{BTreeMap, VecDeque};
use std::ffi::{CStr, CString, c_char};
use std::path::PathBuf;
use std::rc::Rc;
use std::slice;

use base64::Engine as _;
use pocket_fs::{FsModule, Storage};
use pocket_mod::Guest;
use pocket_mod::qjs::Function;
use pocket_net::{HttpRequest, HttpTransport, NetFailure, NetSurface, TransportCompletion};
use pocket_ui_surface::UiSurface;
use pocketjs_core::damage::{DEFAULT_DAMAGE_REGIONS, DamagePolicy, DamageTracker};
use pocketjs_core::spec;
use serde_json::{Value, json};

pub const ABI_VERSION: u32 = 2;
pub const MIN_ABI_VERSION: u32 = 1;
pub const DEFAULT_LOGICAL_WIDTH: u32 = 240;
pub const DEFAULT_LOGICAL_HEIGHT: u32 = 240;
const OK: i32 = 0;
const IDLE: i32 = 1;
const ERR_ARGUMENT: i32 = -1;
const ERR_STATE: i32 = -2;
const ERR_GUEST: i32 = -3;

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

#[derive(Default)]
struct PodBridge {
    events: VecDeque<String>,
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
    guest: Guest,
    surface: UiSurface,
    fs: Rc<RefCell<FsModule>>,
    net: NetSurface<HostTransport>,
    transport: HostTransport,
    bridge: Rc<RefCell<PodBridge>>,
    kv: Rc<RefCell<KvStore>>,
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
    pak_hash: u64,
    bundle_hash: u64,
    draw_words: Vec<u32>,
    draw_hash: u64,
    previous_draw_hash: u64,
    accessibility_json: Vec<u8>,
    accessibility_hash: Option<u64>,
    damage_tracker: DamageTracker<DEFAULT_DAMAGE_REGIONS>,
    frame_number: u64,
    poll_effect: CString,
    poll_net: CString,
    receipt: CString,
}

fn mount_pod(
    guest: &Guest,
    bridge: Rc<RefCell<PodBridge>>,
    kv: Rc<RefCell<KvStore>>,
) -> anyhow::Result<()> {
    guest.mount("pod", |ctx, ns| {
        let b = bridge.clone();
        ns.set(
            "takeEvents",
            Function::new(ctx.clone(), move || {
                let mut b = b.borrow_mut();
                if b.events.is_empty() {
                    return None;
                };
                let raw = format!("[{}]", b.events.drain(..).collect::<Vec<_>>().join(","));
                Some(raw)
            })?,
        )?;

        let b = bridge.clone();
        ns.set(
            "emit",
            Function::new(ctx.clone(), move |line: String| {
                if serde_json::from_str::<Value>(&line).is_ok() {
                    b.borrow_mut().effects.push_back(line);
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

fn hash_bytes(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for byte in bytes {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
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
        surface.with_ui(|ui| ui.set_rounded_clip_supported(true));
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
        fs,
        net,
        transport,
        bridge,
        kv: Rc::new(RefCell::new(KvStore::open(data_dir))),
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
        pak_hash: 0,
        bundle_hash: 0,
        draw_words: Vec::new(),
        draw_hash: 0,
        previous_draw_hash: u64::MAX,
        accessibility_json: Vec::new(),
        accessibility_hash: None,
        damage_tracker: DamageTracker::new(),
        frame_number: 0,
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
    let bytes = unsafe { slice::from_raw_parts(bytes, length) };
    runtime.pak_hash = hash_bytes(bytes);
    runtime.surface.feed_pak(bytes);
    OK
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_validate_package(
    runtime: *mut PodRuntime,
    manifest_json: *const c_char,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if runtime.mounted || runtime.pak_hash == 0 {
        set_error("pak must load before package validation");
        return ERR_STATE;
    }
    // A new validation attempt replaces the prior decision, including failures.
    runtime.package_validated = false;
    runtime.expected_bundle_hash = None;
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
    let expected_pak = format!("{:016x}", runtime.pak_hash);
    let manifest_bundle = value.get("bundleHash").and_then(Value::as_str);
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
        && value.get("pakHash").and_then(Value::as_str) == Some(expected_pak.as_str())
        && manifest_bundle
            .is_some_and(|hash| hash.len() == 16 && hash.bytes().all(|b| b.is_ascii_hexdigit()))
        && capabilities_match;
    if !valid {
        set_error("package target, ABI, revision, pak hash, or capabilities mismatch");
        return ERR_STATE;
    }
    runtime.package_validated = true;
    runtime.package_abi = package_abi.expect("validated package ABI") as u32;
    // Legacy bundles may assert ui.__hostAbi exactly. Negotiate before mount,
    // preserving the old guest contract without overstating host capabilities.
    runtime.surface.set_identity(&runtime.target_id, runtime.package_abi);
    runtime.expected_bundle_hash = manifest_bundle.map(str::to_owned);
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
    if let Err(error) = runtime
        .surface
        .mount(&runtime.guest)
        .and_then(|_| pocket_fs::mount(&runtime.guest, runtime.fs.clone()))
        .and_then(|_| runtime.net.mount(&runtime.guest))
        .and_then(|_| mount_pod(&runtime.guest, runtime.bridge.clone(), runtime.kv.clone()))
    {
        set_error(format!("surface mount failed: {error}"));
        return ERR_GUEST;
    }
    runtime.mounted = true;
    let label = cstr(label).unwrap_or("app");
    if let Err(error) = runtime.guest.eval(label, bundle) {
        set_error(format!("bundle eval failed: {error}"));
        return ERR_GUEST;
    }
    if !runtime.guest.has_frame() {
        set_error("bundle installed no global frame()");
        return ERR_GUEST;
    }
    runtime.bundle_hash = hash_bytes(bytes);
    let revision = option_env!("PODJS_POCKETJS_REVISION")
        .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086");
    runtime.receipt = CString::new(
        json!({
            "target": runtime.target_id,
            "hostAbi": runtime.host_abi,
            "packageAbi": runtime.package_abi,
            "runtimeAbi": ABI_VERSION,
            "pocketjsRevision": revision,
            "pakHash": format!("{:016x}", runtime.pak_hash),
            "bundleHash": format!("{:016x}", runtime.bundle_hash),
        })
        .to_string(),
    )
    .unwrap_or_default();
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
    if let Err(error) =
        runtime
            .guest
            .frame_with_touch_hits(input.buttons, analog, &words, &hits[..hit_count])
    {
        set_error(format!("guest frame failed: {error}"));
        return ERR_GUEST;
    }
    runtime.surface.tick();
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
    let mut hash = runtime.surface.with_ui(|ui| hash_words(&ui.draw().words));
    runtime.surface.with_ui(|ui| {
        for slot in 0..ui.texture_slot_count() as u32 {
            if let Some((handle, revision, _)) = ui.texture_at_versioned(slot) {
                hash ^= (handle as u32 as u64).rotate_left(slot % 63) ^ revision;
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

    fn valid_config(target: &CString, capabilities: &CString) -> PodRuntimeConfig {
        let mut value = config(target);
        value.capabilities_json = capabilities.as_ptr();
        value
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
                "pocketjsRevision": "0a90bf904d835210e52a11ed275a86d0040b5086",
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
    fn abi_two_hosts_boot_legacy_and_current_guests_without_bypassing_validation() {
        for target_name in ["android-watch", "wearos-watch", "watchos-watch", "harmonyos-watch"] {
            for (host_abi, package_abi, accepted) in [(2,1,true),(2,2,true),(1,1,true),(1,2,false),(2,0,false),(2,3,false)] {
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
                    assert_eq!(receipt["runtimeAbi"],2);
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
