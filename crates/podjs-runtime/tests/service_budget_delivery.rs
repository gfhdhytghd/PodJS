use podjs_runtime::{
    PodRuntimeConfig, pod_runtime_create, pod_runtime_destroy, pod_runtime_eval_bundle,
    pod_runtime_load_pak, pod_runtime_validate_package, pod_runtime_post_event,
    pod_runtime_pump_service_results, pod_runtime_poll_effect,
};
use serde_json::json;
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

extern "C" fn account(_: u32, bytes: usize, action: u32, _: u64) -> u64 {
    u64::from(!(action == 0 && bytes >= 64 * 1024 && DENY_LARGE.load(Ordering::Relaxed)))
}

#[test]
fn service_reply_is_retried_if_dispatch_runs_out_of_budget() {
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
        globalThis.frame = () => {};
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
    pod_runtime_destroy(runtime);
}
