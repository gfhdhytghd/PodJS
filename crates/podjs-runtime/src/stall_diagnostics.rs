//! Host watchdog bridge. The request handle owns only an Arc of atomics, not
//! a runtime pointer, and can outlive runtime destruction safely.
use super::*;
use pocket_mod::stall_diagnostics::StackProbe;
use std::sync::Arc;

pub struct PodJsStackProbe { state: Arc<StackProbe> }
pub type PodJsStackSink = unsafe extern "C" fn(usize, *const u8, usize);

/// Runtime thread only. Sink runs synchronously at an interrupt point, must be
/// bounded and must not reenter the guest. Destroying this handle disables it.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_js_stack_probe(runtime: *mut PodRuntime, sink: Option<PodJsStackSink>, userdata: usize) -> *mut PodJsStackProbe {
    let (Ok(runtime), Some(sink)) = (runtime_mut(runtime), sink) else { return std::ptr::null_mut(); };
    let state = runtime.guest.install_stall_diagnostics(move |line| unsafe { sink(userdata, line.as_ptr(), line.len()); });
    Box::into_raw(Box::new(PodJsStackProbe { state }))
}

/// Any thread, serialized with handle close by the host. No QuickJS access.
#[unsafe(no_mangle)]
pub extern "C" fn pod_js_stack_probe_request(probe: *const PodJsStackProbe, phase: u32, delay_ms: u32) {
    if let Some(probe) = unsafe {probe.as_ref()} { probe.state.request(phase.clamp(1,6) as u8, delay_ms); }
}
#[unsafe(no_mangle)]
pub extern "C" fn pod_js_stack_probe_cancel(probe: *const PodJsStackProbe) {
    if let Some(probe) = unsafe {probe.as_ref()} { probe.state.cancel(); }
}
/// Serialize with request/cancel. The JS-thread Arc remains valid until drop.
#[unsafe(no_mangle)]
pub extern "C" fn pod_js_stack_probe_close(probe: *mut PodJsStackProbe) {
    if !probe.is_null() {
        let probe = unsafe {Box::from_raw(probe)};
        probe.state.enable(false);
    }
}
