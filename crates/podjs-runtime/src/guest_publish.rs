//! No-overwrite publication into a cooperating runtime's guest files directory.
//! The host retains the received-source lease and holds the shared guest IO gate.
#![cfg(any(target_os = "linux", target_os = "android", target_vendor = "apple"))]
use crate::guest_io::PodGuestIo;
use crate::sync_cancellation::{PodSyncCancellation, check};
use anyhow::{Result, ensure};
use sha2::{Digest, Sha256};
use std::{ffi::{c_char, CStr, CString}, fs::File, io::{Read, Write}, os::fd::{AsRawFd, FromRawFd, IntoRawFd}, os::unix::fs::MetadataExt};
const QUOTA: u64 = 16 * 1024 * 1024;
fn file(fd: i32) -> Result<File> { ensure!(fd >= 0, "guest file open: {}", std::io::Error::last_os_error()); Ok(unsafe { File::from_raw_fd(fd) }) }
fn directory(parent: &File, name: &CStr) -> Result<File> {
    let result = file(unsafe { libc::openat(parent.as_raw_fd(), name.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC) })?;
    let metadata = result.metadata()?;
    ensure!(metadata.uid() == unsafe { libc::geteuid() } && metadata.mode() & 0o022 == 0, "unsafe guest directory"); Ok(result)
}
fn errno_pointer() -> *mut i32 {
    #[cfg(target_vendor = "apple")] { unsafe { libc::__error() } }
    #[cfg(not(target_vendor = "apple"))] { unsafe { libc::__errno_location() } }
}
fn entries(parent: &File, maximum: usize) -> Result<Vec<CString>> {
    // Open a new description so enumeration never shares/stales a caller offset.
    let descriptor = directory(parent, c".")?.into_raw_fd();
    let raw = unsafe { libc::fdopendir(descriptor) };
    if raw.is_null() { unsafe { libc::close(descriptor); } anyhow::bail!("cannot enumerate guest directory"); }
    struct Entries(*mut libc::DIR); impl Drop for Entries { fn drop(&mut self) { unsafe { libc::closedir(self.0); } } }
    let held = Entries(raw); let mut names = Vec::new();
    loop {
        unsafe { *errno_pointer() = 0; }
        let entry = unsafe { libc::readdir(held.0) };
        if entry.is_null() { ensure!(unsafe { *errno_pointer() } == 0, "guest directory read failed"); break; }
        let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
        if name == c"." || name == c".." { continue; }
        ensure!(names.len() < maximum, "guest directory entry limit"); names.push(name.to_owned());
    }
    Ok(names)
}
fn metadata(parent: &File, name: &CStr) -> Result<libc::stat> {
    let mut result = std::mem::MaybeUninit::uninit();
    ensure!(unsafe { libc::fstatat(parent.as_raw_fd(), name.as_ptr(), result.as_mut_ptr(), libc::AT_SYMLINK_NOFOLLOW) } == 0, "guest metadata unavailable");
    Ok(unsafe { result.assume_init() })
}
fn usage(parent: &File, depth: usize, count: &mut usize, cancellation: Option<&PodSyncCancellation>) -> Result<u64> {
    ensure!(depth <= 64, "guest directory depth exceeded"); let mut total = 0u64;
    for name in entries(parent, 16384)? {
        check(cancellation)?;
        *count += 1; ensure!(*count <= 16384, "guest entry quota exceeded");
        let info = metadata(parent, &name)?;
        let size = match info.st_mode & libc::S_IFMT {
            libc::S_IFDIR => usage(&directory(parent, &name)?, depth + 1, count, cancellation)?,
            libc::S_IFREG => { ensure!(info.st_size >= 0, "negative guest file length"); info.st_size as u64 }
            _ => anyhow::bail!("unsupported guest file type"),
        };
        total = total.checked_add(size).ok_or_else(|| anyhow::anyhow!("guest quota overflow"))?;
        ensure!(total <= QUOTA, "guest quota exceeded");
    }
    Ok(total)
}
fn regular(parent: &File, name: &CStr) -> Result<File> {
    let input = file(unsafe { libc::openat(parent.as_raw_fd(), name.as_ptr(), libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC) })?;
    ensure!(input.metadata()?.is_file(), "not a regular guest file"); Ok(input)
}
fn verified_copy(input: &mut File, output: Option<&mut File>, size: u64, hash: &str, cancellation: Option<&PodSyncCancellation>) -> Result<()> {
    check(cancellation)?;
    ensure!(input.metadata()?.is_file() && input.metadata()?.len() == size, "source size/type changed");
    stream_verified(input, output, size, hash, cancellation)?;
    ensure!(input.metadata()?.len() == size, "source size changed"); Ok(())
}
fn stream_verified<R: Read, W: Write>(input: &mut R, mut output: Option<&mut W>, size: u64, hash: &str, cancellation: Option<&PodSyncCancellation>) -> Result<()> {
    let mut whole = Sha256::new(); let mut remaining = size; let mut bytes = [0u8; 65536];
    while remaining > 0 {
        check(cancellation)?;
        let count = remaining.min(65536) as usize; input.read_exact(&mut bytes[..count])?;
        whole.update(&bytes[..count]); if let Some(output) = output.as_mut() { output.write_all(&bytes[..count])?; }
        remaining -= count as u64;
    }
    ensure!(input.read(&mut bytes[..1])? == 0 && format!("{:x}", whole.finalize()) == hash, "received file checksum changed"); Ok(())
}
fn recover(gate: &PodGuestIo, cancellation: Option<&PodSyncCancellation>) -> Result<()> {
    for name in entries(&gate.staging, 256)? {
        check(cancellation)?;
        let bytes = name.to_bytes();
        if bytes.starts_with(b"save-") && bytes.len() <= 96 && bytes[5..].iter().all(|byte| byte.is_ascii_hexdigit() || *byte == b'-') {
            let info = metadata(&gate.staging, &name)?;
            ensure!(info.st_mode & libc::S_IFMT == libc::S_IFREG && info.st_uid == unsafe { libc::geteuid() } && info.st_nlink <= 2, "unsafe publication staging");
            ensure!(unsafe { libc::unlinkat(gate.staging.as_raw_fd(), name.as_ptr(), 0) } == 0, "cannot recover publication");
        }
    }
    gate.staging.sync_all()?; Ok(())
}
fn publish(gate: &PodGuestIo, source: &CStr, path: &str, size: u64, hash: &str, cancellation: Option<&PodSyncCancellation>) -> Result<()> {
    check(cancellation)?;
    ensure!(gate.held && size <= QUOTA, "guest IO gate/size invalid");
    ensure!(!path.is_empty() && path.len() <= 1024 && !path.as_bytes().contains(&0), "invalid guest path");
    let components: Vec<_> = path.split('/').collect();
    ensure!(components.len() <= 64 && components.iter().all(|part| !part.is_empty() && *part != "." && *part != ".."), "invalid guest path components");
    ensure!(hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)), "invalid source hash");
    recover(gate, cancellation)?;
    let files = directory(&gate.root, c"files")?;
    let mut parent = directory(&files, c".")?;
    for part in &components[..components.len()-1] { parent = directory(&parent, &CString::new(*part)?)?; }
    let target = CString::new(*components.last().unwrap())?;
    // An existing identical artifact is an idempotent retry, never an overwrite.
    let mut info = std::mem::MaybeUninit::<libc::stat>::uninit();
    if unsafe { libc::fstatat(parent.as_raw_fd(), target.as_ptr(), info.as_mut_ptr(), libc::AT_SYMLINK_NOFOLLOW) } == 0 {
        verified_copy(&mut regular(&parent, &target)?, None, size, hash, cancellation)?; parent.sync_all()?; return Ok(());
    }
    ensure!(std::io::Error::last_os_error().raw_os_error() == Some(libc::ENOENT), "cannot inspect destination");
    ensure!(usage(&files, 0, &mut 0, cancellation)? + size <= QUOTA, "guest quota exceeded"); check(cancellation)?;
    let mut input = file(unsafe { libc::open(source.as_ptr(), libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC) })?;
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let name = CString::new(format!("save-{:x}-{:x}-{:x}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos(), NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)))?;
    let mut output = file(unsafe { libc::openat(gate.staging.as_raw_fd(), name.as_ptr(), libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC, 0o600) })?;
    let result = (|| -> Result<()> {
        verified_copy(&mut input, Some(&mut output), size, hash, cancellation)?; output.sync_all()?;
        check(cancellation)?; // Last cancellation boundary before atomic commit.
        if unsafe { libc::linkat(gate.staging.as_raw_fd(), name.as_ptr(), parent.as_raw_fd(), target.as_ptr(), 0) } != 0 {
            ensure!(std::io::Error::last_os_error().raw_os_error() == Some(libc::EEXIST), "cannot publish guest file");
            verified_copy(&mut regular(&parent, &target)?, None, size, hash, None)?;
        }
        parent.sync_all()?; Ok(())
    })();
    let removed = unsafe { libc::unlinkat(gate.staging.as_raw_fd(), name.as_ptr(), 0) };
    let synced = gate.staging.sync_all();
    result?; ensure!(removed == 0, "publication cleanup failed"); synced?; Ok(())
}
/// # Safety
/// Live exclusively borrowed acquired gate and valid host strings. Source is a
/// host-private verified artifact under its receiver lease, path is guest-relative.
/// Returns 0 or -1. Failure after publication may still leave a complete target.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_guest_publish(gate: *const PodGuestIo, source: *const c_char, path: *const c_char, size: u64, hash: *const c_char) -> i32 {
    unsafe { pod_guest_publish_cancellable(gate, source, path, size, hash, std::ptr::null()) }
}
/// # Safety
/// Same as pod_guest_publish; optional cancellation is live through this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_guest_publish_cancellable(gate: *const PodGuestIo, source: *const c_char, path: *const c_char, size: u64, hash: *const c_char, cancellation: *const PodSyncCancellation) -> i32 {
    let Some(gate) = (unsafe { gate.as_ref() }) else { return -1; };
    if source.is_null() || path.is_null() || hash.is_null() { return -1; }
    let result = (|| -> Result<()> { publish(gate, unsafe { CStr::from_ptr(source) }, unsafe { CStr::from_ptr(path) }.to_str()?, size, unsafe { CStr::from_ptr(hash) }.to_str()?, unsafe { cancellation.as_ref() }) })();
    match result { Ok(()) => 0, Err(error) => { crate::set_error(error.to_string()); -1 } }
}
#[cfg(test)] mod tests {
    use super::*;
    use crate::sync_cancellation::*;
    #[test] fn cancellation_after_first_write_stops_next_block() {
        let raw = pod_sync_cancellation_new(); let cancellation = unsafe { &*raw };
        struct Output<'a> { cancellation: &'a PodSyncCancellation, written: usize }
        impl Write for Output<'_> {
            fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
                self.written += bytes.len(); unsafe { pod_sync_cancellation_cancel(self.cancellation); } Ok(bytes.len())
            }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let bytes = vec![7u8; 131072]; let hash = format!("{:x}", Sha256::digest(&bytes));
        let mut input = std::io::Cursor::new(bytes); let mut output = Output { cancellation, written: 0 };
        assert!(stream_verified(&mut input, Some(&mut output), 131072, &hash, Some(cancellation)).is_err());
        assert_eq!(output.written,65536); drop(output); unsafe { pod_sync_cancellation_free(raw); }
    }
}
