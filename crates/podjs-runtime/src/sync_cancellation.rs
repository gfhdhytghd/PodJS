use std::sync::atomic::{AtomicBool, Ordering};
pub struct PodSyncCancellation { cancelled: AtomicBool }
pub(crate) fn check(value: Option<&PodSyncCancellation>) -> anyhow::Result<()> {
    anyhow::ensure!(!value.is_some_and(|value| value.cancelled.load(Ordering::Acquire)), "file operation cancelled"); Ok(())
}
#[unsafe(no_mangle)]
pub extern "C" fn pod_sync_cancellation_new() -> *mut PodSyncCancellation { Box::into_raw(Box::new(PodSyncCancellation { cancelled: AtomicBool::new(false) })) }
/// # Safety
/// Live shared handle. May run concurrently with operations borrowing it.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_cancellation_cancel(value: *const PodSyncCancellation) {
    if let Some(value) = unsafe { value.as_ref() } { value.cancelled.store(true, Ordering::Release); }
}
/// # Safety
/// Null or live shared handle; null denotes no cancellation.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_cancellation_is_cancelled(value: *const PodSyncCancellation) -> bool {
    unsafe { value.as_ref() }.is_some_and(|value| value.cancelled.load(Ordering::Acquire))
}
/// # Safety
/// Owned live handle with no concurrent borrowers, freed exactly once; null safe.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_cancellation_free(value: *mut PodSyncCancellation) { if !value.is_null() { drop(unsafe { Box::from_raw(value) }); } }
