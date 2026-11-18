use podjs_runtime::{
    PodRuntimeConfig, pod_runtime_create, pod_runtime_destroy, pod_runtime_eval_bundle,
    pod_runtime_frame, pod_runtime_load_pak, pod_runtime_validate_package,
};
use serde_json::json;
use std::ffi::CString;

unsafe extern "C" {
    fn pod_runtime_memory_budget(bytes: usize, callback: Option<extern "C" fn(u32, usize, u32, u64) -> u64>);
}

fn fnv(bytes: &[u8]) -> String {
    let hash = bytes.iter().fold(0xcbf29ce484222325u64, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
    });
    format!("{hash:016x}")
}

#[test]
fn a_budgeted_foreground_realm_retains_gc_headroom_above_24_mb() {
    // This test binary owns its process-wide memory configuration.
    unsafe { pod_runtime_memory_budget(128_000_000, None) };
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
        globalThis.library = Array.from({length:8192}, () => new Uint8Array(4096));
        globalThis.frame = () => { globalThis.frameState = {size:library.length}; };
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
    assert_eq!(pod_runtime_frame(runtime, std::ptr::null()), 0);

    let stats = unsafe { &*runtime }.memory_stats();
    pod_runtime_destroy(runtime);
    let allocated = stats["quickjs"]["mallocBytes"].as_u64().unwrap();
    let threshold = stats["quickjs"]["gcThresholdBytes"].as_u64().unwrap();
    assert!(allocated > 24_000_000, "{stats}");
    assert!(
        threshold >= allocated + 1_000_000,
        "GC threshold {threshold} leaves no growth headroom above {allocated} allocated bytes"
    );
}
