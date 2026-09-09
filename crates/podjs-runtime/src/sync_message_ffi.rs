use sha2::{Digest, Sha256};
/// SHA-256 for a bounded message payload/envelope, using the common runtime.
/// # Safety
/// `bytes` is readable for `length` bytes (NULL allowed only with length zero),
/// `digest` is writable for 32 bytes and does not overlap `bytes`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_message_sha256(bytes: *const u8, length: usize, digest: *mut u8) -> i32 {
    if length > 262153 || (bytes.is_null() && length != 0) || digest.is_null() { return -1; }
    let input = if length == 0 { &[] } else { unsafe { std::slice::from_raw_parts(bytes, length) } };
    let result = Sha256::digest(input);
    unsafe { std::ptr::copy_nonoverlapping(result.as_ptr(), digest, 32) }; 0
}
