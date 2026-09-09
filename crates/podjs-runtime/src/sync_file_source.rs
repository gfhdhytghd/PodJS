//! Host-approved file descriptor retained across manifest scan and import reads.
//! No writes and no guest/network path API. Destination publication is separate.
use crate::sync_files::{FileManifest, CHUNK_BYTES, MAX_FILE_BYTES};
use crate::sync_cancellation::{PodSyncCancellation, check};
use anyhow::{Result, ensure};
use sha2::{Digest, Sha256};
use std::{ffi::{c_char, CStr, CString}, fs::{File, OpenOptions}, io::{Read, Seek, SeekFrom}, path::Path};

pub struct PodSyncFileSource { input: File, manifest: FileManifest, manifest_json: CString, chunk: Vec<u8> }
impl PodSyncFileSource {
    #[cfg(test)]
    fn open(path: &Path, id: &str, mime: &str) -> Result<Self> { Self::open_cancellable(path, id, mime, None) }
    fn open_cancellable(path: &Path, id: &str, mime: &str, cancellation: Option<&PodSyncCancellation>) -> Result<Self> {
        check(cancellation)?;
        let mut manifest = FileManifest { transfer_id: id.into(), size: 0,
            sha256: "0".repeat(64), chunk_hashes: Vec::new(), mime: mime.into() };
        manifest.validate()?;
        ensure!(std::fs::symlink_metadata(path)?.is_file(), "source is not regular");
        let mut options = OpenOptions::new(); options.read(true);
        #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK); }
        let mut input = options.open(path)?; let metadata = input.metadata()?;
        ensure!(metadata.is_file() && metadata.len() <= MAX_FILE_BYTES, "source size/type invalid");
        manifest.size = metadata.len();
        let mut whole = Sha256::new(); let mut buffer = [0u8; CHUNK_BYTES]; let mut remaining = manifest.size;
        while remaining > 0 {
            check(cancellation)?;
            let count = remaining.min(CHUNK_BYTES as u64) as usize;
            input.read_exact(&mut buffer[..count])?; whole.update(&buffer[..count]);
            manifest.chunk_hashes.push(format!("{:x}", Sha256::digest(&buffer[..count]))); remaining -= count as u64;
        }
        ensure!(input.read(&mut buffer[..1])? == 0 && input.metadata()?.len() == manifest.size, "source changed during scan");
        check(cancellation)?;
        manifest.sha256 = format!("{:x}", whole.finalize()); manifest.validate()?;
        let manifest_json = CString::new(serde_json::to_vec(&manifest)?)?;
        Ok(Self { input, manifest, manifest_json, chunk: Vec::new() })
    }
    fn read_chunk(&mut self, index: usize) -> Result<()> {
        self.chunk.clear();
        ensure!(index < self.manifest.chunk_hashes.len(), "source chunk index invalid");
        ensure!(self.input.metadata()?.len() == self.manifest.size, "source size changed");
        let offset = index as u64 * CHUNK_BYTES as u64;
        let count = (self.manifest.size - offset).min(CHUNK_BYTES as u64) as usize;
        self.input.seek(SeekFrom::Start(offset))?;
        let mut chunk = vec![0u8; count]; self.input.read_exact(&mut chunk)?;
        ensure!(self.input.metadata()?.len() == self.manifest.size &&
            format!("{:x}", Sha256::digest(&chunk)) == self.manifest.chunk_hashes[index], "source chunk changed");
        self.chunk = chunk; Ok(())
    }
}
/// # Safety
/// All arguments are valid NUL-terminated strings from trusted host input.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_open(path: *const c_char, id: *const c_char, mime: *const c_char) -> *mut PodSyncFileSource {
    unsafe { pod_sync_file_source_open_cancellable(path, id, mime, std::ptr::null()) }
}
/// # Safety
/// Host strings and optional cancellation handle remain live throughout the scan.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_open_cancellable(path: *const c_char, id: *const c_char, mime: *const c_char, cancellation: *const PodSyncCancellation) -> *mut PodSyncFileSource {
    if path.is_null() || id.is_null() || mime.is_null() { return std::ptr::null_mut(); }
    let result = (|| -> Result<_> {
        let path = unsafe { CStr::from_ptr(path) }.to_str()?;
        let id = unsafe { CStr::from_ptr(id) }.to_str()?;
        let mime = unsafe { CStr::from_ptr(mime) }.to_str()?;
        PodSyncFileSource::open_cancellable(Path::new(path), id, mime, unsafe { cancellation.as_ref() })
    })();
    match result { Ok(source) => Box::into_raw(Box::new(source)), Err(error) => { crate::set_error(error.to_string()); std::ptr::null_mut() } }
}
/// # Safety
/// Live handle; returned manifest bytes remain borrowed until close.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_manifest(source: *const PodSyncFileSource) -> *const c_char {
    unsafe { source.as_ref() }.map_or(std::ptr::null(), |source| source.manifest_json.as_ptr())
}
/// # Safety
/// Live source; call after the read pass (also for an empty source).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_check(source: *const PodSyncFileSource) -> bool {
    unsafe { source.as_ref() }.is_some_and(|source| source.input.metadata().is_ok_and(|metadata| metadata.len() == source.manifest.size))
}
/// # Safety
/// Exclusively borrowed live source and writable length. Copy returned bytes
/// before another read or close. Failure returns null and length zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_read(source: *mut PodSyncFileSource, index: usize, length: *mut usize) -> *const u8 {
    if length.is_null() { return std::ptr::null(); }
    unsafe { *length = 0; }
    let Some(source) = (unsafe { source.as_mut() }) else { return std::ptr::null(); };
    match source.read_chunk(index) {
        Ok(()) => { unsafe { *length = source.chunk.len(); } source.chunk.as_ptr() }
        Err(error) => { crate::set_error(error.to_string()); std::ptr::null() }
    }
}
/// # Safety
/// Null or owned live handle, closed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_source_close(source: *mut PodSyncFileSource) {
    if !source.is_null() { drop(unsafe { Box::from_raw(source) }); }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn retained_descriptor_and_changed_chunks() {
        let root = std::env::temp_dir().join(format!("podjs-source-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&root).unwrap();
        let path = root.join("source"); std::fs::write(&path, b"abc").unwrap();
        let mut source = PodSyncFileSource::open(&path, "id", "text/plain").unwrap();
        assert_eq!(source.manifest.sha256, format!("{:x}", Sha256::digest(b"abc")));
        std::fs::rename(&path, root.join("retained")).unwrap(); std::fs::write(&path, b"bad").unwrap();
        source.read_chunk(0).unwrap(); assert_eq!(source.chunk, b"abc");
        std::fs::write(root.join("retained"), b"xyz").unwrap(); assert!(source.read_chunk(0).is_err()); assert!(source.chunk.is_empty());
        assert!(source.read_chunk(1).is_err());
        let empty = root.join("empty"); std::fs::write(&empty, b"").unwrap();
        let source = PodSyncFileSource::open(&empty, "empty", "").unwrap();
        assert!(unsafe { pod_sync_file_source_check(&source) });
        std::fs::write(&empty, b"grew").unwrap(); assert!(!unsafe { pod_sync_file_source_check(&source) });
        drop(source); std::fs::remove_dir_all(&root).unwrap();
    }
    #[test] fn null_source_calls_fail_closed() { unsafe {
        assert!(pod_sync_file_source_open(std::ptr::null(), std::ptr::null(), std::ptr::null()).is_null());
        assert!(pod_sync_file_source_manifest(std::ptr::null()).is_null());
        assert!(!pod_sync_file_source_check(std::ptr::null()));
        let mut size = 9; assert!(pod_sync_file_source_read(std::ptr::null_mut(), 0, &mut size).is_null()); assert_eq!(size, 0);
        pod_sync_file_source_close(std::ptr::null_mut());
    } }
}
