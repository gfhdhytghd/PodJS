use podjs_runtime::{
    PodInputFrame, PodRuntimeConfig, pod_runtime_create, pod_runtime_destroy, pod_runtime_eval_bundle,
    pod_runtime_frame,
    pod_runtime_load_pak, pod_runtime_validate_package, pod_runtime_post_event,
    pod_runtime_pump_service_results, pod_runtime_poll_effect,
};
use podjs_runtime::background_ffi::{pod_background_open, pod_background_execute, pod_background_close};
use serde_json::json;
use std::alloc::{GlobalAlloc, Layout, System};
use std::ffi::{CStr, CString};
use std::sync::atomic::{AtomicBool, Ordering};

unsafe extern "C" {
    fn pod_runtime_memory_budget(bytes: usize, callback: Option<extern "C" fn(u32, usize, u32, u64) -> u64>);
}

fn fnv(bytes: &[u8]) -> String {
    let hash = bytes.iter().fold(0xcbf29ce484222325u64, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
    });
    format!("{hash:016x}")
}

static DENY_LARGE: AtomicBool = AtomicBool::new(false);
static DENY_BACKGROUND: AtomicBool = AtomicBool::new(false);
static FAIL_MALLOC: AtomicBool = AtomicBool::new(false);

struct OomInjector;

unsafe impl GlobalAlloc for OomInjector {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        // Target the backing allocation plus its allocator headers, without
        // denying the small allocations QuickJS uses to throw InternalError.
        if FAIL_MALLOC.load(Ordering::Relaxed)
            && (128 * 1024..128 * 1024 + 128).contains(&layout.size())
        {
            return std::ptr::null_mut();
        }
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) };
    }
}

#[global_allocator]
static INJECTOR: OomInjector = OomInjector;

extern "C" fn account(_: u32, bytes: usize, action: u32, _: u64) -> u64 {
    let denied = action == 4 && DENY_BACKGROUND.load(Ordering::Relaxed)
        || action == 5 && bytes >= 64 * 1024 && DENY_LARGE.load(Ordering::Relaxed);
    u64::from(!denied)
}

#[test]
fn allocation_failure_retries_service_reply_and_preserves_the_input_loop() {
    // This test binary owns its process-wide allocator callback.
    unsafe { pod_runtime_memory_budget(128_000_000, Some(account)) };
    let target = CString::new("android-watch").unwrap();
    let capabilities = CString::new("[]").unwrap();
    let config = PodRuntimeConfig {
        struct_size: std::mem::size_of::<PodRuntimeConfig>() as u32,
        target_id: target.as_ptr(),
        host_abi: 1,
        raster_density: 1,
        physical_width: 320,
        physical_height: 320,
        display_density: 1.0,
        display_shape: 0,
        safe_top: 0.0,
        safe_right: 0.0,
        safe_bottom: 0.0,
        safe_left: 0.0,
        data_dir: std::ptr::null(),
        capabilities_json: capabilities.as_ptr(),
    };
    let runtime = pod_runtime_create(&config);
    assert!(!runtime.is_null());

    let pak = b"pak";
    let bundle = br#"
        globalThis.frame = buttons => {
            if (buttons) {
                const scratch = new Uint8Array(128 * 1024);
                pod.emit(JSON.stringify({buttons, size: scratch.length}));
            }
        };
        function settle(event) {
            const scratch = new Uint8Array(128 * 1024);
            pod.emit(JSON.stringify({reply: event.id, size: scratch.length}));
        }
        globalThis.__pocketPumpServices = () => {
            if (pod.dispatchEvent) pod.dispatchEvent(settle);
            else {
                const event = pod.takeEvent();
                if (event) settle(event);
            }
        };
    "#;
    let manifest = CString::new(json!({
        "target": "android-watch",
        "hostAbi": 1,
        "pocketjsRevision": option_env!("PODJS_POCKETJS_REVISION")
            .unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
        "pakHash": fnv(pak),
        "bundleHash": fnv(bundle),
        "capabilities": []
    }).to_string()).unwrap();
    assert_eq!(pod_runtime_load_pak(runtime, pak.as_ptr(), pak.len()), 0);
    assert_eq!(pod_runtime_validate_package(runtime, manifest.as_ptr()), 0);
    assert_eq!(pod_runtime_eval_bundle(runtime, bundle.as_ptr(), bundle.len(), std::ptr::null()), 0);
    let response = CString::new(r#"{"t":"service.result","id":41,"ok":false}"#).unwrap();
    assert_eq!(pod_runtime_post_event(runtime, response.as_ptr()), 0);
    // Simulate a rejecting host callback to retain the existing retry contract.
    DENY_LARGE.store(true, Ordering::Relaxed);
    let deferred = pod_runtime_pump_service_results(runtime);
    DENY_LARGE.store(false, Ordering::Relaxed);
    assert_eq!(deferred, -4);
    assert!(pod_runtime_poll_effect(runtime).is_null());

    assert_eq!(pod_runtime_pump_service_results(runtime), 0);
    let effect = pod_runtime_poll_effect(runtime);
    assert!(!effect.is_null(), "reply was dropped before the request settled");
    let effect: serde_json::Value =
        serde_json::from_str(unsafe { CStr::from_ptr(effect) }.to_str().unwrap()).unwrap();
    assert_eq!(effect, json!({"reply":41, "size": 128 * 1024}));
    assert_eq!(pod_runtime_pump_service_results(runtime), 0);
    assert!(pod_runtime_poll_effect(runtime).is_null());

    let response = CString::new(r#"{"t":"service.result","id":42,"ok":false}"#).unwrap();
    assert_eq!(pod_runtime_post_event(runtime, response.as_ptr()), 0);
    FAIL_MALLOC.store(true, Ordering::Relaxed);
    let deferred = pod_runtime_pump_service_results(runtime);
    FAIL_MALLOC.store(false, Ordering::Relaxed);
    assert_eq!(deferred, -5);
    assert!(pod_runtime_poll_effect(runtime).is_null());
    assert_eq!(pod_runtime_pump_service_results(runtime), 0);
    let effect = pod_runtime_poll_effect(runtime);
    assert!(!effect.is_null(), "allocator failure dropped the queued reply");
    let effect: serde_json::Value =
        serde_json::from_slice(unsafe { CStr::from_ptr(effect) }.to_bytes()).unwrap();
    assert_eq!(effect, json!({"reply":42,"size":128 * 1024}));
    assert_eq!(pod_runtime_pump_service_results(runtime), 0);
    assert!(pod_runtime_poll_effect(runtime).is_null());

    let mut input = PodInputFrame {
        struct_size: std::mem::size_of::<PodInputFrame>() as u32,
        buttons: 1,
        analog: 0,
        touches: std::ptr::null(),
        touch_count: 0,
        rotary_primary_millidegrees: 0,
        rotary_secondary_millidegrees: 0,
    };
    FAIL_MALLOC.store(true, Ordering::Relaxed);
    let exhausted = pod_runtime_frame(runtime, &input);
    FAIL_MALLOC.store(false, Ordering::Relaxed);
    assert_eq!(exhausted, -5);
    assert!(pod_runtime_poll_effect(runtime).is_null());
    input.buttons = 2;
    assert_eq!(pod_runtime_frame(runtime, &input), 0);
    let effect = pod_runtime_poll_effect(runtime);
    assert!(!effect.is_null(), "next input did not reach the mounted guest");
    let effect: serde_json::Value =
        serde_json::from_slice(unsafe { CStr::from_ptr(effect) }.to_bytes()).unwrap();
    assert_eq!(effect, json!({"buttons":2,"size":128 * 1024}));
    // The process can saturate after Android admitted this job but before
    // QuickJS constructs its runtime. The native result must be retryable.
    let task = br#"{"app_id":"jsoom-fixture","task_id":"background","source":"globalThis.backgroundHandler=async()=> 'success'","budget_ms":1000,"memory_bytes":8388608}"#;
    let background = unsafe { pod_background_open(task.as_ptr(), task.len()) };
    assert!(!background.is_null());
    DENY_BACKGROUND.store(true, Ordering::Relaxed);
    let result = unsafe { pod_background_execute(background) };
    DENY_BACKGROUND.store(false, Ordering::Relaxed);
    assert!(!result.is_null());
    let result: serde_json::Value =
        serde_json::from_slice(unsafe { CStr::from_ptr(result) }.to_bytes()).unwrap();
    assert_eq!(result["status"], "retry");
    assert_eq!(result["code"], "memory_budget_deferred");
    unsafe { pod_background_close(background) };
    pod_runtime_destroy(runtime);
}
